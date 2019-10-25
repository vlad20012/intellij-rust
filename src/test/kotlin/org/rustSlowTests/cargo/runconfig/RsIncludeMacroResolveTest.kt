/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustSlowTests.cargo.runconfig

import org.rust.MinRustcVersion
import org.rust.lang.core.psi.RsPath

@MinRustcVersion("1.32.0")
class RsIncludeMacroResolveTest : RunConfigurationTestBase() {

    fun `test include in workspace project`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "intellij-rust-test"
                version = "0.1.0"
                authors = []
            """)
            rust("build.rs", """
                use std::env;
                use std::fs::File;
                use std::io::Write;
                use std::path::Path;

                fn main() {
                    let out_dir = env::var("OUT_DIR").unwrap();
                    let dest_path = Path::new(&out_dir).join("hello.rs");
                    let mut f = File::create(&dest_path).unwrap();

                    f.write_all(b"
                        pub fn message() -> &'static str {
                            \"Hello, World!\"
                        }",
                    ).unwrap();
                }
            """)
            dir("src") {
                rust("main.rs", """
                    include!(concat!(env!("OUT_DIR"), "/hello.rs"));

                    fn main() {
                        println!("{}", message());
                                        //^
                    }
                """)
            }
        }
        buildProject()

        runWithInvocationEventsDispatching("Failed to resolve the reference") {
            testProject.findElementInFile<RsPath>("src/main.rs").reference.resolve() != null
        }
    }

    fun `test include in dependency`() {
        val testProject = buildProject {
            toml("Cargo.toml", """
                [package]
                name = "intellij-rust-test"
                version = "0.1.0"
                authors = []

                [dependencies]
                code-generation-example = "0.1.0"
            """)
            dir("src") {
                rust("lib.rs", """
                    fn main() {
                        println!("{}", code_generation_example::message());
                                                               //^
                    }
                """)
            }
        }
        buildProject()
        runWithInvocationEventsDispatching("Failed to resolve the reference") {
            testProject.findElementInFile<RsPath>("src/lib.rs").reference.resolve() != null
        }
    }
}