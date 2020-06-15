/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.ide.todo.TodoConfiguration
import org.intellij.lang.annotations.Language
import org.rust.*
import org.rust.cargo.project.workspace.CargoWorkspace.Edition
import org.rust.ide.colors.RsColor

@ExpandMacros
class RsHighlightingAnnotatorTest : RsAnnotatorTestBase(RsHighlightingAnnotator::class) {

    override fun setUp() {
        super.setUp()
        annotationFixture.registerSeverities(RsColor.values().map(RsColor::testSeverity))
    }

    fun `test attributes`() = checkHighlighting("""
        <ATTRIBUTE>#[cfg_attr(foo)]</ATTRIBUTE>
        <ATTRIBUTE>#[foo(<STRING>"bar"</STRING>)]</ATTRIBUTE>
        fn <FUNCTION>main</FUNCTION>() {
            <ATTRIBUTE>#![crate_type = <STRING>"lib"</STRING>]</ATTRIBUTE>
        }
    """)

    fun `test fields`() = checkHighlightingWithMacro("""
        struct <STRUCT>T</STRUCT>(<PRIMITIVE_TYPE>i32</PRIMITIVE_TYPE>);
        struct <STRUCT>S</STRUCT>{ <FIELD>field</FIELD>: <STRUCT>T</STRUCT>}
        fn <FUNCTION>main</FUNCTION>() {
            let s = <STRUCT>S</STRUCT>{ <FIELD>field</FIELD>: <STRUCT>T</STRUCT>(92) };
            s.<FIELD>field</FIELD>.0;
        }
    """)

    fun `test functions`() = checkHighlightingWithMacro("""
        fn <FUNCTION>main</FUNCTION>() {}
        struct <STRUCT>S</STRUCT>;
        impl <STRUCT>S</STRUCT> {
            fn <METHOD>foo</METHOD>(&self) {}
            fn <ASSOC_FUNCTION>bar</ASSOC_FUNCTION>() {}
        }
        trait <TRAIT>T</TRAIT> {
            fn <METHOD>foo</METHOD>(&self);
            fn <ASSOC_FUNCTION>bar</ASSOC_FUNCTION>() {}
        }
        impl <TRAIT>T</TRAIT> for <STRUCT>S</STRUCT> {
            fn <METHOD>foo</METHOD>(&self) {}
            fn <ASSOC_FUNCTION>bar</ASSOC_FUNCTION>() {}
        }
    """)

    fun `test function and method calls`() = checkHighlightingWithMacro("""
        fn <FUNCTION>function</FUNCTION>() {}
        struct Foo;
        impl Foo {
            fn <METHOD>method</METHOD>(&self) {}
            fn <ASSOC_FUNCTION>assoc_function</ASSOC_FUNCTION>() {}
        }
        fn <FUNCTION>check</FUNCTION><T: FnOnce(i32)>(<PARAMETER>f</PARAMETER>: T) {
            <FUNCTION_CALL>function</FUNCTION_CALL>();
            (<FUNCTION_CALL>function</FUNCTION_CALL>)();
            <STRUCT>Foo</STRUCT>.<METHOD_CALL>method</METHOD_CALL>();
            <STRUCT>Foo</STRUCT>::<METHOD_CALL>method</METHOD_CALL>(&<STRUCT>Foo</STRUCT>);
            <STRUCT>Foo</STRUCT>::<ASSOC_FUNCTION_CALL>assoc_function</ASSOC_FUNCTION_CALL>();
            <PARAMETER>f</PARAMETER>(123);
        }
    """)

    fun `test macro`() = checkByText("""
        fn <FUNCTION>main</FUNCTION>() {
            <MACRO>println</MACRO><MACRO>!</MACRO>["Hello, World!"];
            <MACRO>unreachable</MACRO><MACRO>!</MACRO>();
        }
        <MACRO>macro_rules</MACRO><MACRO>!</MACRO> foo {
            (x => $ <FUNCTION>e</FUNCTION>:expr) => (println!("mode X: {}", $ <FUNCTION>e</FUNCTION>));
            (y => $ <FUNCTION>e</FUNCTION>:expr) => (println!("mode Y: {}", $ <FUNCTION>e</FUNCTION>));
        }
        impl T {
            <MACRO>foo</MACRO><MACRO>!</MACRO>();
        }
    """)

    fun `test type parameters`() = checkHighlightingWithMacro("""
        trait <TRAIT>MyTrait</TRAIT> {
            type <TYPE_ALIAS>AssocType</TYPE_ALIAS>;
            fn <METHOD>some_fn</METHOD>(&<SELF_PARAMETER>self</SELF_PARAMETER>);
        }
        struct <STRUCT>MyStruct</STRUCT><<TYPE_PARAMETER>N</TYPE_PARAMETER>: ?<TRAIT>Sized</TRAIT>+<TRAIT>Debug</TRAIT>+<TRAIT>MyTrait</TRAIT>> {
            <FIELD>N</FIELD>: my_field
        }
    """)

    fun `test const parameters`() = checkHighlightingWithMacro("""
        struct MyStruct<const <CONST_PARAMETER>N</CONST_PARAMETER>: usize>;
        trait MyTrait<const <CONST_PARAMETER>N</CONST_PARAMETER>: usize> {
            fn foo<const <CONST_PARAMETER>M</CONST_PARAMETER>: usize>(a: [i32; <CONST_PARAMETER>M</CONST_PARAMETER>]);
        }
        impl MyTrait<0> for MyStruct<0> {
            fn foo<const <CONST_PARAMETER>M</CONST_PARAMETER>: usize>(a: [i32; <CONST_PARAMETER>M</CONST_PARAMETER>]) {
                let x = <CONST_PARAMETER>M</CONST_PARAMETER>;
            }
        }
    """)

    fun `test function arguments`() = checkHighlightingWithMacro("""
        struct <STRUCT>Foo</STRUCT> {}
        impl <STRUCT>Foo</STRUCT> {
            fn <METHOD>bar</METHOD>(&<SELF_PARAMETER>self</SELF_PARAMETER>, (<PARAMETER>i</PARAMETER>, <PARAMETER>j</PARAMETER>): (<PRIMITIVE_TYPE>i32</PRIMITIVE_TYPE>, <PRIMITIVE_TYPE>i32</PRIMITIVE_TYPE>)) {}
        }
        fn <FUNCTION>baz</FUNCTION>(<PARAMETER>u</PARAMETER>: <PRIMITIVE_TYPE>u32</PRIMITIVE_TYPE>) {}
    """)

    fun `test contextual keywords`() = checkHighlightingWithMacro("""
        trait <TRAIT>T</TRAIT> {
            fn <ASSOC_FUNCTION>foo</ASSOC_FUNCTION>();
        }
        <KEYWORD>union</KEYWORD> <UNION>U</UNION> { }
        impl <TRAIT>T</TRAIT> for <UNION>U</UNION> {
            <KEYWORD>default</KEYWORD> fn <ASSOC_FUNCTION>foo</ASSOC_FUNCTION>() {}
        }
    """)

    fun `test ? operator`() = checkHighlightingWithMacro("""
        fn <FUNCTION>foo</FUNCTION>() -> Result<<PRIMITIVE_TYPE>i32</PRIMITIVE_TYPE>, ()>{
            Ok(Ok(1)<Q_OPERATOR>?</Q_OPERATOR> * 2)
        }
    """)

    fun `test type alias`() = checkHighlightingWithMacro("""
        type <TYPE_ALIAS>Bar</TYPE_ALIAS> = <PRIMITIVE_TYPE>u32</PRIMITIVE_TYPE>;
        fn <FUNCTION>main</FUNCTION>() {
            let a: <TYPE_ALIAS>Bar</TYPE_ALIAS> = 10;
        }
    """)

    fun `test keyword paths are not over annotated`() = checkByText("""
        pub use self::<MODULE>foo</MODULE>;
        pub use crate::foobar;

        mod <MODULE>foo</MODULE> {
            pub use self::<MODULE>bar</MODULE>;

            pub mod <MODULE>bar</MODULE> {}
        }

        trait <TRAIT>Foo</TRAIT> { fn <ASSOC_FUNCTION>foo</ASSOC_FUNCTION>(_: Self) -> Self; }
    """, checkWarn = false, ignoreExtraHighlighting = false)

    fun `test primitive`() = checkByText("""
        fn <FUNCTION>main</FUNCTION>() -> <PRIMITIVE_TYPE>bool</PRIMITIVE_TYPE> {
            let a: <PRIMITIVE_TYPE>u8</PRIMITIVE_TYPE> = 42;
            let b: <PRIMITIVE_TYPE>f32</PRIMITIVE_TYPE> = 10.0;
            let c: &<PRIMITIVE_TYPE>str</PRIMITIVE_TYPE> = "example";
            <PRIMITIVE_TYPE>char</PRIMITIVE_TYPE>::is_lowercase('a');
            let mut i32 = 1;
            i32 = 2;
            true
        }
    """)

    @ProjectDescriptor(WithStdlibAndDependencyRustProjectDescriptor::class)
    fun `test crate`() = checkHighlightingWithMacro("""
        extern crate <CRATE>dep_lib_target</CRATE>;

        use <CRATE>std</CRATE>::<MODULE>io</MODULE>::<TRAIT>Read</TRAIT>;
    """)

    fun `test dont touch ast in other files`() = checkDontTouchAstInOtherFiles("""
        //- main.rs
            mod <MODULE>aux</MODULE>;

            fn <FUNCTION>main</FUNCTION>() {
                let _ = <MODULE>aux</MODULE>::<STRUCT>S</STRUCT>;
            }

        //- aux.rs
            pub struct S;
        """
    )

    fun `test const and static`() = checkHighlightingWithMacro("""
        const <CONST>FOO</CONST>: i32 = 0;
        static <CONST>BAR</CONST>: i32 = 0;
        fn main() {
            <CONST>FOO</CONST>;
            <CONST>BAR</CONST>;
        }
    """)

    @MockEdition(Edition.EDITION_2015)
    fun `test postfix await 2015`() = checkHighlightingWithMacro("""
        fn main() {
            dummy.await;
        }
    """)

    @MockEdition(Edition.EDITION_2018)
    fun `test postfix await 2018`() = checkHighlightingWithMacro("""
        fn main() {
            dummy.<KEYWORD>await</KEYWORD>;
        }
    """)

    fun `test highlight todo macro when todo highlighting disabled`() = withoutTodoHighlighting {
        checkHighlighting("""
            fn main() {
                <MACRO>todo</MACRO><MACRO>!</MACRO>("");
            }
        """)
    }

    @MockEdition(Edition.EDITION_2018)
    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test highlight macro in use item`() = checkByFileTree("""
    //- lib.rs
        use <CRATE>dep_lib_target</CRATE>::<MACRO>foo</MACRO>; /*caret*/
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    private fun checkHighlightingWithMacro(@Language("Rust") text: String) {
        checkHighlighting(text)
        checkHighlightingInsideMacroCall(text)
    }

    private fun checkHighlightingInsideMacroCall(@Language("Rust") text: String) {
        checkHighlighting("""
            macro_rules! as_is {
                ($($ t:tt)*) => {$($ t)*};
            }
            as_is! {
                $text
            }
        """)
    }

    private fun withoutTodoHighlighting(action: () -> Unit) {
        val todoConfiguration = TodoConfiguration.getInstance()
        val todoPatterns = todoConfiguration.todoPatterns
        todoConfiguration.todoPatterns = emptyArray()
        try {
            action()
        } finally {
            todoConfiguration.todoPatterns = todoPatterns
        }
    }
}
