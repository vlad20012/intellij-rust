/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustSlowTests

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.util.LocalTimeCounter
import org.rust.RsTestBase
import org.rust.WithStdlibRustProjectDescriptor
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.stubs.RsFileStub
import org.rustPerformanceTests.fullyRefreshDirectoryInUnitTests

class RsLazyCodeBlockIsNotExpandedDuringStubBuildTest : RsTestBase() {
    override fun getProjectDescriptor() = WithStdlibRustProjectDescriptor

    fun `test stdlib source`() {
        val sources = rustSrcDir()
        checkRustFiles(
            sources,
            ignored = setOf("tests", "test", "doc", "etc", "grammar")
        )
    }

    private fun checkRustFiles(directory: VirtualFile, ignored: Collection<String>) {
        val files = collectRustFiles(directory, ignored)

        var numBlocks = 0
        var numParsedBlocks = 0

        for (psi in files) {
            val blocks = SyntaxTraverser.psiTraverser(psi).expand { it !is RsBlock }.filterIsInstance<RsBlock>()

            blocks.forEach {
                check(it.parent !is RsFunction || !it.isParsed) {
                    "Expected NOT parsed block: `${it.text}`"
                }
            }

            RsFileStub.Type.getBuilder().buildStubTree(psi)

            for (block in blocks) {
                if (block.parent !is RsFunction) continue
                numBlocks++
                val blockIsParsed = block.isParsed

                val stubbedElements = block.descendantsOfType<StubBasedPsiElement<*>>()
                    .filter { it.elementType.shouldCreateStub(it.node) }
                val containsStubbedElements = stubbedElements.isNotEmpty()

                // Macros can contain any tokens, so our heuristic can give false-positives; allow them
                val parsingAllowed = containsStubbedElements ||
                    block.descendantsOfType<RsMacroCall>().isNotEmpty() ||
                    block.descendantsOfType<RsMacro>().isNotEmpty() ||
                    block.descendantsOfType<RsMacro2>().isNotEmpty()

                if (blockIsParsed) {
                    numParsedBlocks++
                    check(parsingAllowed) {
                        "Expected NOT parsed block after stub tree building: `${block.text}`"
                    }
                } else {
                    check(!containsStubbedElements) {
                        "Expected PARSED block after stub tree building: `${block.text}`\n" +
                            "Because it contains elements that should be stubbed: " +
                            "${stubbedElements.joinToString { "`${it.text}`" }}"
                    }
                }
            }
        }

        println("Blocks: $numBlocks, parsed: $numParsedBlocks (${numParsedBlocks * 100 / numBlocks}%)")
    }

    private fun collectRustFiles(directory: VirtualFile, ignored: Collection<String>): List<RsFile> {
        val files = mutableListOf<RsFile>()
        fullyRefreshDirectoryInUnitTests(directory)
        VfsUtilCore.visitChildrenRecursively(directory, object : VirtualFileVisitor<Void>() {
            override fun visitFileEx(file: VirtualFile): Result {
                if (file.isDirectory && file.name in ignored) return SKIP_CHILDREN
                if (file.fileType != RsFileType) return CONTINUE
                val fileContent = String(file.contentsToByteArray())

                val psi = PsiFileFactory.getInstance(project).createFileFromText(
                    file.name,
                    file.fileType,
                    fileContent,
                    LocalTimeCounter.currentTime(),
                    true,
                    false
                )

                if (psi is RsFile) {
                    files += psi
                }

                return CONTINUE
            }
        })
        return files
    }

    private val RsBlock.isParsed get() = (node as LazyParseableElement).isParsed

    private fun rustSrcDir(): VirtualFile = projectDescriptor.stdlib!!
}
