/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.rust.ide.inspections.import.AutoImportFix
import org.rust.ide.inspections.import.ImportCandidate
import org.rust.ide.inspections.import.ImportContext
import org.rust.ide.inspections.import.import
import org.rust.ide.settings.RsCodeInsightSettings
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.psiElement
import org.rust.lang.core.resolve.*
import org.rust.lang.core.resolve.ref.FieldResolveVariant
import org.rust.lang.core.resolve.ref.MethodResolveVariant
import org.rust.lang.core.stubs.index.RsNamedElementIndex
import org.rust.lang.core.stubs.index.RsReexportIndex
import org.rust.lang.core.types.expectedType
import org.rust.lang.core.types.infer.containsTyOfClass
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyPrimitive
import org.rust.lang.core.types.ty.TyTypeParameter
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.lang.core.types.type
import org.rust.openapiext.Testmark

object RsCommonCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        // Use original position if possible to re-use caches of the real file
        val position = parameters.position.safeGetOriginalOrSelf()
        val element = position.parent as RsReferenceElement
        if (position !== element.referenceNameElement) return

        // This set will contain the names of all paths that have been added to the `result` by this provider.
        val processedPathNames = hashSetOf<String>()

        val isSimplePath = simplePathPattern.accepts(parameters.position)
        val expectedTy = getExpectedTypeForEnclosingPathOrDotExpr(element)
        collectCompletionVariants(result, isSimplePath, expectedTy) {
            when (element) {
                is RsAssocTypeBinding -> processAssocTypeVariants(element, it)
                is RsExternCrateItem -> processExternCrateResolveVariants(element, true, it)
                is RsLabel -> processLabelResolveVariants(element, it)
                is RsLifetime -> processLifetimeResolveVariants(element, it)
                is RsMacroReference -> processMacroReferenceVariants(element, it)
                is RsModDeclItem -> processModDeclResolveVariants(element, it)
                is RsPatBinding -> processPatBindingResolveVariants(element, true, it)
                is RsStructLiteralField -> processStructLiteralFieldResolveVariants(element, true, it)

                is RsPath -> {
                    val lookup = ImplLookup.relativeTo(element)
                    processPathResolveVariants(
                        lookup,
                        element,
                        true,
                        filterAssocTypes(
                            element,
                            filterCompletionVariantsByVisibility(
                                filterPathCompletionVariantsByTraitBounds(
                                    addProcessedPathName(it, processedPathNames),
                                    lookup
                                ),
                                element.containingMod
                            )
                        )
                    )
                }
            }
        }

        if (element is RsMethodOrField) {
            addMethodAndFieldCompletion(element, result, isSimplePath, expectedTy)
        }

        if (isSimplePath && RsCodeInsightSettings.getInstance().suggestOutOfScopeItems) {
            addCompletionsFromIndex(parameters, context, result, processedPathNames, expectedTy)
        }
    }

    private fun addMethodAndFieldCompletion(
        element: RsMethodOrField,
        result: CompletionResultSet,
        forSimplePath: Boolean = false,
        expectedTy: Ty? = null
    ) {
        val receiver = element.receiver.safeGetOriginalOrSelf()
        val lookup = ImplLookup.relativeTo(receiver)
        val receiverTy = receiver.type
        val processResolveVariants = if (element is RsMethodCall) {
            ::processMethodCallExprResolveVariants
        } else {
            ::processDotExprResolveVariants
        }
        val processor = methodAndFieldCompletionProcessor(element, result, forSimplePath, expectedTy)

        processResolveVariants(
            lookup,
            receiverTy,
            filterCompletionVariantsByVisibility(
                filterMethodCompletionVariantsByTraitBounds(processor, lookup, receiverTy),
                receiver.containingMod
            )
        )
    }

    private fun addCompletionsFromIndex(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
        processedPathNames: Set<String>,
        expectedTy: Ty?
    ) {
        val sourceParameters = context.sourceCompletionParameters
        val isMacroBodyCompletion = sourceParameters != null
        // We use the position in the original file in order not to process empty paths
        val originalPosition = (sourceParameters ?: parameters).originalPosition ?: return
        val actualPosition = if (isMacroBodyCompletion) parameters.position else originalPosition
        if (sourceParameters != null &&
            sourceParameters.position.contextOrSelf<RsElement>()?.containingMod !=
            actualPosition.contextOrSelf<RsElement>()?.containingMod) {
            return
        }
        val path = actualPosition.parent as? RsPath ?: return
        if (TyPrimitive.fromPath(path) != null) return
        Testmarks.pathCompletionFromIndex.hit()

        val project = parameters.originalFile.project
        val importContext = ImportContext.from(project, path, true)

        val keys = hashSetOf<String>().apply {
            val explicitNames = StubIndex.getInstance().getAllKeys(RsNamedElementIndex.KEY, project)
            val reexportedNames = StubIndex.getInstance().getAllKeys(RsReexportIndex.KEY, project)

            addAll(explicitNames)
            addAll(reexportedNames)

            // Filters out path names that have already been added to `result`
            removeAll(processedPathNames)
        }

        for (elementName in CompletionUtil.sortMatching(result.prefixMatcher, keys)) {
            val candidates = AutoImportFix.getImportCandidates(importContext, elementName, elementName) {
                !(it.item is RsMod || it.item is RsModDeclItem || it.item.parent is RsMembers)
            }

            candidates
                .distinctBy { it.qualifiedNamedItem.item }
                .map { candidate ->
                    val item = candidate.qualifiedNamedItem.item
                    createLookupElement(
                        element = item,
                        scopeName = elementName,
                        locationString = candidate.info.usePath,
                        forSimplePath = true,
                        expectedTy = expectedTy,
                        insertHandler = object : RsDefaultInsertHandler() {
                            override fun handleInsert(
                                element: RsElement,
                                scopeName: String,
                                context: InsertionContext,
                                item: LookupElement
                            ) {
                                super.handleInsert(element, scopeName, context, item)
                                if (RsCodeInsightSettings.getInstance().importOutOfScopeItems) {
                                    context.commitDocument()
                                    context.getElementOfType<RsElement>()?.let { candidate.import(it) }
                                }
                            }
                        }
                    )
                }
                .forEach(result::addElement)
        }
    }

    val elementPattern: ElementPattern<PsiElement>
        get() = PlatformPatterns.psiElement().withParent(psiElement<RsReferenceElement>())

    private val simplePathPattern: ElementPattern<PsiElement>
        get() {
            val simplePath = psiElement<RsPath>()
                .with(object : PatternCondition<RsPath>("SimplePath") {
                    override fun accepts(path: RsPath, context: ProcessingContext?): Boolean =
                        path.kind == PathKind.IDENTIFIER &&
                            path.path == null &&
                            path.typeQual == null &&
                            !path.hasColonColon &&
                            path.ancestorStrict<RsUseSpeck>() == null
                })
            return PlatformPatterns.psiElement().withParent(simplePath)
        }

    object Testmarks {
        val pathCompletionFromIndex = Testmark("pathCompletionFromIndex")
    }
}

private fun filterAssocTypes(
    path: RsPath,
    processor: RsResolveProcessor
): RsResolveProcessor {
    val qualifier = path.path
    val allAssocItemsAllowed =
        qualifier == null || qualifier.hasCself || qualifier.reference.resolve() is RsTypeParameter
    return if (allAssocItemsAllowed) processor else fun(it: ScopeEntry): Boolean {
        if (it is AssocItemScopeEntry && (it.element is RsTypeAlias)) return false
        return processor(it)
    }
}

private fun filterPathCompletionVariantsByTraitBounds(
    processor: RsResolveProcessor,
    lookup: ImplLookup
): RsResolveProcessor {
    val cache = hashMapOf<RsImplItem, Boolean>()
    return fun(it: ScopeEntry): Boolean {
        if (it !is AssocItemScopeEntry) return processor(it)
        if (it.source !is TraitImplSource.ExplicitImpl) return processor(it)

        val receiver = it.subst[TyTypeParameter.self()] ?: return processor(it)
        // Don't filter partially unknown types
        if (receiver.containsTyOfClass(TyUnknown::class.java)) return processor(it)
        // Filter members by trait bounds (try to select all obligations for each impl)
        // We're caching evaluation results here because we can often complete members
        // in the same impl and always have the same receiver type
        val canEvaluate = cache.getOrPut(it.source.value) {
            lookup.ctx.canEvaluateBounds(it.source.value, receiver)
        }
        if (canEvaluate) return processor(it)

        return false
    }
}

private fun filterMethodCompletionVariantsByTraitBounds(
    processor: RsResolveProcessor,
    lookup: ImplLookup,
    receiver: Ty
): RsResolveProcessor {
    // Don't filter partially unknown types
    if (receiver.containsTyOfClass(TyUnknown::class.java)) return processor

    val cache = mutableMapOf<RsImplItem, Boolean>()
    return fun(it: ScopeEntry): Boolean {
        // If not a method (actually a field) or a trait method - just process it
        if (it !is MethodResolveVariant || it.source !is TraitImplSource.ExplicitImpl) return processor(it)
        // Filter methods by trait bounds (try to select all obligations for each impl)
        // We're caching evaluation results here because we can often complete methods
        // in the same impl and always have the same receiver type
        val canEvaluate = cache.getOrPut(it.source.value) {
            lookup.ctx.canEvaluateBounds(it.source.value, receiver)
        }
        if (canEvaluate) return processor(it)

        return false
    }
}

private fun methodAndFieldCompletionProcessor(
    methodOrField: RsMethodOrField,
    result: CompletionResultSet,
    forSimplePath: Boolean = false,
    expectedTy: Ty? = null
): RsResolveProcessor = fun(e: ScopeEntry): Boolean {
    when (e) {
        is FieldResolveVariant -> result.addElement(createLookupElement(
            element = e.element,
            scopeName = e.name,
            forSimplePath = forSimplePath,
            expectedTy = expectedTy
        ))
        is MethodResolveVariant -> {
            if (e.element.isTest) return false

            result.addElement(createLookupElement(
                element = e.element,
                scopeName = e.name,
                forSimplePath = forSimplePath,
                expectedTy = expectedTy,
                insertHandler = object : RsDefaultInsertHandler() {
                    override fun handleInsert(
                        element: RsElement,
                        scopeName: String,
                        context: InsertionContext,
                        item: LookupElement
                    ) {
                        val traitImportCandidate = findTraitImportCandidate(methodOrField, e)
                        super.handleInsert(element, scopeName, context, item)

                        if (traitImportCandidate != null) {
                            context.commitDocument()
                            context.getElementOfType<RsElement>()?.let { traitImportCandidate.import(it) }
                        }
                    }
                }
            ))
        }
    }
    return false
}

private fun findTraitImportCandidate(methodOrField: RsMethodOrField, resolveVariant: MethodResolveVariant): ImportCandidate? {
    if (!RsCodeInsightSettings.getInstance().importOutOfScopeItems) return null
    val ancestor = PsiTreeUtil.getParentOfType(methodOrField, RsBlock::class.java, RsMod::class.java) ?: return null
    // `AutoImportFix.getImportCandidates` expects original scope element for correct item filtering
    val scope = CompletionUtil.getOriginalElement(ancestor) as? RsElement ?: return null
    return AutoImportFix.getImportCandidates(methodOrField.project, scope, listOf(resolveVariant)).orEmpty().singleOrNull()
}

private fun addProcessedPathName(
    processor: RsResolveProcessor,
    processedPathNames: MutableSet<String>
): RsResolveProcessor = fun(it: ScopeEntry): Boolean {
    if (it.element != null) {
        processedPathNames.add(it.name)
    }
    return processor(it)
}

private fun getExpectedTypeForEnclosingPathOrDotExpr(element: RsReferenceElement): Ty? {
    for (ancestor in element.ancestors) {
        if (element.endOffset < ancestor.endOffset) continue
        if (element.endOffset > ancestor.endOffset) break
        when (ancestor) {
            is RsPathExpr -> return ancestor.expectedType
            is RsDotExpr -> return ancestor.expectedType
        }
    }
    return null
}
