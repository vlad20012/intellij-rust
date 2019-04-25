/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.SmartList
import org.rust.lang.core.psi.RsMacroArgument
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.ext.ancestorStrict
import org.rust.lang.core.psi.ext.expansion
import org.rust.lang.core.psi.ext.startOffset
import org.rust.stdext.optimizeList
import org.rust.stdext.readVarInt
import org.rust.stdext.writeVarInt
import java.io.DataInputStream
import java.io.DataOutputStream

// TODO optimize it using sorted array and binary search
class RangeMap private constructor(private val ranges: List<Pair<TextRange, TextRange>>) {

    fun mapOffsetFromExpansionToCallBody(offset: Int): Int? {
        return ranges.singleOrNull { (_, s) ->
            offset >= s.startOffset && offset < s.endOffset
        }?.let { (d, s) ->
            d.startOffset + (offset - s.startOffset)
        }
    }

    fun mapOffsetFromCallBodyToExpansion(offset: Int): List<Int> {
        return ranges.filter { (s, _) ->
            offset >= s.startOffset && offset < s.endOffset
        }.map { (s, d) ->
            d.startOffset + (offset - s.startOffset)
        }
    }

    companion object {
        val EMPTY: RangeMap = CompactRangeMap.EMPTY.unpack()

        fun fromRanges(ranges: List<MappedTextRange>): RangeMap {
            val textRanges = ranges.map {
                TextRange(it.srcOffset, it.srcOffset + it.length) to
                    TextRange(it.offsetInExpansion, it.offsetInExpansion + it.length)
            }
            return RangeMap(textRanges)
        }
    }
}

/**
 * Compact representation of [RangeMap] used for serialization and to save memory.
 *
 * Must provide [equals] method because it's used to track changes in macro expansion mechanism
 */
@Suppress("DataClassPrivateConstructor")
data class CompactRangeMap private constructor(private val ranges: List<MappedTextRange>) {

    fun unpack(): RangeMap =
        RangeMap.fromRanges(ranges)

    fun writeTo(data: DataOutputStream) {
        data.writeInt(ranges.size)
        ranges.forEach {
            data.writeMappedTextRange(it)
        }
    }

    companion object {
        val EMPTY: CompactRangeMap = CompactRangeMap(emptyList())

        fun readFrom(data: DataInputStream): CompactRangeMap {
            val size = data.readInt()
            val ranges = (0 until size).map { data.readMappedTextRange() }
            return CompactRangeMap(ranges)
        }

        fun from(ranges: SmartList<MappedTextRange>): CompactRangeMap {
            return CompactRangeMap(ranges.optimizeList())
        }
    }
}

private fun DataInputStream.readMappedTextRange(): MappedTextRange = MappedTextRange(
    readVarInt(),
    readVarInt(),
    readVarInt()
)

private fun DataOutputStream.writeMappedTextRange(range: MappedTextRange) {
    writeVarInt(range.srcOffset)
    writeVarInt(range.offsetInExpansion)
    writeVarInt(range.length)
}

data class MappedTextRange(
    val srcOffset: Int,
    val offsetInExpansion: Int,
    val length: Int
) {
    init {
        check(srcOffset >= 0) { "srcOffset should be >= 0; got: $srcOffset" }
        check(offsetInExpansion >= 0) { "offsetInExpansion should be >= 0; got: $offsetInExpansion" }
        check(length > 0) { "length should be grater than 0; got: $length" }
    }
}

/**
 * If receiver element is inside a macro expansion, returns the leaf element inside the macro call
 * from which the first token of this element is expanded. Always returns an element inside a root
 * macro call, i.e. outside of any expansion.
 */
fun PsiElement.findElementExpandedFrom(): PsiElement? {
    val mappedElement = findElementExpandedFromNonRecursive() ?: return null
    return mappedElement.findElementExpandedFrom() ?: mappedElement.takeIf { !it.isExpandedFromMacro }
}

private fun PsiElement.findElementExpandedFromNonRecursive(): PsiElement? {
    val call = findMacroCallExpandedFromNonRecursive() ?: return null
    val mappedOffset = mapOffsetFromExpansionToCallBody(call, startOffset) ?: return null
    return call.containingFile.findElementAt(mappedOffset)
        ?.takeIf { it.startOffset == mappedOffset }
}

private fun mapOffsetFromExpansionToCallBody(call: RsMacroCall, offset: Int): Int? {
    val expansion = call.expansion ?: return null
    val fileOffset = call.expansionContext.expansionFileStartOffset
    return expansion.ranges.mapOffsetFromExpansionToCallBody(offset - fileOffset)
        ?.fromBodyRelativeOffset(call)
}

fun PsiElement.findExpansionElements(): List<PsiElement>? {
    val mappedElements = findExpansionElementsNonRecursive() ?: return null
    return mappedElements.flatMap { mappedElement ->
        mappedElement.findExpansionElements() ?: listOf(mappedElement)
    }
}

private fun PsiElement.findExpansionElementsNonRecursive(): List<PsiElement>? {
    val call = ancestorStrict<RsMacroArgument>()?.ancestorStrict<RsMacroCall>() ?: return null
    val expansion = call.expansion ?: return null
    val mappedOffsets = mapOffsetFromCallBodyToExpansion(call, expansion, startOffset) ?: return null
    val expansionFile = expansion.file
    return mappedOffsets.mapNotNull { mappedOffset ->
        expansionFile.findElementAt(mappedOffset)
            ?.takeIf { it.startOffset == mappedOffset }
    }
}

private fun mapOffsetFromCallBodyToExpansion(
    call: RsMacroCall,
    expansion: MacroExpansion,
    absOffsetInCallBody: Int
): List<Int>? {
    val rangeInCallBody = absOffsetInCallBody.toBodyRelativeOffset(call) ?: return null
    val fileOffset = call.expansionContext.expansionFileStartOffset
    return expansion.ranges.mapOffsetFromCallBodyToExpansion(rangeInCallBody)
        .map { it + fileOffset }
        .takeIf { it.isNotEmpty() }
}

private fun Int.toBodyRelativeOffset(call: RsMacroCall): Int? {
    val macroOffset = call.macroArgument?.compactTT?.startOffset ?: return null
    val elementOffset = this - macroOffset
    check(elementOffset >= 0)
    return elementOffset
}

private fun Int.fromBodyRelativeOffset(call: RsMacroCall): Int? {
    val macroRange = call.macroArgument?.compactTT?.textRange ?: return null
    val elementOffset = this + macroRange.startOffset
    check(elementOffset <= macroRange.endOffset)
    return elementOffset
}
