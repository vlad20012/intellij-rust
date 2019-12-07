/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.rust.ide.icons.RsIcons
import org.rust.ide.presentation.getStubOnlyText
import org.rust.ide.refactoring.RsNamesValidator
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.AssocItemScopeEntryBase
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.resolve.ScopeEntry
import org.rust.lang.core.resolve.ref.FieldResolveVariant
import org.rust.lang.core.types.*
import org.rust.lang.core.types.infer.*
import org.rust.lang.core.types.ty.*

const val KEYWORD_PRIORITY = 80.0
const val PRIMITIVE_TYPE_PRIORITY = KEYWORD_PRIORITY
const val FRAGMENT_SPECIFIER_PRIORITY = KEYWORD_PRIORITY
private const val VARIABLE_PRIORITY = 5.0
private const val ENUM_VARIANT_PRIORITY = 4.0
private const val FIELD_DECL_PRIORITY = 3.0
private const val ASSOC_FN_PRIORITY = 2.0
private const val DEFAULT_PRIORITY = 0.0
private const val MACRO_PRIORITY = -0.1
private const val DEPRECATED_PRIORITY = -1.0

private const val EXPECTED_TYPE_PRIORITY_OFFSET = 40.0
private const val LOCAL_PRIORITY_OFFSET = 20.0
private const val INHERENT_IMPL_MEMBER_PRIORITY_OFFSET = 0.1

fun createLookupElement(
    scopeEntry: ScopeEntry,
    context: RsCompletionContext,
    locationString: String? = null,
    insertHandler: InsertHandler<LookupElement> = RsDefaultInsertHandler()
): LookupElement {
    val element = checkNotNull(scopeEntry.element) { "Invalid scope entry" }
    val subst = context.lookup?.ctx?.getSubstitution(scopeEntry) ?: emptySubstitution
    val base = element.getLookupElementBuilder(scopeEntry.name, subst)
        .withInsertHandler(insertHandler)
        .let { if (locationString != null) it.appendTailText(" ($locationString)", true) else it }

    var priority = when {
        element is RsDocAndAttributeOwner && element.queryAttributes.deprecatedAttribute != null -> DEPRECATED_PRIORITY
        element is RsMacro -> MACRO_PRIORITY
        element is RsPatBinding -> VARIABLE_PRIORITY
        element is RsEnumVariant -> ENUM_VARIANT_PRIORITY
        element is RsFieldDecl -> FIELD_DECL_PRIORITY
        element is RsFunction && element.isAssocFn -> ASSOC_FN_PRIORITY
        else -> DEFAULT_PRIORITY
    }

    if (element is RsAbstractable && element.owner.isInherentImpl) {
        priority += INHERENT_IMPL_MEMBER_PRIORITY_OFFSET
    }

    if (context.isSimplePath && !element.canBeExported) {
        // It's visible and can't be exported = it's local
        priority += LOCAL_PRIORITY_OFFSET
    }

    if (isCompatibleTypes(element.implLookup, element.asTy, context.expectedTy)) {
        priority += EXPECTED_TYPE_PRIORITY_OFFSET
    }

    return base.withPriority(priority)
}

private fun RsInferenceContext.getSubstitution(scopeEntry: ScopeEntry): Substitution =
    when (scopeEntry) {
        is AssocItemScopeEntryBase<*> -> {
            val ownerSubst = instantiateMethodOwnerSubstitution(scopeEntry).mapTypeValues { (_, v) -> resolveTypeVarsIfPossible(v) }
//                + mapOf(TyTypeParameter.self() to scopeEntry.selfTy).toTypeSubst()
            val element = scopeEntry.element
            val s = if (element is RsFunction) {
                val signature = element.type
                val generics = element.generics
                val occurences = generics.associateWith { 0 }.toMutableMap()
                signature.computeOccurences(occurences)
                val singleUsageGenerics = occurences.entries.mapNotNull { (ty, count) -> ty.takeIf { count == 1 } }
                val occurencesInBounds = singleUsageGenerics.associateWith { 0 }.toMutableMap()
                val bounds = element.bounds
                bounds.forEach { it.computeOccurences(occurencesInBounds) }
                val singleUsageGenerics2 = occurencesInBounds.entries.mapNotNull { (ty, count) -> ty.takeIf { count == 1 } }
                singleUsageGenerics2.mapNotNull { generic ->
                    bounds.find { it.selfTy == generic }?.let { generic to it }
                }.associate { (generic, bound) -> generic to TyAnon(null, listOf(bound.trait)) }
            } else {
                emptyMap()
            }.toTypeSubst()
//            val s3 = s.substituteInValues(ownerSubst).mapTypeValues { (_, v) ->
//                val (r, o) = normalizeAssociatedTypesIn(v)
//                o.forEach(fulfill::registerPredicateObligation)
//                r
//            }
//            fulfill.selectWherePossible()
//            (s3 + ownerSubst).mapTypeValues { (_, v) -> resolveTypeVarsIfPossible(v) }
            s.substituteInValues(ownerSubst) + ownerSubst
        }
        is FieldResolveVariant ->
            scopeEntry.selfTy.typeParameterValues
        else ->
            emptySubstitution
    }

private fun <T> TypeFoldable<T>.computeOccurences(occurences: MutableMap<TyTypeParameter, Int>) {
    visitTyTypeParameterWith {
        occurences.computeIfPresent(it) { _, old -> old + 1 }
        false
    }
}

private val RsElement.asTy: Ty?
    get() = when (this) {
        is RsConstant -> typeReference?.type
        is RsConstParameter -> typeReference?.type
        is RsFieldDecl -> typeReference?.type
        is RsFunction -> retType?.typeReference?.type
        is RsStructItem -> declaredType
        is RsEnumVariant -> parentEnum.declaredType
        is RsPatBinding -> type
        else -> null
    }

fun LookupElementBuilder.withPriority(priority: Double): LookupElement =
    if (priority == DEFAULT_PRIORITY) this else PrioritizedLookupElement.withPriority(this, priority)

private fun RsElement.getLookupElementBuilder(scopeName: String, subst: Substitution): LookupElementBuilder {
    val base = LookupElementBuilder.createWithSmartPointer(scopeName, this)
        .withIcon(if (this is RsFile) RsIcons.MODULE else this.getIcon(0))
        .withStrikeoutness(this is RsDocAndAttributeOwner && queryAttributes.deprecatedAttribute != null)

    return when (this) {
        is RsMod -> if (scopeName == "self" || scopeName == "super" || scopeName == "crate") {
            base.withTailText("::")
        } else {
            base
        }

        is RsConstant -> base
            .withTypeText(typeReference?.getStubOnlyText(subst))
        is RsConstParameter -> base
            .withTypeText(typeReference?.getStubOnlyText(subst))
        is RsFieldDecl -> base
            .withTypeText(typeReference?.getStubOnlyText(subst))
        is RsTraitItem -> base

        is RsFunction -> base
            .withTypeText(retType?.typeReference?.getStubOnlyText(subst) ?: "()")
            .withTailText(valueParameterList?.getStubOnlyText(subst) ?: "()")
            .appendTailText(getExtraTailText(subst), true)

        is RsStructItem -> base
            .withTailText(getFieldsOwnerTailText(this, subst))

        is RsEnumVariant -> base
            .withTypeText(stubAncestorStrict<RsEnumItem>()?.name ?: "")
            .withTailText(getFieldsOwnerTailText(this, subst))

        is RsPatBinding -> base
            .withTypeText(type.let {
                when (it) {
                    is TyUnknown -> ""
                    else -> it.toString()
                }
            })

        is RsMacroBinding -> base.withTypeText(fragmentSpecifier)

        is RsMacro -> base.withTailText("!")

        else -> base
    }
}

private fun getFieldsOwnerTailText(owner: RsFieldsOwner, subst: Substitution): String = when {
    owner.blockFields != null -> " { ... }"
    owner.tupleFields != null ->
        owner.positionalFields.joinToString(prefix = "(", postfix = ")") { it.typeReference.getStubOnlyText(subst) }
    else -> ""
}

open class RsDefaultInsertHandler : InsertHandler<LookupElement> {

    final override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val element = item.psiElement as? RsElement ?: return
        val scopeName = item.lookupString
        handleInsert(element, scopeName, context, item)
    }

    protected open fun handleInsert(
        element: RsElement,
        scopeName: String,
        context: InsertionContext,
        item: LookupElement
    ) {
        val document = context.document
        val startOffset = context.startOffset
        val curUseItem = context.getElementOfType<RsUseItem>()
        if (element is RsNameIdentifierOwner && !RsNamesValidator.isIdentifier(scopeName) && scopeName !in CAN_NOT_BE_ESCAPED) {
            document.insertString(startOffset, RS_RAW_PREFIX)
        }
        when (element) {

            is RsMod -> {
                when (scopeName) {
                    "self",
                    "super" -> {
                        val inSelfParam = context.getElementOfType<RsSelfParameter>() != null
                        if (!(context.isInUseGroup || inSelfParam)) {
                            context.addSuffix("::")
                        }
                    }
                    "crate" -> context.addSuffix("::")
                }
            }

            is RsConstant -> appendSemicolon(context, curUseItem)
            is RsTraitItem -> appendSemicolon(context, curUseItem)
            is RsStructItem -> appendSemicolon(context, curUseItem)

            is RsFunction -> {
                if (curUseItem != null) {
                    appendSemicolon(context, curUseItem)
                } else {
                    val isMethodCall = context.getElementOfType<RsMethodOrField>() != null
                    if (!context.alreadyHasCallParens) {
                        document.insertString(context.selectionEndOffset, "()")
                    }
                    val caretShift = if (element.valueParameters.isEmpty() && (isMethodCall || !element.hasSelfParameters)) 2 else 1
                    EditorModificationUtil.moveCaretRelatively(context.editor, caretShift)
                    if (!element.valueParameters.isEmpty()) {
                        AutoPopupController.getInstance(element.project)?.autoPopupParameterInfo(context.editor, element)
                    }
                }
            }

            is RsEnumVariant -> {
                if (curUseItem == null) {
                    // Currently this works only for enum variants (and not for structs). It's because in the case of
                    // struct you may want to append an associated function call after a struct name (e.g. `::new()`)
                    val (text, shift) = when {
                        element.tupleFields != null -> Pair("()", 1)
                        element.blockFields != null -> Pair(" {}", 2)
                        else -> Pair("", 0)
                    }
                    if (!(context.alreadyHasStructBraces || context.alreadyHasCallParens)) {
                        document.insertString(context.selectionEndOffset, text)
                    }
                    EditorModificationUtil.moveCaretRelatively(context.editor, shift)
                }
            }

            is RsMacro -> {
                if (curUseItem == null) {
                    if (!context.nextCharIs('!')) {
                        val parens = when (element.name) {
                            "vec" -> "[]"
                            else -> "()"
                        }
                        document.insertString(context.selectionEndOffset, "!$parens")
                    }
                    EditorModificationUtil.moveCaretRelatively(context.editor, 2)
                } else {
                    appendSemicolon(context, curUseItem)
                }
            }
        }
    }
}

private fun appendSemicolon(context: InsertionContext, curUseItem: RsUseItem?) {
    if (curUseItem != null) {
        val hasSemicolon = curUseItem.lastChild!!.elementType == RsElementTypes.SEMICOLON
        if (!(hasSemicolon || context.isInUseGroup)) {
            context.addSuffix(";")
        }
    }
}

inline fun <reified T : PsiElement> InsertionContext.getElementOfType(strict: Boolean = false): T? =
    PsiTreeUtil.findElementOfClassAtOffset(file, tailOffset - 1, T::class.java, strict)

private val InsertionContext.isInUseGroup: Boolean
    get() = getElementOfType<RsUseGroup>() != null

val InsertionContext.alreadyHasCallParens: Boolean
    get() = nextCharIs('(')

private val InsertionContext.alreadyHasStructBraces: Boolean
    get() = nextCharIs('{')

private fun RsFunction.getExtraTailText(subst: Substitution): String {
    val traitRef = stubAncestorStrict<RsImplItem>()?.traitRef ?: return ""
    return " of ${traitRef.getStubOnlyText(subst)}"
}

fun InsertionContext.nextCharIs(c: Char): Boolean =
    document.charsSequence.indexOfSkippingSpace(c, tailOffset) != null

private fun CharSequence.indexOfSkippingSpace(c: Char, startIndex: Int): Int? {
    for (i in startIndex until this.length) {
        val currentChar = this[i]
        if (c == currentChar) return i
        if (currentChar != ' ' && currentChar != '\t') return null
    }
    return null
}

private val RsElement.canBeExported: Boolean
    get() {
        if (this is RsEnumVariant) return true
        val context = PsiTreeUtil.getContextOfType(this, true, RsItemElement::class.java, RsFile::class.java)
        return context == null || context is RsMod
    }

private fun isCompatibleTypes(lookup: ImplLookup, actualTy: Ty?, expectedTy: Ty?): Boolean {
    if (
        actualTy == null || expectedTy == null ||
        actualTy is TyUnknown || expectedTy is TyUnknown ||
        actualTy is TyTypeParameter || expectedTy is TyTypeParameter
    ) return false

    // Replace `TyUnknown` and `TyTypeParameter` with `TyNever` to ignore them when combining types
    val folder = object : TypeFolder {
        override fun foldTy(ty: Ty): Ty = when (ty) {
            is TyUnknown -> TyNever
            is TyTypeParameter -> TyNever
            else -> ty.superFoldWith(this)
        }
    }

    return lookup.ctx.combineTypesNoVars(actualTy.foldWith(folder), expectedTy.foldWith(folder)).isOk
}
