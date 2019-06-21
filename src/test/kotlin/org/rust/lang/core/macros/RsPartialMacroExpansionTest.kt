/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

class RsPartialMacroExpansionTest : RsMacroExpansionTestBase() {
    fun `test missing tail literal`() = doTest("""
        macro_rules! foo {
            ($ i:ident, spam) => (
                fn $ i() {}
            )
        }
        foo! { bar }
    """, """
        fn bar() {}
    """)

    fun `test not matched if multiple choices`() = doTest("""
        macro_rules! foo {
            ($ i:ident, spam) => (
                fn $ i() {}
            )
            ($ i:ident, eggs) => (
                fn $ i() {}
            )
        }
        foo! { bar }
    """, """
        foo! { bar }
    """)

    fun `test missing tail fragment`() = doTest("""
        macro_rules! foo {
            ($ i:ident, $ j: ident) => (
                fn $ i() { $ j; }
            )
        }
        foo! { bar }
    """, """
        fn bar() { IntellijRustPlaceholder; }
    """)

    fun `test incomplete expr`() = doTest("""
        macro_rules! foo {
            ($ e:expr, spam) => (
                fn foo() { $ e }
            )
        }
        foo! { 2 + }
    """, """
        fn foo() { (2+ ) }
    """)
}
