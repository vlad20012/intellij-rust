/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi.ext

import com.intellij.openapi.util.text.StringUtil
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.resolve.TYPES
import org.rust.lang.core.resolve.processNestedScopesUpwards

/**
 * Note: don't forget to add an element type to [org.rust.lang.core.psi.RS_ITEMS]
 * when implementing [RsItemElement]
 */
interface RsItemElement : RsVisibilityOwner, RsOuterAttributeOwner, RsExpandedElement

fun <T : RsItemElement> Iterable<T>.filterInScope(scope: RsElement): List<T> {
    val set = toMutableSet()
    processNestedScopesUpwards(scope, TYPES) {
        set.remove(it.element)
        set.isEmpty()
    }
    return if (set.isEmpty()) toList() else toMutableList().apply { removeAll(set) }
}

val RsItemElement.itemKindName: String
    get() = when (this) {
        is RsMod, is RsModDeclItem -> "module"
        is RsFunction -> "function"
        is RsConstant -> when (kind) {
            RsConstantKind.STATIC -> "static"
            RsConstantKind.MUT_STATIC -> "static"
            RsConstantKind.CONST -> "constant"
        }
        is RsStructItem -> when (kind) {
            RsStructKind.STRUCT -> "struct"
            RsStructKind.UNION -> "union"
        }
        is RsEnumItem -> "enum"
        is RsTraitItem -> "trait"
        is RsTraitAlias -> "trait alias"
        is RsTypeAlias -> "type alias"
        is RsImplItem -> "impl"
        is RsUseItem -> "use item"
        is RsForeignModItem -> "foreign module"
        is RsExternCrateItem -> "extern crate"
        is RsMacro2 -> "macro"
        else -> "item"
    }

val RsItemElement.itemKindNameCapitalized: String
    get() = StringUtil.capitalize(itemKindName)
