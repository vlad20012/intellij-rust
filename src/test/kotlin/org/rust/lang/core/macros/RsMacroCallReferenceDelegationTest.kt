/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import org.rust.ExpandMacros
import org.rust.lang.core.resolve.RsResolveTestBase

@ExpandMacros
class RsMacroCallReferenceDelegationTest : RsResolveTestBase() {
    fun `test item context`() = checkByCode("""
        struct X;
             //X
        macro_rules! foo { ($($ i:item)*) => { $( $ i )* }; }
        foo! {
            type T = X;
        }          //^
    """)

    fun `test statement context`() = checkByCode("""
        struct X;
             //X
        macro_rules! foo { ($($ i:item)*) => { $( $ i )* }; }
        fn main () {
            foo! {
                type T = X;
            };         //^
        }
    """)

    // TODO adjust type inference to take into account macros
    fun `test expression context`() = expect<IllegalStateException> {
        checkByCode("""
        struct X;
             //X
        macro_rules! foo { ($($ i:tt)*) => { $( $ i )* }; }
        fn main () {
            let a = foo!(X);
        }              //^
    """)
    }

    fun `test type context`() = checkByCode("""
        struct X;
             //X
        macro_rules! foo { ($($ i:tt)*) => { $( $ i )* }; }
        type T = foo!(X);
                    //^
    """)

    // TODO implement `getContext()` in all RsPat PSI elements
    fun `test pattern context`() = expect<IllegalStateException> {
        checkByCode("""
        const X: i32 = 0;
            //X
        macro_rules! foo { ($($ i:tt)*) => { $( $ i )* }; }
        fn main() {
            match 0 {
                foo!(X) => {}
                   //^
                _ => {}
            }
        }
    """)
    }
}
