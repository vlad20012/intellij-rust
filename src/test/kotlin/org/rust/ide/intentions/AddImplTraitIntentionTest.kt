/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

import org.intellij.lang.annotations.Language

class AddImplTraitIntentionTest : RsIntentionTestBase(AddImplTraitIntention::class) {
    fun `test empty trait`() = doTest("""
        trait Trait {}

        struct S/*caret*/;
    """, """
        trait Trait {}

        struct S;

        impl Trait/*caret*/ for S {}
    """, "Trait")

    private fun doTest(@Language("Rust") before: String, @Language("Rust") after: String, traitName: String) {
        val name = AddImplTraitIntention.traitName
        try {
            AddImplTraitIntention.traitName = traitName
            doAvailableTest(before, after)
        }
        finally {
            AddImplTraitIntention.traitName = name
        }
    }
}
// TODO: test trait with default implementation
// TODO: fixup trait tests
