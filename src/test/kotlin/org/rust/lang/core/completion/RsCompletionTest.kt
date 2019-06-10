/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion

import org.rust.ProjectDescriptor
import org.rust.WithDependencyRustProjectDescriptor
import org.rust.WithStdlibAndDependencyRustProjectDescriptor
import org.rust.WithStdlibRustProjectDescriptor

class RsCompletionTest : RsCompletionTestBase() {
    fun `test local variable`() = doSingleCompletion("""
        fn foo(quux: i32) { qu/*caret*/ }
    """, """
        fn foo(quux: i32) { quux/*caret*/ }
    """)

    fun `test function call zero args`() = doSingleCompletion("""
        fn frobnicate() {}
        fn main() { frob/*caret*/ }
    """, """
        fn frobnicate() {}
        fn main() { frobnicate()/*caret*/ }
    """)

    fun `test function call one arg`() = doSingleCompletion("""
        fn frobnicate(foo: i32) {}
        fn main() { frob/*caret*/ }
    """, """
        fn frobnicate(foo: i32) {}
        fn main() { frobnicate(/*caret*/) }
    """)

    fun `test function call with parens`() = doSingleCompletion("""
        fn frobnicate() {}
        fn main() { frob/*caret*/() }
    """, """
        fn frobnicate() {}
        fn main() { frobnicate()/*caret*/ }
    """)

    fun `test tuple struct with parens`() = doSingleCompletion("""
        struct Frobnicate(i32, String);
        fn main() { Frob/*caret*/() }
    """, """
        struct Frobnicate(i32, String);
        fn main() { Frobnicate/*caret*/() }
    """)

    fun `test tuple enum with parens`() = doSingleCompletion("""
        enum E { Frobnicate(i32, String) }
        fn main() { E::Frob/*caret*/() }
    """, """
        enum E { Frobnicate(i32, String) }
        fn main() { E::Frobnicate(/*caret*/) }
    """)

    fun `test tuple enum match`() = doSingleCompletion("""
        enum E { Frobnicate(i32, String) }
        fn foo(f: E) { match f {
            E::Frob/*caret*/() => {}
        }}
    """, """
        enum E { Frobnicate(i32, String) }
        fn foo(f: E) { match f {
            E::Frobnicate(/*caret*/) => {}
        }}
    """)

    fun `test struct-like enum with braces`() = doSingleCompletion("""
        enum E { Frobnicate { f: i32 } }
        fn main() { E::Frob/*caret*/ {} }
    """, """
        enum E { Frobnicate { f: i32 } }
        fn main() { E::Frobnicate {/*caret*/} }
    """)

    fun `test function call with parens with arg`() = doSingleCompletion("""
        fn frobnicate(foo: i32) {}
        fn main() { frob/*caret*/() }
    """, """
        fn frobnicate(foo: i32) {}
        fn main() { frobnicate(/*caret*/) }
    """)

    fun `test function call with parens overwrite`() = doSingleCompletion("""
        fn frobnicate(foo: i32) {}
        fn main() { frob/*caret*/transmog() }
    """, """
        fn frobnicate(foo: i32) {}
        fn main() { frobnicate(/*caret*/)transmog() }
    """)

    fun `test path`() = doSingleCompletion("""
        mod foo {
            pub mod bar { pub fn frobnicate() {} }
        }
        fn frobfrobfrob() {}

        fn main() {
            foo::bar::frob/*caret*/
        }
    """, """
        mod foo {
            pub mod bar { pub fn frobnicate() {} }
        }
        fn frobfrobfrob() {}

        fn main() {
            foo::bar::frobnicate()/*caret*/
        }
    """)

    fun `test anonymous item does not break completion`() = doSingleCompletion("""
        extern "C" { }
        fn frobnicate() {}

        fn main() {
            frob/*caret*/
        }
    """, """
        extern "C" { }
        fn frobnicate() {}

        fn main() {
            frobnicate()/*caret*/
        }
    """)

    fun `test use glob`() = doSingleCompletion("""
        mod foo { pub fn quux() {} }

        use self::foo::q/*caret*/;
    """, """
        mod foo { pub fn quux() {} }

        use self::foo::quux/*caret*/;
    """)

    fun `test use glob self`() = doSingleCompletion("""
        mod foo { }

        use self::foo::{sel/*caret*/};
    """, """
        mod foo { }

        use self::foo::{self/*caret*/};
    """)

    fun `test use glob global`() = doSingleCompletion("""
        pub struct Foo;

        mod m {
            use F/*caret*/;
        }
    """, """
        pub struct Foo;

        mod m {
            use Foo/*caret*/;
        }
    """)

    fun `test use item`() = doSingleCompletion("""
        mod foo { pub fn quux() {} }
        use self::foo::q/*caret*/;
    """, """
        mod foo { pub fn quux() {} }
        use self::foo::quux/*caret*/;
    """)

    fun `test use glob function no semicolon`() = doSingleCompletion("""
        mod foo { pub fn quux() {} }
        use self::foo::{q/*caret*/};
    """, """
        mod foo { pub fn quux() {} }
        use self::foo::{quux/*caret*/};
    """)

    fun `test use function semicolon`() = doSingleCompletion("""
        use self::f/*caret*/
        fn foo() {}
    """, """
        use self::foo;/*caret*/
        fn foo() {}
    """)

    fun `test use struct semicolon`() = doSingleCompletion("""
        use self::F/*caret*/
        struct Foo;
    """, """
        use self::Foo;/*caret*/
        struct Foo;
    """)

    fun `test use const semicolon`() = doSingleCompletion("""
        use self::F/*caret*/
        const Foo: str = "foo";
    """, """
        use self::Foo;/*caret*/
        const Foo: str = "foo";
    """)

    fun `test use static semicolon`() = doSingleCompletion("""
        use self::F/*caret*/
        static Foo: str = "foo";
    """, """
        use self::Foo;/*caret*/
        static Foo: str = "foo";
    """)

    fun `test use trait semicolon`() = doSingleCompletion("""
        use self::f/*caret*/
        trait foo{}
    """, """
        use self::foo;/*caret*/
        trait foo{}
    """)

    fun `test wildcard imports`() = doSingleCompletion("""
        mod foo { pub fn transmogrify() {} }

        fn main() {
            use self::foo::*;
            trans/*caret*/
        }
    """, """
        mod foo { pub fn transmogrify() {} }

        fn main() {
            use self::foo::*;
            transmogrify()/*caret*/
        }
    """)

    fun `test shadowing`() = doSingleCompletion("""
        fn main() {
            let foobar = "foobar";
            let foobar = foobar.to_string();
            foo/*caret*/
        }
    """, """
        fn main() {
            let foobar = "foobar";
            let foobar = foobar.to_string();
            foobar/*caret*/
        }
    """)

    fun `test complete alias`() = doSingleCompletion("""
        mod m { pub fn transmogrify() {} }
        use self::m::transmogrify as frobnicate;

        fn main() {
            frob/*caret*/
        }
    """, """
        mod m { pub fn transmogrify() {} }
        use self::m::transmogrify as frobnicate;

        fn main() {
            frobnicate()/*caret*/
        }
    """)

    fun `test complete self type`() = doSingleCompletion("""
        trait T { fn foo() -> Se/*caret*/ }
    """, """
        trait T { fn foo() -> Self/*caret*/ }
    """)

    fun `test complete self method`() = doSingleCompletion("""
        struct S;
        impl S { fn foo(&se/*caret*/) {}}
    """, """
        struct S;
        impl S { fn foo(&self/*caret*/) {}}
    """)

    fun `test complete self with double colon path method`() = doSingleCompletion("""
        struct S;
        impl S { fn foo(test: &se/*caret*/) {}}
    """, """
        struct S;
        impl S { fn foo(test: &self::/*caret*/) {}}
    """)

    fun `test struct field`() = doSingleCompletion("""
        struct S { foobarbaz: i32 }
        fn main() {
            let _ = S { foo/*caret*/ };
        }
    """, """
        struct S { foobarbaz: i32 }
        fn main() {
            let _ = S { foobarbaz/*caret*/ };
        }
    """)

    fun `test enum field`() = doSingleCompletion("""
        enum E { X { bazbarfoo: i32 } }
        fn main() {
            let _ = E::X { baz/*caret*/ }
        }
    """, """
        enum E { X { bazbarfoo: i32 } }
        fn main() {
            let _ = E::X { bazbarfoo/*caret*/ }
        }
    """)

    fun `test local scope`() = checkNoCompletion("""
        fn foo() {
            let x = spam/*caret*/;
            let spamlot = 92;
        }
    """)

    fun `test while let`() = checkNoCompletion("""
        fn main() {
            while let Some(quazimagnitron) = quaz/*caret*/ { }
        }
    """)

    fun `test completion respects namespaces`() = checkNoCompletion("""
        fn foobar() {}

        fn main() {
            let _: foo/*caret*/ = unimplemented!();
        }
    """)

    fun `test child file`() = doSingleCompletionMultifile("""
    //- main.rs
        use foo::Spam;
        mod foo;
        fn main() { let _ = Spam::Q/*caret*/; }

    //- foo.rs
        pub enum Spam { Quux, Eggs }
    """, """
        use foo::Spam;
        mod foo;
        fn main() { let _ = Spam::Quux/*caret*/; }
    """)

    fun `test parent file`() = doSingleCompletionMultifile("""
    //- main.rs
        mod foo;
        pub enum Spam { Quux, Eggs }

    //- foo.rs
        use super::Spam;
        fn foo() { let _ = Spam::Q/*caret*/; }
    """, """
        use super::Spam;
        fn foo() { let _ = Spam::Quux/*caret*/; }
    """)

    fun `test parent file 2`() = doSingleCompletionMultifile("""
    //- main.rs
        mod foo;
        pub enum Spam { Quux, Eggs }

    //- foo/mod.rs
        use Spam::Qu/*caret*/;
    """, """
        use Spam::Quux/*caret*/;
    """)

    fun `test enum variant`() = doSingleCompletion("""
        enum Foo { BARBOO, BAZBAR }
        fn main() { let _ = Foo::BAZ/*caret*/ }
    """, """
        enum Foo { BARBOO, BAZBAR }
        fn main() { let _ = Foo::BAZBAR/*caret*/ }
    """)

    fun `test enum variant with tuple fields`() = doSingleCompletion("""
        enum Foo { BARBAZ(f64) }
        fn main() { let _ = Foo::BAR/*caret*/ }
    """, """
        enum Foo { BARBAZ(f64) }
        fn main() { let _ = Foo::BARBAZ(/*caret*/) }
    """)

    fun `test enum variant with tuple fields in use block`() = doSingleCompletion("""
        enum Foo { BARBAZ(f64) }
        fn main() { use Foo::BAR/*caret*/ }
    """, """
        enum Foo { BARBAZ(f64) }
        fn main() { use Foo::BARBAZ/*caret*/ }
    """)

    fun `test enum variant with block fields`() = doSingleCompletion("""
        enum Foo { BARBAZ { foo: f64 } }
        fn main() { let _ = Foo::BAR/*caret*/ }
    """, """
        enum Foo { BARBAZ { foo: f64 } }
        fn main() { let _ = Foo::BARBAZ {/*caret*/} }
    """)

    fun `test enum variant with block fields in use block`() = doSingleCompletion("""
        enum Foo { BARBAZ { foo: f64 } }
        fn main() { use Foo::BAR/*caret*/ }
    """, """
        enum Foo { BARBAZ { foo: f64 } }
        fn main() { use Foo::BARBAZ/*caret*/ }
    """)

    fun `test type namespace is completed for path head`() = doSingleCompletion("""
        struct FooBar { f: i32 }

        fn main() { Foo/*caret*/ }
    """, """
        struct FooBar { f: i32 }

        fn main() { FooBar/*caret*/ }
    """)

    // issue #1182
    fun `test associated type completion`() = doSingleCompletion("""
        trait Foo {
            type Bar;
            fn foo(bar: Self::Ba/*caret*/);
        }
    """, """
        trait Foo {
            type Bar;
            fn foo(bar: Self::Bar/*caret*/);
        }
    """)

    fun `test complete enum variants 1`() = doSingleCompletion("""
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        fn foo(e: Expr) {
            use self::Expr::*;
            match e {
                Bi/*caret*/
            }
        }
    """, """
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        fn foo(e: Expr) {
            use self::Expr::*;
            match e {
                BinOp(/*caret*/)
            }
        }
    """)

    fun `test complete enum variants 2`() = doSingleCompletion("""
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        fn foo(e: Expr) {
            use self::Expr::*;
            match e {
                Un/*caret*/
            }
        }
    """, """
        enum Expr { Unit, BinOp(Box<Expr>, Box<Expr>) }
        fn foo(e: Expr) {
            use self::Expr::*;
            match e {
                Unit/*caret*/
            }
        }
    """)

    fun `test should not complete for test functions`() = checkNoCompletion("""
        #[test]
        fn foobar() {}

        fn main() {
            foo/*caret*/
        }
    """)

    fun `test complete macro`() = doSingleCompletion("""
        macro_rules! foo_bar { () => () }
        fn main() {
            fo/*caret*/
        }
    """, """
        macro_rules! foo_bar { () => () }
        fn main() {
            foo_bar!(/*caret*/)
        }
    """)

    fun `test complete outer macro`() = doSingleCompletion("""
        macro_rules! foo_bar { () => () }
        fo/*caret*/
        fn main() {
        }
    """, """
        macro_rules! foo_bar { () => () }
        foo_bar!(/*caret*/)
        fn main() {
        }
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete macro with qualified path`() = doSingleCompletionMultifile("""
    //- lib.rs
        fn bar() {
            dep_lib_target::fo/*caret*/
        }
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => () }
    """, """
        fn bar() {
            dep_lib_target::foo_bar!(/*caret*/)
        }
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete outer macro with qualified path 1`() = doSingleCompletionMultifile("""
    //- lib.rs
        dep_lib_target::fo/*caret*/
        fn foo(){}
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => () }
    """, """
        dep_lib_target::foo_bar!(/*caret*/)
        fn foo(){}
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test complete outer macro with qualified path 2`() = doSingleCompletionMultifile("""
    //- lib.rs
        ::dep_lib_target::fo/*caret*/
        fn foo(){}
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => () }
    """, """
        ::dep_lib_target::foo_bar!(/*caret*/)
        fn foo(){}
    """)

    fun `test macro don't suggests as function name`() = checkNoCompletion("""
        macro_rules! foo_bar { () => () }
        fn foo/*caret*/() {
        }
    """)

    // https://github.com/intellij-rust/intellij-rust/issues/1598
    fun `test no macro completion in item element definition`() {
        for (itemKeyword in listOf("fn", "struct", "enum", "union", "trait", "type", "impl")) {
            checkNoCompletion("""
                macro_rules! foo_bar { () => () }
                $itemKeyword Foo f/*caret*/
            """)
        }
    }

    // https://github.com/intellij-rust/intellij-rust/issues/1598
    fun `test no macro completion after path segment`() = checkNoCompletion("""
        struct Foo;
        macro_rules! foo_bar { () => () }
        fn main() {
            Foo::f/*caret*/
        }
    """)

    @ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
    fun `test no completion for outer macro with qualified path of 3 segments`() = checkNoCompletionWithMultifile("""
    //- lib.rs
        quux::dep_lib_target::fo/*caret*/
        fn foo(){}
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => () }
    """)

    fun `test hidden macro completes in the same module`() = doSingleCompletion("""
        #[doc(hidden)]
        macro_rules! private_macro {}

        fn main() { private_m/*caret*/ }
    """, """
        #[doc(hidden)]
        macro_rules! private_macro {}

        fn main() { private_macro!(/*caret*/) }
    """)

    fun `test hidden macro is hidden in other module`() = checkNoCompletion("""
        #[doc(hidden)]
        macro_rules! private_macro {}

        mod inner {
            fn main() { private_m/*caret*/ }
        }
    """)

    fun `test hidden macro is hidden in other module multiple doc attributes`() = checkNoCompletion("""
        #[doc="No problems with"]
        #[doc(hidden)]
        #[doc="explicit docs"]
        macro_rules! private_macro {}

        mod inner {
            fn main() { private_m/*caret*/ }
        }
    """)

    fun `test explicit associated type binding`() = doSingleCompletion("""
        trait Tr { type Item; }
        type T = Tr<It/*caret*/=u8>;
    """, """
        trait Tr { type Item; }
        type T = Tr<Item/*caret*/=u8>;
    """)

    fun `test possible associated type binding`() = doSingleCompletion("""
        trait Tr { type Item; }
        type T = Tr<It/*caret*/>;
    """, """
        trait Tr { type Item; }
        type T = Tr<Item/*caret*/>;
    """)

    fun `test complete crate with double colon in use path`() = doSingleCompletion("""
        use cra/*caret*/
    """, """
        use crate::/*caret*/
    """)

    fun `test complete crate with double colon in general path`() = doSingleCompletion("""
       fn main() {
            let x = cra/*caret*/
       }
    """, """
       fn main() {
            let x = crate::/*caret*/
       }
    """)

    fun `test complete paths in include macro`() = doSingleCompletionMultifile("""
    //- main.rs
        include!("fo/*caret*/");
    //- foo.rs
        pub struct Foo;
    """, """
        include!("foo.rs/*caret*/");
    """)

    fun `test complete path in path attribute on mod decl`() = doSingleCompletionMultifile("""
    //- main.rs
        #[path="b/*caret*/"]
        mod foo;
    //- bar.rs
        fn bar() {}
    """, """
        #[path="bar.rs/*caret*/"]
        mod foo;
    """)

    fun `test complete rust file path in path attribute`() = doSingleCompletionMultifile("""
    //- main.rs
        #[path="b/*caret*/"]
        mod foo;
    //- bar.rs
        fn bar() {}
    //- baz.txt
        // some text
    """, """
        #[path="bar.rs/*caret*/"]
        mod foo;
    """)

    fun `test complete path in path attribute on mod`() = doSingleCompletionMultifile("""
    //- main.rs
        #[path="ba/*caret*/"]
        mod foo {
        }
    //- baz/bar.rs
        fn bar() {}
    """, """
        #[path="baz/*caret*/"]
        mod foo {
        }
    """)

    fun `test complete path in path attribute on inner mod decl`() = doSingleCompletionMultifile("""
    //- main.rs
        #[path="baz"]
        mod foo {
            #[path="ba/*caret*/"]
            mod qqq;
        }
    //- baz/bar.rs
        fn bar() {}
    """, """
        #[path="baz"]
        mod foo {
            #[path="bar.rs/*caret*/"]
            mod qqq;
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test private extern crate`() = checkNoCompletion("""
        mod foo { extern crate std; }
        pub use foo::st/*caret*/
    """)

    @ProjectDescriptor(WithStdlibAndDependencyRustProjectDescriptor::class)
    fun `test no std completion`() = checkNoCompletion("""
        extern crate dep_lib_target;
        pub use dep_lib_target::st/*caret*/
    """)

    fun `test complete with identifier escaping`() = doSingleCompletion("""
        fn r#else() {}
        fn main() {
            els/*caret*/
        }
    """, """
        fn r#else() {}
        fn main() {
            r#else()/*caret*/
        }
    """)

    fun `test complete lifetime`() = doSingleCompletion("""
        fn foo<'aaaaaa>(x:&'a/*caret*/ str) {}
    """, """
        fn foo<'aaaaaa>(x:&'aaaaaa/*caret*/ str) {}
    """)

    fun `test super completion`() = doSingleCompletion("""
        pub mod foo {
            fn bar() {
                self::su/*caret*/
            }
        }
    """, """
        pub mod foo {
            fn bar() {
                self::super::/*caret*/
            }
        }
    """)

    fun `test not super completion after named path segment`() = checkNoCompletion("""
        pub mod foo {}
        fn main() {
            foo::su/*caret*/
        }
    """)

    fun `test self completion with extern crate self without alias`() = doSingleCompletion("""
        extern crate self;

        mod foo {
            use sel/*caret*/
        }
    """, """
        extern crate self;

        mod foo {
            use self::/*caret*/
        }
    """)

    fun `test completion after tuple field expr`() = doSingleCompletion("""
        struct S { field: i32 }
        fn main() {
            let x = (0, S { field: 0 });
            x.1./*caret*/
        }
    """, """
        struct S { field: i32 }
        fn main() {
            let x = (0, S { field: 0 });
            x.1.field/*caret*/
        }
    """)

    fun `test 123`() = doSingleCompletion("""
        macro_rules! foo {
            ($($ i:item)*) => {
                $( $ i )*
            };
        }
        foo! {
            struct FooBar;
            fn bar() {
                F/*caret*/
            }
        }
    """, """
        macro_rules! foo {
            ($($ i:item)*) => {
                $( $ i )*
            };
        }
        foo! {
            struct FooBar;
            fn bar() {
                FooBar/*caret*/
            }
        }
    """)
}
