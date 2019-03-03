/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.util.TextRange

/*inline*/ class RangeMapper(private val ranges: List<Pair<TextRange, TextRange>>) {
    fun map(src: TextRange): TextRange? {
        val mapped = ranges.find { (s) ->
            src.startOffset >= s.startOffset && src.endOffset <= s.endOffset
        }?.second
        check(mapped == null || mapped.length == src.length)
        return mapped
    }
}
