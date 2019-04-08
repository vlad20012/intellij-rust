/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import org.intellij.lang.annotations.Language
import org.rust.ExpandMacros

// More tests are located in `RsHighlightingAnnotatorTest` (most of those tests are executed
// in both plain and macro context)
@ExpandMacros
class RsMacroExpansionHighlightingPassTest : RsAnnotationTestBase() {

    fun `test attributes inside macro call`() = checkHighlighting("""
        <ATTRIBUTE>#</ATTRIBUTE><ATTRIBUTE>[cfg_attr(foo)]</ATTRIBUTE>
        fn <FUNCTION>main</FUNCTION>() {
            <ATTRIBUTE>#![crate_type = <STRING>"lib"</STRING>]</ATTRIBUTE>
        }
    """)

    protected fun checkHighlightingInsideMacro(@Language("Rust") text: String) {
        super.checkHighlighting("""
            macro_rules! as_is {
                ($($ t:tt)*) => {$($ t)*};
            }
            as_is! {
                $text
            }
        """)
    }

    override fun createAnnotationFixture(): RsAnnotationTestFixture =
        RsAnnotationTestFixture(myFixture)
}
