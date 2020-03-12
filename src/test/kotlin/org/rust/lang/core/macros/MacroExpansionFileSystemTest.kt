/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.rust.openapiext.fullyRefreshDirectory

class MacroExpansionFileSystemTest : BasePlatformTestCase() {
    fun `test simple`() {
        val vfs = MacroExpansionFileSystem.getInstance().apply {
            createDirectory("/foo")
            createFileWithContent("/foo/bar.txt", "bar content")
            createFileWithContent("/foo/baz.txt", "baz content")
            fullyRefreshDirectory(root)
        }
        val bar = vfs.findNonNullFileByPath("/foo/bar.txt")
        val baz = vfs.findNonNullFileByPath("/foo/baz.txt")
        val parent = bar.parent
        assertEquals("bar content", VfsUtil.loadText(bar))
        assertEquals("baz content", VfsUtil.loadText(baz))
        assertEquals("bar.txt", bar.name)
        assertEquals("foo", parent.name)
        assertEquals(2, parent.children.size)
        assertContainsElements(parent.children.toList(), bar, baz)
        assertEquals(vfs.findNonNullFileByPath("/foo"), parent)
        assertEquals(vfs.findNonNullFileByPath("/foo/"), parent)
        assertEquals(vfs.findNonNullFileByPath("/"), parent.parent)

        vfs.setFileContent("/foo/bar.txt", "new bar content")
        fullyRefreshDirectory(vfs.root)
        assertEquals("new bar content", VfsUtil.loadText(bar))
        assertEquals("baz content", VfsUtil.loadText(baz))

        vfs.deleteFile("/foo/bar.txt")
        fullyRefreshDirectory(vfs.root)
        assertNull(vfs.findFileByPath("/foo/bar.txt"))

        vfs.deleteFile("/foo")
        fullyRefreshDirectory(vfs.root)
        assertNull(vfs.findFileByPath("/foo/baz.txt"))
        assertNull(vfs.findFileByPath("/foo"))
    }

    fun `test mkdirs`() {
        MacroExpansionFileSystem.getInstance().apply {
            createFileWithContent("/foo/bar/baz/quux", "content", mkdirs = true)
            assertEquals("content", VfsUtil.loadText(findFileByPath("/foo/bar/baz/quux")!!))
            fullyRefreshDirectory(root)

        }
    }

    override fun tearDown() {
        try {
            val vfs = MacroExpansionFileSystem.getInstance()
            for (child in vfs.root.children) {
                vfs.deleteFile(child.path)
            }
            fullyRefreshDirectory(vfs.root)
        } finally {
            super.tearDown()
        }
    }
}

private fun VirtualFileSystem.findNonNullFileByPath(path: String): VirtualFile =
    findFileByPath(path) ?: error("File not found: $path")

private val VirtualFileSystem.root: VirtualFile get() = findNonNullFileByPath("/")
