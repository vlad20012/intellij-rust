/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.util.TextRange

/*inline*/ class RangeMapper(private val ranges: List<Pair<TextRange, TextRange>>) {
    fun map(src: TextRange): List<TextRange> {
        return ranges.filter { (s) ->
            src.startOffset >= s.startOffset && src.endOffset <= s.endOffset
        }.map { (s, d) ->
            val start = d.startOffset + (src.startOffset - s.startOffset)
            TextRange(start, start + src.length)
        }
    }
}
