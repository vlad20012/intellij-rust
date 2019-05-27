/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve

import org.rust.MockEdition
import org.rust.ProjectDescriptor
import org.rust.WithDependencyRustProjectDescriptor
import org.rust.WithStdlibAndDependencyRustProjectDescriptor
import org.rust.cargo.project.workspace.CargoWorkspace

@ProjectDescriptor(WithDependencyRustProjectDescriptor::class)
class RsPackageLibraryResolveTest : RsResolveTestBase() {
    fun `test library as crate`() = stubOnlyResolve("""
    //- main.rs
        extern crate test_package;

        fn main() {
            test_package::hello();
        }         //^ lib.rs

    //- lib.rs
        pub fn hello() {}
    """)

    fun `test crate alias`() = stubOnlyResolve("""
    //- main.rs
        extern crate test_package as other_name;

        fn main() {
            other_name::hello();
        }                //^ lib.rs

    //- lib.rs
        pub fn hello() {}
    """)


    fun `test macro rules`() = stubOnlyResolve("""
    //- main.rs
        #[macro_use]
        extern crate test_package;

        fn main() {
            foo_bar!();
        }  //^ lib.rs
    //- lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => {} }
    """)

    fun `test duplicated macro_export macro`() = stubOnlyResolve("""
    //- main.rs
        #[macro_use]
        extern crate test_package;

        fn main() {
            foo_bar!();
        }  //^ unresolved
    //- lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => {} }
        #[macro_export]
        macro_rules! foo_bar { () => {} }
    """)

    fun `test macro rules missing macro_export`() = stubOnlyResolve("""
    //- main.rs
        #[macro_use]
        extern crate test_package;

        fn main() {
            foo_bar!();
        }  //^ unresolved
    //- lib.rs
        // Missing #[macro_export] here
        macro_rules! foo_bar { () => {} }
    """, NameResolutionTestmarks.missingMacroExport)

    fun `test macro rules missing macro_use`() = stubOnlyResolve("""
    //- main.rs
        // Missing #[macro_use] here
        extern crate test_package;

        fn main() {
            foo_bar!();
        }  //^ unresolved
    //- lib.rs
        #[macro_export]
        macro_rules! foo_bar { () => {} }
    """, NameResolutionTestmarks.missingMacroUse)

    fun `test macro rules in mod 1`() = stubOnlyResolve("""
    //- main.rs
        #[macro_use]
        extern crate test_package;

        fn main() {
            foo_bar!();
        }  //^ lib.rs
    //- lib.rs
        mod foo {
            #[macro_export]
            macro_rules! foo_bar { () => {} }
        }
    """)

    fun `test macro rules in mod 2`() = stubOnlyResolve("""
    //- main.rs
        #[macro_use]
        extern crate test_package;

        fn main() {
            foo_bar!();
        }  //^ foo.rs
    //- lib.rs
        mod foo;
    //- foo.rs
        #[macro_export]
        macro_rules! foo_bar { () => {} }
    """)

    fun `test macro reexport in use item`() = stubOnlyResolve("""
    //- lib.rs
        #[macro_use]
        extern crate dep_lib_target;

        pub use dep_lib_target::foo;
                               //^ dep-lib/lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    fun `test new macro reexport`() = stubOnlyResolve("""
    //- lib.rs
        extern crate dep_lib_target;

        pub use dep_lib_target::foo;

    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }

    //- main.rs
        #[macro_use]
        extern crate test_package;

        fn main() {
            foo!();
            //^ dep-lib/lib.rs
        }
    """)

    fun `test new macro reexport with crate alias`() = stubOnlyResolve("""
    //- lib.rs
        extern crate dep_lib_target as dep_lib;

        pub use dep_lib::foo;

    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }

    //- main.rs
        #[macro_use]
        extern crate test_package;

        fn main() {
            foo!();
            //^ dep-lib/lib.rs
        }
    """)

    fun `test new macro reexport from inner module`() = stubOnlyResolve("""
    //- lib.rs
        extern crate dep_lib_target;

        pub use dep_lib_target::foo;

    //- dep-lib/lib.rs
        mod macros;

    //- dep-lib/macros.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }

    //- main.rs
        #[macro_use]
        extern crate test_package;

        fn main() {
            foo!();
            //^ dep-lib/macros.rs
        }
    """)

    fun `test import macro by use item`() = stubOnlyResolve("""
    //- lib.rs
        extern crate dep_lib_target;
        use dep_lib_target::foo;
        fn bar() {
            foo!();
        } //^ dep-lib/lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """, NameResolutionTestmarks.missingMacroUse)

    fun `test import macro by use item wildcard`() = stubOnlyResolve("""
    //- lib.rs
        extern crate dep_lib_target;
        use dep_lib_target::*;
        fn bar() {
            foo!();
        } //^ dep-lib/lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """, NameResolutionTestmarks.missingMacroUse)

    fun `test import macro by use item without extern crate`() = stubOnlyResolve("""
    //- lib.rs
        use dep_lib_target::foo;
        fn bar() {
            foo!();
        } //^ dep-lib/lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    fun `test import macro by use item wildcard without extern crate`() = stubOnlyResolve("""
    //- lib.rs
        use dep_lib_target::*;
        fn bar() {
            foo!();
        } //^ dep-lib/lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    fun `test import macro by complex use item without extern crate`() = stubOnlyResolve("""
    //- lib.rs
        use {{dep_lib_target::{{foo}}}};
        fn bar() {
            foo!();
        } //^ dep-lib/lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    fun `test import macro by complex use item wildcard without extern crate`() = stubOnlyResolve("""
    //- lib.rs
        use {{dep_lib_target::{{*}}}};
        fn bar() {
            foo!();
        } //^ dep-lib/lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    // Issue https://github.com/intellij-rust/intellij-rust/issues/3642
    fun `test issue 3642 1`() = stubOnlyResolve("""
    //- lib.rs
        use dep_lib_target::foobar::*;
        fn bar() {
            foo!();
        } //^ unresolved
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    // Issue https://github.com/intellij-rust/intellij-rust/issues/3642
    @ProjectDescriptor(WithStdlibAndDependencyRustProjectDescriptor::class)
    fun `test issue 3642 2`() = stubOnlyResolve("""
    //- lib.rs
        use dep_lib_target::*;
        fn bar() {
            foo!();
        } //^ unresolved
    //- dep-lib/lib.rs
        pub use self::*;
    """)

    fun `test local definition wins over imported by use item`() = stubOnlyResolve("""
    //- lib.rs
        use dep_lib_target::foo;
        macro_rules! foo {
            () => {};
        }
        fn bar() {
            foo!();
        } //^ lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    fun `test import macro by non-root use item with aliased extern crate`() = stubOnlyResolve("""
    //- lib.rs
        extern crate dep_lib_target as aliased;
        mod bar {
            use aliased::foo;
            fn bar() {
                foo!();
            } //^ dep-lib/lib.rs
        }
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    fun `test import macro by qualified path without extern crate`() = stubOnlyResolve("""
    //- lib.rs
        fn bar() {
            dep_lib_target::foo!();
        }                 //^ dep-lib/lib.rs
    //- dep-lib/lib.rs
        #[macro_export]
        macro_rules! foo {
            () => {};
        }
    """)

    // TODO
    fun `test import macro by qualified path with aliased extern crate`() = expect<IllegalStateException> {
        stubOnlyResolve("""
        //- lib.rs
            extern crate dep_lib_target as aliased;
            fn bar() {
                aliased::foo!();
            }          //^ dep-lib/lib.rs
        //- dep-lib/lib.rs
            #[macro_export]
            macro_rules! foo {
                () => {};
            }
        """)
    }

    fun `test import from crate root without 'pub' vis`() = stubOnlyResolve("""
    //- lib.rs
        mod foo {
            pub mod bar {
                pub struct S;
            }
        }
        use foo::bar;

        mod baz;
    //- baz.rs
        use bar::S;
               //^ lib.rs
    """)

    fun `test transitive dependency on the same crate`() = stubOnlyResolve("""
    //- dep-lib-new/lib.rs
        pub struct Foo;
    //- dep-lib/lib.rs
        extern crate dep_lib_target;

        pub use dep_lib_target::Foo;
    //- lib.rs
        extern crate dep_lib_target;

        use dep_lib_target::Foo;
                            //^ dep-lib-new/lib.rs
    """, NameResolutionTestmarks.otherVersionOfSameCrate)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test resolve reference without extern crate item 1 (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        use dep_lib_target::Foo;
                //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test resolve reference without extern crate item 2 (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        fn foo() -> dep_lib_target::Foo { unimplemented!() }
                                   //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2015)
    fun `test resolve reference without extern crate item 1 (edition 2015)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        use dep_lib_target::Foo;
                //^ unresolved
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2015)
    fun `test resolve reference without extern crate item 2 (edition 2015)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        fn foo() -> dep_lib_target::Foo { unimplemented!() }
                                   //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test resolve module instead of crate (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        mod dep_lib_target {
            pub struct Foo;
        }
        fn foo() -> dep_lib_target::Foo { unimplemented!() }
                                   //^ lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2015)
    fun `test resolve module instead of crate (edition 2015)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        mod dep_lib_target {
            pub struct Foo;
        }
        fn foo() -> dep_lib_target::Foo { unimplemented!() }
                                   //^ lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test resolve module instead of crate in nested mod (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        mod bar {
            mod dep_lib_target {
                pub struct Foo;
            }
            fn foo() -> dep_lib_target::Foo { unimplemented!() }
                                       //^ lib.rs
        }
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test resolve module instead of crate in use item in nested mod (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        mod bar {
            mod dep_lib_target {
                pub struct Foo;
            }
            use dep_lib_target::Foo;
                               //^ lib.rs
        }
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test resolve crate instead of module in absolute use item (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        mod dep_lib_target {
            pub struct Foo;
        }
        use ::dep_lib_target::Foo;
                            //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extern crate item (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        extern crate dep_lib_target;

        fn foo() -> dep_lib_target::Foo { unimplemented!() }
                                   //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extern crate item alias 1 (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        extern crate dep_lib_target as dep_lib;

        fn foo() -> dep_lib::Foo { unimplemented!() }
                            //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extern crate item alias 2 (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        extern crate dep_lib_target as dep_lib;

        fn foo() -> dep_lib_target::Foo { unimplemented!() }
                                   //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extern crate item alias with same name (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        extern crate dep_lib_target as dep_lib_target;

        fn foo() -> dep_lib_target::Foo { unimplemented!() }
                                   //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extern crate in super chain (edition 2018)`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        mod foo {
            extern crate dep_lib_target;
            use self::dep_lib_target::Foo;
                                     //^ dep-lib/lib.rs
        }
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test resolve bang proc macro with use item`() = stubOnlyResolve("""
    //- dep-proc-macro/lib.rs
        #[proc_macro]
        pub fn example_proc_macro(item: TokenStream) -> TokenStream { item }
    //- lib.rs
        use dep_proc_macro::example_proc_macro;
        example_proc_macro!();
        //^ dep-proc-macro/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extern crate alias`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        extern crate dep_lib_target as dep_lib;

        mod foo {
            use dep_lib::Foo;
                       //^ dep-lib/lib.rs
        }
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extern crate alias shadows implicit extern crate names`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- dep-lib-2/lib.rs
        pub struct Foo;
    //- lib.rs
        extern crate dep_lib_target as dep_lib_target_2;

        mod foo {
            use dep_lib_target_2::Foo;
                                 //^ dep-lib/lib.rs
        }
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test local item shadows extern crate alias`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        extern crate dep_lib_target as dep_lib;

        mod bar {
            mod dep_lib {
                pub struct Foo;
            }
            use dep_lib::Foo;
                       //^ lib.rs
        }
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test ambiguity of extern crate alias and other item with the same name`() {
        stubOnlyResolve("""
        //- dep-lib/lib.rs
            pub struct Foo;
        //- lib.rs
            extern crate dep_lib_target as dep_lib;

            mod dep_lib {
                pub struct Foo;
            }
            use dep_lib::Foo;
                       //^ unresolved
        """)
    }

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    @ProjectDescriptor(WithStdlibAndDependencyRustProjectDescriptor::class)
    fun `test ambiguity of extern crate alias and prelude item`() = expect<IllegalStateException> {
        stubOnlyResolve("""
        //- dep-lib/lib.rs
            pub struct Ok;
        //- lib.rs
            extern crate dep_lib_target as Result;

            mod foo {
                use Result::Ok;
                           //^ unresolved
            }
        """)
    }

    // Issue https://github.com/intellij-rust/intellij-rust/issues/3846
    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extra use of crate name 1`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        use dep_lib_target;
        use dep_lib_target::Foo;
                          //^ dep-lib/lib.rs
    """, ItemResolutionTestmarks.extraAtomUse)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test "extra use of crate name 1" with alias`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        use dep_lib_target as dep;
        use dep::Foo;
                 //^ dep-lib/lib.rs
    """)

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test extra use of crate name 2`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        pub struct Foo;
    //- lib.rs
        use dep_lib_target::{self};
        use dep_lib_target::Foo;
                          //^ dep-lib/lib.rs
    """, ItemResolutionTestmarks.extraAtomUse)

    // Issue https://github.com/intellij-rust/intellij-rust/issues/3912
    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test star import of item with the same name as extern crate`() = stubOnlyResolve("""
    //- dep-lib/lib.rs
        mod dep_lib_target {}
        pub mod foo {}
    //- lib.rs
        use dep_lib_target::*;
        use dep_lib_target::foo;
                          //^ dep-lib/lib.rs
    """)
}
