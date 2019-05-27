/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve

import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.SmartList
import org.rust.cargo.util.AutoInjectedCrates.CORE
import org.rust.cargo.util.AutoInjectedCrates.STD
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.ref.RsReference
import org.rust.openapiext.Testmark
import org.rust.openapiext.recursionGuard
import org.rust.stdext.intersects
import java.util.*

fun processItemOrEnumVariantDeclarations(
    scope: RsElement,
    ns: Set<Namespace>,
    processor: RsResolveProcessor,
    withPrivateImports: Boolean = false
): Boolean {
    when (scope) {
        is RsEnumItem -> {
            if (processAll(scope.variants, processor)) return true
        }
        is RsMod -> {
            val ipm = if (withPrivateImports) {
                ItemProcessingMode.WITH_PRIVATE_IMPORTS
            } else {
                ItemProcessingMode.WITHOUT_PRIVATE_IMPORTS
            }
            if (processItemDeclarations(scope, ns, processor, ipm)) return true
        }
    }

    return false
}

fun processItemDeclarations(
    scope: RsItemsOwner,
    ns: Set<Namespace>,
    originalProcessor: RsResolveProcessor,
    ipm: ItemProcessingMode
): Boolean {
    val withPrivateImports = ipm != ItemProcessingMode.WITHOUT_PRIVATE_IMPORTS

    val starImports = mutableListOf<RsUseSpeck>()
    val itemImports = mutableListOf<RsUseSpeck>()

    val directlyDeclaredNames = HashSet<String>()
    val processor = { e: ScopeEntry ->
        directlyDeclaredNames += e.name
        originalProcessor(e)
    }

    loop@ for (item in scope.expandedItemsExceptImpls) {
        when (item) {
            is RsUseItem ->
                if (item.isPublic || withPrivateImports) {
                    val rootSpeck = item.useSpeck ?: continue@loop
                    rootSpeck.forEachLeafSpeck { speck ->
                        (if (speck.isStarImport) starImports else itemImports) += speck
                    }
                }

            // Unit like structs are both types and values
            is RsStructItem -> {
                if (item.namespaces.intersects(ns) && processor(item)) return true
            }

            is RsModDeclItem -> if (Namespace.Types in ns) {
                val name = item.name ?: continue@loop
                val mod = item.reference.resolve() ?: continue@loop
                if (processor(name, mod)) return true
            }

            is RsEnumItem, is RsModItem, is RsTraitItem, is RsTypeAlias ->
                if (Namespace.Types in ns && processor(item as RsNamedElement)) return true

            is RsFunction, is RsConstant ->
                if (Namespace.Values in ns && processor(item as RsNamedElement)) return true

            is RsForeignModItem -> if (Namespace.Values in ns) {
                if (processAll(item.functionList, processor) || processAll(item.constantList, processor)) return true
            }

            is RsExternCrateItem -> {
                if (processExternCrateItem(item, processor, withPrivateImports)) return true
            }
        }
    }

    val isEdition2018 = scope.isEdition2018
    for (speck in itemImports) {
        check(speck.useGroup == null)
        val path = speck.path ?: continue
        val name = speck.nameInScope ?: continue

        if (isEdition2018 && speck.alias == null && path.isAtom) {
            // Use items like `use foo;` or `use foo::{self}` are meaningless on 2018 edition.
            // We should ignore it or it breaks resolve of such `foo` in other places.
            ItemResolutionTestmarks.extraAtomUse.hit()
            continue
        }
        if (processMultiResolveWithNs(name, ns, path.reference, processor)) return true
    }

    if (withPrivateImports && Namespace.Types in ns && scope is RsFile && !isEdition2018 && scope.isCrateRoot) {
        // Rust injects implicit `extern crate std` in every crate root module unless it is
        // a `#![no_std]` crate, in which case `extern crate core` is injected. However, if
        // there is a (unstable?) `#![no_core]` attribute, nothing is injected.
        //
        // https://doc.rust-lang.org/book/using-rust-without-the-standard-library.html
        // The stdlib lib itself is `#![no_std]`, and the core is `#![no_core]`
        when (scope.attributes) {
            RsFile.Attributes.NONE ->
                if (processor.lazy(STD) { scope.findDependencyCrateRoot(STD) }) return true

            RsFile.Attributes.NO_STD ->
                if (processor.lazy(CORE) { scope.findDependencyCrateRoot(CORE) }) return true

            RsFile.Attributes.NO_CORE -> Unit
        }
    }

    if (originalProcessor(ScopeEvent.STAR_IMPORTS)) {
        return false
    }

    if (ipm != ItemProcessingMode.WITH_PRIVATE_IMPORTS && Namespace.Types in ns && scope is RsMod) {
        if (scope.isEdition2018 && !scope.isCrateRoot) {
            val crateRoot = scope.crateRoot
            if (crateRoot != null) {
                val result = processWithShadowing(directlyDeclaredNames, processor) { shadowingProcessor ->
                    crateRoot.processExpandedItemsExceptImpls { item ->
                        if (item is RsExternCrateItem) {
                            processExternCrateItem(item, shadowingProcessor, true)
                        } else {
                            false
                        }
                    }
                }
                if (result) return true
            }
        }

        // "extern_prelude" feature. Extern crate names can be resolved as if they were in the prelude.
        // See https://blog.rust-lang.org/2018/10/25/Rust-1.30.0.html#module-system-improvements
        // See https://github.com/rust-lang/rust/pull/54404/
        val result = processWithShadowing(directlyDeclaredNames, processor) { shadowingProcessor ->
            val isCompletion = ipm == ItemProcessingMode.WITH_PRIVATE_IMPORTS_N_EXTERN_CRATES_COMPLETION
            processExternCrateResolveVariants(scope, isCompletion, shadowingProcessor)
        }
        if (result) return true
    }

    for (speck in starImports) {
        val path = speck.path
        val basePath = if (path == null && speck.context is RsUseGroup) {
            // `use foo::bar::{self, *}`
            //           ~~~
            speck.qualifier
        } else {
            // `use foo::bar::*` or `use foo::{self, bar::*}`
            //           ~~~                         ~~~
            path
        }
        val mod = (if (basePath != null) basePath.reference.resolve() else speck.crateRoot)
            ?: continue

        val found = recursionGuard(mod, Computable {
            processItemOrEnumVariantDeclarations(mod, ns,
                { it.name !in directlyDeclaredNames && originalProcessor(it) },
                withPrivateImports = basePath != null && isSuperChain(basePath)
            )
        })
        if (found == true) return true
    }

    return false
}

fun processExternCrateItem(item: RsExternCrateItem, processor: RsResolveProcessor, withPrivateImports: Boolean): Boolean {
    if (item.isPublic || withPrivateImports) {
        val mod = item.reference.resolve() ?: return false
        val nameWithAlias = item.nameWithAlias
        if (nameWithAlias != "self") {
            if (processor(nameWithAlias, mod)) return true
        } else {
            ItemResolutionTestmarks.externCrateSelfWithoutAlias.hit()
        }
    }
    return false
}

private fun processMultiResolveWithNs(name: String, ns: Set<Namespace>, ref: RsReference, processor: RsResolveProcessor): Boolean {
    // XXX: use items can legitimately resolve in both namespaces.
    // Because we must be lazy, we don't know up front how many times we
    // need to call the `processor`, so we need to calculate this lazily
    // if the processor scrutinizes at least the first element.

    // XXX: there are two `cfg`ed `boxed` modules in liballoc, so
    // we apply "first in the namespace wins" heuristic.

    if (ns.size == 1) {
        // Optimized version for single namespace.
        // Also this provides ability to cache ScopeEntries and so necessary for [processItemDeclarationsWithCache]
        return processor.lazy(name) {
            ref.multiResolve().find { it is RsNamedElement && ns.intersects(it.namespaces) }
        }
    }

    var variants: List<RsNamedElement> = emptyList()
    val visitedNamespaces = EnumSet.noneOf(Namespace::class.java)
    if (processor.lazy(name) {
        variants = ref.multiResolve()
            .filterIsInstance<RsNamedElement>()
            .filter { ns.intersects(it.namespaces) }
        val first = variants.firstOrNull()
        if (first != null) {
            visitedNamespaces.addAll(first.namespaces)
        }
        first
    }) {
        return true
    }
    // `variants` will be populated if processor looked at the corresponding element
    for (element in variants.drop(1)) {
        if (element.namespaces.all { it in visitedNamespaces }) continue
        visitedNamespaces.addAll(element.namespaces)
        if (processor(name, element)) return true
    }
    return false
}

/**
 * A cached version of [processItemDeclarations]. Exists only for optimization purposes and can be safely
 * replaced with [processItemDeclarations]. The cached version is used only when [ns] consists of the
 * single element that is [Namespace.Types]. This is due to the following reasons:
 * 1. Types namespace is an absolute record holder in name resolution invocations and time
 * 2. We can cache only single namespace due to [processMultiResolveWithNs] implementation
 */
fun processItemDeclarationsWithCache(
    scope: RsMod,
    ns: Set<Namespace>,
    processor: RsResolveProcessor,
    ipm: ItemProcessingMode = ItemProcessingMode.WITH_PRIVATE_IMPORTS
): Boolean {
    return if (ns == TYPES) {
        val key = when (ipm) {
            ItemProcessingMode.WITHOUT_PRIVATE_IMPORTS -> CACHED_ITEM_DECLS
            ItemProcessingMode.WITH_PRIVATE_IMPORTS -> CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS
            ItemProcessingMode.WITH_PRIVATE_IMPORTS_N_EXTERN_CRATES -> CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS_EC
            ItemProcessingMode.WITH_PRIVATE_IMPORTS_N_EXTERN_CRATES_COMPLETION -> CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS_ECC
        }
        val cached = CachedValuesManager.getCachedValue(scope, key) {
            val scopeEntryList = SmartList<ScopeEntry>()
            processItemDeclarations(scope, TYPES, {
                scopeEntryList.add(it)
                false
            }, ipm)
            CachedValueProvider.Result.create(scopeEntryList, scope.rustStructureOrAnyPsiModificationTracker)
        }
        for (e in cached) {
            if (processor(e)) return true
        }
        false
    } else {
        processItemDeclarations(scope, ns, processor, ipm)
    }
}

private val CACHED_ITEM_DECLS: Key<CachedValue<List<ScopeEntry>>> =
    Key.create("CACHED_ITEM_DECLS")
private val CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS: Key<CachedValue<List<ScopeEntry>>> =
    Key.create("CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS")
private val CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS_EC: Key<CachedValue<List<ScopeEntry>>> =
    Key.create("CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS_EC")
private val CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS_ECC: Key<CachedValue<List<ScopeEntry>>> =
    Key.create("CACHED_ITEM_DECLS_WITH_PRIVATE_IMPORTS_ECC")

private val RsPath.isAtom: Boolean
    get() = when (kind) {
        PathKind.IDENTIFIER -> qualifier == null
        PathKind.SELF -> qualifier?.isAtom == true
        else -> false
    }

enum class ItemProcessingMode {
    WITHOUT_PRIVATE_IMPORTS,
    WITH_PRIVATE_IMPORTS,
    WITH_PRIVATE_IMPORTS_N_EXTERN_CRATES,
    WITH_PRIVATE_IMPORTS_N_EXTERN_CRATES_COMPLETION
}

object ItemResolutionTestmarks {
    val externCrateSelfWithoutAlias = Testmark("externCrateSelfWithoutAlias")
    val extraAtomUse = Testmark("extraAtomUse")
}
