/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.PsiModificationTracker
import org.rust.cargo.project.settings.rustSettings
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.childrenOfType
import org.rust.lang.core.psi.ext.descendantOfTypeStrict

private val NULL_RESULT: CachedValueProvider.Result<MacroExpansion?> =
    CachedValueProvider.Result.create(null, PsiModificationTracker.MODIFICATION_COUNT)

fun expandMacro(call: RsMacroCall): CachedValueProvider.Result<MacroExpansion?> {
    val context = call.context as? RsElement ?: return NULL_RESULT
    @Suppress("WhenWithOnlyElse")
    return when {
//        call.macroName == "lazy_static" -> {
//            val result = expandLazyStatic(call)
//            if (result == null || result.isEmpty()) return NULL_RESULT
//            result.forEach {
//                it.setContext(context)
//                it.setExpandedFrom(call)
//            }
//            val expansion = MacroExpansion.Items(result.first().containingFile as RsFile, result)
//            CachedValueProvider.Result.create(expansion, call.containingFile)
//        }
        else -> {
            val project = context.project
            if (!project.rustSettings.expandMacros) return NULL_RESULT
            val def = call.path.reference.resolve() as? RsMacro ?: return NULL_RESULT
            val expander = MacroExpander(project)
            val result = expander.expandMacro(def, call)
            result?.elements?.forEach {
                it.setContext(context)
                it.setExpandedFrom(call)
            }
            CachedValueProvider.Result.create(result, call.rustStructureOrAnyPsiModificationTracker)
        }
    }
}

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

sealed class MacroExpansion(val file: RsFile, val ranges: RangeMapper) {

    /** The list of expanded elements. Can be empty */
    abstract val elements: List<RsExpandedElement>

    class Expr(file: RsFile, ranges: RangeMapper, val expr: RsExpr) : MacroExpansion(file, ranges) {
        override val elements: List<RsExpandedElement>
            get() = listOf(expr)
    }

    class Pat(file: RsFile, ranges: RangeMapper, val pat: RsPat) : MacroExpansion(file, ranges) {
        override val elements: List<RsExpandedElement>
            get() = listOf(pat)
    }

    class Type(file: RsFile, ranges: RangeMapper, val type: RsTypeReference) : MacroExpansion(file, ranges) {
        override val elements: List<RsExpandedElement>
            get() = listOf(type)
    }

    /** Can contains items, macros and macro calls */
    class Items(file: RsFile, ranges: RangeMapper, override val elements: List<RsExpandedElement>) : MacroExpansion(file, ranges)

    /** Can contains items, statements and a tail expr */
    class Stmts(file: RsFile, ranges: RangeMapper, override val elements: List<RsExpandedElement>) : MacroExpansion(file, ranges)
}

fun parseExpandedTextWithContext(
    context: MacroExpansionContext,
    factory: RsPsiFactory,
    expandedText: CharSequence,
    ranges: RangeMapper
): MacroExpansion? =
    getExpansionFromExpandedFile(context, factory.createFile(prepareExpandedTextForParsing(context, expandedText)), ranges)

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

/** If a call is previously expanded to [expandedFile], this function extract expanded elements from the file */
fun getExpansionFromExpandedFile(context: MacroExpansionContext, expandedFile: RsFile, ranges: RangeMapper): MacroExpansion? {
    return when (context) {
        MacroExpansionContext.EXPR -> {
            val expr = expandedFile.descendantOfTypeStrict<RsExpr>() ?: return null
            MacroExpansion.Expr(expandedFile, ranges, expr)
        }
        MacroExpansionContext.PAT -> {
            val pat = expandedFile.descendantOfTypeStrict<RsPat>() ?: return null
            MacroExpansion.Pat(expandedFile, ranges, pat)
        }
        MacroExpansionContext.TYPE -> {
            val type = expandedFile.descendantOfTypeStrict<RsTypeReference>() ?: return null
            MacroExpansion.Type(expandedFile, ranges, type)
        }
        MacroExpansionContext.STMT -> {
            val block = expandedFile.descendantOfTypeStrict<RsBlock>() ?: return null
            val itemsAndStatements = block.childrenOfType<RsExpandedElement>()
            MacroExpansion.Stmts(expandedFile, ranges, itemsAndStatements)
        }
        MacroExpansionContext.ITEM -> {
            val items = expandedFile.childrenOfType<RsExpandedElement>()
            MacroExpansion.Items(expandedFile, ranges, items)
        }
    }
}
