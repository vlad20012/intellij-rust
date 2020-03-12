/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import org.rust.stdext.randomLowercaseAlphabetic

interface MacroExpansionVfsBatch {
    interface Path {
        fun toVirtualFile(): VirtualFile?
    }

    fun resolve(file: VirtualFile): Path

    fun createFileWithContent(content: String, stepNumber: Int): Path
    fun deleteFile(file: VirtualFile)
    fun writeFile(file: VirtualFile, content: String): Path

    fun applyToVfs(async: Boolean, callback: Runnable?)
}

class MacroExpansionVfsBatchImpl(rootDirName: String) : MacroExpansionVfsBatch {
    private val contentRoot = "/$MACRO_EXPANSION_VFS_ROOT/$rootDirName"
    private val batch: VfsBatch = VfsBatch()

    override fun resolve(file: VirtualFile): MacroExpansionVfsBatch.Path =
        PathImpl.VFile(file)

    override fun createFileWithContent(content: String, stepNumber: Int): MacroExpansionVfsBatch.Path =
        PathImpl.StringPath(createFileInternal(content, stepNumber))

    private fun createFileInternal(content: String, stepNumber: Int): String {
        val name = randomLowercaseAlphabetic(16)
        return batch.run {
            contentRoot
                .getOrCreateDirectory(stepNumber.toString())
                .getOrCreateDirectory(name[0].toString())
                .getOrCreateDirectory(name[1].toString())
                .createFile(name.substring(2) + ".rs", content)
        }
    }

    override fun deleteFile(file: VirtualFile) {
        batch.deleteFile(file)
    }

    override fun writeFile(file: VirtualFile, content: String): MacroExpansionVfsBatch.Path {
        batch.writeFile(file, content)
        return resolve(file)
    }

    override fun applyToVfs(async: Boolean, callback: Runnable?) {
        batch.applyToVfs(async, callback)
    }

    private sealed class PathImpl : MacroExpansionVfsBatch.Path {
        abstract fun toPath(): String

        class VFile(val file: VirtualFile): PathImpl() {
            override fun toVirtualFile(): VirtualFile? = file

            override fun toPath(): String = file.path
        }
        class StringPath(val path: String): PathImpl() {
            override fun toVirtualFile(): VirtualFile? =
                MacroExpansionFileSystem.getInstance().findFileByPath(path)

            override fun toPath(): String = path
        }
    }
}

class VfsBatch {
    fun String.createFile(name: String, content: String): String =
        createFile(this, name, content)

    @JvmName("createFile_")
    private fun createFile(parent: String, name: String, content: String): String {
        val child = "$parent/$name"
        MacroExpansionFileSystem.getInstance().createFileWithContent(child, content)
        markDirtyByPath(parent)
        return child
    }

    private fun createDirectory(parent: String, name: String): String {
        val child = "$parent/$name"
        MacroExpansionFileSystem.getInstance().createDirectory(child)
        markDirtyByPath(parent)
        return child
    }

    fun String.getOrCreateDirectory(name: String): String =
        getOrCreateDirectory(this, name)

    @JvmName("getOrCreateDirectory_")
    private fun getOrCreateDirectory(parent: String, name: String): String {
        val child = "$parent/$name"
        if (MacroExpansionFileSystem.getInstance().exists(child)) {
            return child
        }
        return createDirectory(parent, name)
    }

    fun writeFile(file: VirtualFile, content: String) {
        MacroExpansionFileSystem.getInstance().setFileContent(file.path, content)

        markDirty(file)
    }

    fun deleteFile(file: VirtualFile) {
        MacroExpansionFileSystem.getInstance().deleteFile(file.path)

        markDirty(file)
    }

    fun applyToVfs(async: Boolean, callback: Runnable? = null) {
        val root = MacroExpansionFileSystem.getInstance().findFileByPath("/") ?: return
        RefreshQueue.getInstance().refresh(async, true, callback, root)
    }

    private fun markDirty(file: VirtualFile) {
        (file as NewVirtualFile).markDirty()
    }

    private fun markDirtyByPath(path: String) {
        val segments = StringUtil.split(path, "/")

        var file = MacroExpansionFileSystem.getInstance().findFileByPath("/") ?: return
        for (segment in segments) {
            file = file.findChild(segment) ?: break
        }

        markDirty(file)
    }
}
