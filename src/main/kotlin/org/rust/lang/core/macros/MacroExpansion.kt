/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.stubChildrenOfType
import org.rust.lang.core.psi.ext.stubDescendantOfTypeOrStrict

enum class MacroExpansionContext {
    EXPR, PAT, TYPE, STMT, ITEM
}

val RsMacroCall.expansionContext: MacroExpansionContext
    get() = when (val context = context) {
        is RsMacroExpr -> when {
            context.context is RsExprStmt -> MacroExpansionContext.STMT
            else -> MacroExpansionContext.EXPR
        }
        is RsPatMacro -> MacroExpansionContext.PAT
        is RsMacroType -> MacroExpansionContext.TYPE
        else -> MacroExpansionContext.ITEM
    }

sealed class MacroExpansion(val file: RsFile) {

    /** The list of expanded elements. Can be empty */
    abstract val elements: List<RsExpandedElement>

    class Expr(file: RsFile, val expr: RsExpr) : MacroExpansion(file) {
        override val elements: List<RsExpandedElement>
            get() = listOf(expr)
    }

    class Pat(file: RsFile, val pat: RsPat) : MacroExpansion(file) {
        override val elements: List<RsExpandedElement>
            get() = listOf(pat)
    }

    class Type(file: RsFile, val type: RsTypeReference) : MacroExpansion(file) {
        override val elements: List<RsExpandedElement>
            get() = listOf(type)
    }

    /** Can contains items, macros and macro calls */
    class Items(file: RsFile, override val elements: List<RsExpandedElement>) : MacroExpansion(file)

    /** Can contains items, statements and a tail expr */
    class Stmts(file: RsFile, override val elements: List<RsExpandedElement>) : MacroExpansion(file)
}

fun parseExpandedTextWithContext(
    context: MacroExpansionContext,
    factory: RsPsiFactory,
    expandedText: CharSequence
): MacroExpansion? =
    getExpansionFromExpandedFile(context, factory.createFile(prepareExpandedTextForParsing(context, expandedText)))

// Keep in sync with [MacroExpansionContext.additionalOffset]
private fun prepareExpandedTextForParsing(
    context: MacroExpansionContext,
    expandedText: CharSequence
): CharSequence = when (context) {
    MacroExpansionContext.EXPR -> "fn f() { $expandedText; }"
    MacroExpansionContext.PAT -> "fn f($expandedText: ()) {}"
    MacroExpansionContext.TYPE -> "fn f(_: $expandedText) {}"
    MacroExpansionContext.STMT -> "fn f() { $expandedText }"
    MacroExpansionContext.ITEM -> expandedText
}

val MacroExpansionContext.expansionFileStartOffset: Int
    get() = when (this) {
        MacroExpansionContext.EXPR -> 9
        MacroExpansionContext.PAT -> 5
        MacroExpansionContext.TYPE -> 8
        MacroExpansionContext.STMT -> 9
        MacroExpansionContext.ITEM -> 0
    }

/** If a call is previously expanded to [expandedFile], this function extract expanded elements from the file */
fun getExpansionFromExpandedFile(context: MacroExpansionContext, expandedFile: RsFile): MacroExpansion? {
    return when (context) {
        MacroExpansionContext.EXPR -> {
            val expr = expandedFile.stubDescendantOfTypeOrStrict<RsExpr>() ?: return null
            MacroExpansion.Expr(expandedFile, expr)
        }
        MacroExpansionContext.PAT -> {
            val pat = expandedFile.stubDescendantOfTypeOrStrict<RsPat>() ?: return null
            MacroExpansion.Pat(expandedFile, pat)
        }
        MacroExpansionContext.TYPE -> {
            val type = expandedFile.stubDescendantOfTypeOrStrict<RsTypeReference>() ?: return null
            MacroExpansion.Type(expandedFile, type)
        }
        MacroExpansionContext.STMT -> {
            val block = expandedFile.stubDescendantOfTypeOrStrict<RsBlock>() ?: return null
            val itemsAndStatements = block.stubChildrenOfType<RsExpandedElement>()
            MacroExpansion.Stmts(expandedFile, itemsAndStatements)
        }
        MacroExpansionContext.ITEM -> {
            val items = expandedFile.stubChildrenOfType<RsExpandedElement>()
            MacroExpansion.Items(expandedFile, items)
        }
    }
}

fun MacroExpander.expandMacro(
    def: RsMacro,
    call: RsMacroCall,
    factory: RsPsiFactory,
    storeRangeMap: Boolean
): MacroExpansion? {
    val (expandedText, ranges) = expandMacroAsText(def, call) ?: return null
    return parseExpandedTextWithContext(call.expansionContext, factory, expandedText)?.also {
        if (storeRangeMap) it.file.putUserData(MACRO_RANGE_MAP_KEY, ranges)
    }
}

private val MACRO_RANGE_MAP_KEY: Key<CompactRangeMap> = Key.create("MACRO_RANGE_MAP_KEY")

private val RsFile.compactRanges: Pair<CompactRangeMap?, ModificationTracker>
    get() {
        val virtualFile = virtualFile
        return if (virtualFile is VirtualFileWithId) {
            virtualFile.loadRangeMapWithModTracker()
        } else {
            // NEVER_CHANGED b/c light vfile will be replaced fully along with all caches after macro change
            getUserData(MACRO_RANGE_MAP_KEY) to ModificationTracker.NEVER_CHANGED
        }
    }

val MacroExpansion.ranges: RangeMap
    get() {
        val file = file
        return CachedValuesManager.getCachedValue(file) {
            val (compactRanges, modTracker) = file.compactRanges
            val ranges = compactRanges?.unpack() ?: RangeMap.EMPTY
            CachedValueProvider.Result(ranges, modTracker)
        }
    }
