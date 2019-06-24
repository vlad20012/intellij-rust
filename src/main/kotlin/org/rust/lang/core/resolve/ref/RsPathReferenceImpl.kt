/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve.ref

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.Substitution
import org.rust.lang.core.types.infer.foldTyInferWith
import org.rust.lang.core.types.infer.resolve
import org.rust.lang.core.types.infer.substitute
import org.rust.lang.core.types.inference
import org.rust.lang.core.types.regions.ReEarlyBound
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.type
import org.rust.stdext.buildMap
import org.rust.stdext.intersects

class RsPathReferenceImpl(
    element: RsPath
) : RsReferenceBase<RsPath>(element),
    RsPathReference {

    override val RsPath.referenceAnchor: PsiElement get() = referenceNameElement

    override fun isReferenceTo(element: PsiElement): Boolean {
        if (element is RsFieldDecl) return false

        val path = this.element
        if (element is RsNamedElement && !path.allowedNamespaces().intersects(element.namespaces)) return false

        val isMember = element is RsAbstractable && element.owner.isImplOrTrait
        if (isMember && (path.parent is RsUseSpeck || path.path == null && path.typeQual == null)) {
            return false
        }
        val target = resolve()
        return element.manager.areElementsEquivalent(target, element)
    }

    override fun advancedResolve(): BoundElement<RsElement>? =
        advancedMultiResolve().singleOrNull()

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        return advancedMultiResolve().toTypedArray()
    }

    override fun multiResolve(): List<RsNamedElement> =
        advancedMultiResolve().mapNotNull { it.element as? RsNamedElement }

    private fun advancedMultiResolve(): List<BoundElement<RsElement>> =
        (element.parent as? RsPathExpr)?.let { it.inference?.getResolvedPaths(it)?.map { BoundElement(it) } }
            ?: advancedCachedMultiResolve()

    private fun advancedCachedMultiResolve(): List<BoundElement<RsElement>> {
        return RsResolveCache.getInstance(element.project)
            .resolveWithCaching(element, ResolveCacheDependency.LOCAL_AND_RUST_STRUCTURE, Resolver)
            .orEmpty()
            // We can store a fresh `TyInfer.TyVar` to the cache for `_` path parameter (like `Vec<_>`), but
            // TyVar is mutable type, so we must copy it after retrieving from the cache
            .map { it.foldTyInferWith { if (it is TyInfer.TyVar) TyInfer.TyVar(it.origin) else it } }
    }

    private object Resolver : (RsPath) -> List<BoundElement<RsElement>> {
        override fun invoke(element: RsPath): List<BoundElement<RsElement>> {
            return resolvePath(element)
        }
    }
}

fun resolvePathRaw(path: RsPath, lookup: ImplLookup): List<ScopeEntry> {
    return collectResolveVariantsAsScopeEntries(path.referenceName) {
        processPathResolveVariants(lookup, path, false, it)
    }
}

fun resolvePath(path: RsPath, lookup: ImplLookup = ImplLookup.relativeTo(path)): List<BoundElement<RsElement>> {
    val result = collectPathResolveVariants(path.referenceName) {
        processPathResolveVariants(lookup, path, false, it)
    }

    return when (result.size) {
        0 -> emptyList()
        1 -> listOf(instantiatePathGenerics(path, result.single(), lookup))
        else -> result
    }
}

fun <T: RsElement> instantiatePathGenerics(
    path: RsPath,
    resolved: BoundElement<T>,
    lookup: ImplLookup
): BoundElement<T> {
    val (element, subst) = resolved.downcast<RsGenericDeclaration>() ?: return resolved
    val typeArguments: List<Ty>? = run {
        val inAngles = path.typeArgumentList
        val fnSugar = path.valueParameterList
        when {
            inAngles != null -> inAngles.typeReferenceList.map { it.type }
            fnSugar != null -> listOf(
                TyTuple(fnSugar.valueParameterList.map { it.typeReference?.type ?: TyUnknown })
            )
            else -> null
        }
    }
    val regionArguments: List<Region>? = path.typeArgumentList?.lifetimeList?.map { it.resolve() }
    val outputArg = path.retType?.typeReference?.type

    val assocTypes = run {
        if (element is RsTraitItem) {
            buildMap {
                // Iterator<Item=T>
                path.typeArgumentList?.assocTypeBindingList?.forEach { binding ->
                    // We can't just use `binding.reference.resolve()` here because
                    // resolving of an assoc type depends on a parent path resolve,
                    // so we coming back here and entering the infinite recursion
                    resolveAssocTypeBinding(element, binding)?.let { assoc ->
                        binding.typeReference?.type?.let { put(assoc, it) }
                    }

                }

                // Fn() -> T
                val outputParam = lookup.fnOnceOutput
                if (outputArg != null && outputParam != null) {
                    put(outputParam, outputArg)
                }
            }
        } else {
            emptyMap<RsTypeAlias, Ty>()
        }
    }

    val parent = path.parent
    // Generic arguments are optional in expression context, e.g.
    // `let a = Foo::<u8>::bar::<u16>();` can be written as `let a = Foo::bar();`
    // if it is possible to infer `u8` and `u16` during type inference
    val areOptionalArgs = parent is RsExpr || parent is RsPath && parent.parent is RsExpr
    val typeSubst = element.typeParameters.withIndex().associate { (i, param) ->
        val paramTy = TyTypeParameter.named(param)
        val value = typeArguments?.getOrNull(i) ?: if (!areOptionalArgs) {
            // Args aren't optional and aren't present, so use either default argument
            // from a definition `struct S<T=u8>(T);` or falling back to `TyUnknown`
            param.typeReference?.type ?: TyUnknown
        } else {
            paramTy
        }
        paramTy to value
    }
    val regionParameters = element.lifetimeParameters.map { ReEarlyBound(it) }
    val regionSubst = regionParameters.zip(regionArguments ?: regionParameters).toMap()
    val newSubst = Substitution(typeSubst, regionSubst)
    return BoundElement(resolved.element, subst + newSubst, assocTypes)
}

private fun resolveAssocTypeBinding(trait: RsTraitItem, binding: RsAssocTypeBinding): RsTypeAlias? =
    collectResolveVariants(binding.referenceName) { processAssocTypeVariants(trait, it) }
        .singleOrNull() as? RsTypeAlias?

/** Resolves a reference through type aliases */
fun RsPathReference.deepResolve(): RsElement? =
    advancedDeepResolve()?.element

/** Resolves a reference through type aliases */
fun RsPathReference.advancedDeepResolve(): BoundElement<RsElement>? {
    val boundElement = advancedResolve()?.let { resolved ->
        // Resolve potential `Self` inside `impl`
        if (resolved.element is RsImplItem && element.hasCself) {
            (resolved.element.typeReference?.typeElement as? RsBaseType)?.path?.reference?.advancedResolve() ?: resolved
        } else {
            resolved
        }
    }

    // Resolve potential type aliases
    return if (boundElement != null && boundElement.element is RsTypeAlias) {
        resolveThroughTypeAliases(boundElement)
    } else {
        boundElement
    }
}

private fun resolveThroughTypeAliases(boundElement: BoundElement<RsElement>): BoundElement<RsElement>? {
    var base: BoundElement<RsElement> = boundElement
    val visited = mutableSetOf(boundElement.element)
    while (base.element is RsTypeAlias) {
        val resolved = ((base.element as RsTypeAlias).typeReference?.typeElement as? RsBaseType)
            ?.path?.reference?.advancedResolve()
            ?: break
        if (!visited.add(resolved.element)) return null
        // Stop at `type S<T> = T;`
        if (resolved.element is RsTypeParameter) break
        base = resolved.substitute(base.subst)
    }
    return base
}
