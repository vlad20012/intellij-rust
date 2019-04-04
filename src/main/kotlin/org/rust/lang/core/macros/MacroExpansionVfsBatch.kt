/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.exists
import org.apache.commons.lang.RandomStringUtils
import org.rust.openapiext.checkWriteAccessAllowed
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

interface MacroExpansionVfsBatch {
    interface Path {
        fun toVirtualFile(): VirtualFile?
    }

    fun resolve(file: VirtualFile): Path

    fun createFileWithContent(content: String): Path
    fun deleteFile(file: Path)
    fun writeFile(file: Path, content: String)

    fun applyToVfs()
}

class LocalFsMacroExpansionVfsBatch(
    private val realFsExpansionContentRoot: Path
) : MacroExpansionVfsBatch {
    private val batch: VfsBatch = RefreshBasedVfsBatch()

    override fun resolve(file: VirtualFile): MacroExpansionVfsBatch.Path =
        PathImpl.VFile(file)

    override fun createFileWithContent(content: String): MacroExpansionVfsBatch.Path =
        PathImpl.NioPath(createFileInternal(content))

    private fun createFileInternal(content: String): String {
        val name = RandomStringUtils.random(16, "0123456789abcdifghijklmnopqrstuvwxyz")
        return batch.run {
            realFsExpansionContentRoot
//                .getOrCreateDirectory(name[0].toString())
//                .getOrCreateDirectory(name[1].toString())
                .createFile(name.substring(2) + ".rs", content)
        }
    }

    override fun deleteFile(file: MacroExpansionVfsBatch.Path) {
        batch.deleteFile((file as PathImpl).toPath())
    }

    override fun writeFile(file: MacroExpansionVfsBatch.Path, content: String) {
        batch.writeFile((file as PathImpl).toPath(), content)
    }

    override fun applyToVfs() {
        batch.applyToVfs()
    }

    private sealed class PathImpl : MacroExpansionVfsBatch.Path {
        abstract fun toPath(): String

        class VFile(val file: VirtualFile): PathImpl() {
            override fun toVirtualFile(): VirtualFile? = file

            override fun toPath(): String = file.path
        }
        class NioPath(val path: String): PathImpl() {
            override fun toVirtualFile(): VirtualFile? =
                SnapshotOnlyVFS.getInstance().findFileByPath(path)

            override fun toPath(): String = path
        }
    }
}

abstract class VfsBatch {
    protected val dirEvents: MutableList<DirCreateEvent> = mutableListOf()
    protected val fileEvents: MutableList<Event> = mutableListOf()

    fun Path.createFile(name: String, content: String): String =
        createFile(this, name, content)

    @JvmName("createFile_")
    private fun createFile(parent: Path, name: String, content: String): String {
//        val child = parent.resolve(name)
//        check(!child.exists())
//        child.createFile()
//        child.write(content) // UTF-8

        val child = "/$name"
        SnapshotOnlyVFS.getInstance().createFileWithContent(child, content)

        fileEvents.add(Event.Create(File(child), content))
        return child
    }

    private fun createDirectory(parent: Path, name: String): Path {
        val child = parent.resolve(name)
        check(!child.exists())
        Files.createDirectory(child)

        dirEvents.add(DirCreateEvent(parent, name))

        return child
    }

    fun Path.getOrCreateDirectory(name: String): Path =
        getOrCreateDirectory(this, name)

    @JvmName("getOrCreateDirectory_")
    private fun getOrCreateDirectory(parent: Path, name: String): Path {
        val child = parent.resolve(name)
        if (child.exists()) {
            return child
        }
        return createDirectory(parent, name)
    }

    fun writeFile(file: String, content: String) {
//        check(file.isFile())
//        file.write(content) // UTF-8
        SnapshotOnlyVFS.getInstance().writeFileContent(file, content)

        fileEvents.add(Event.Write(File(file), content))
    }

    fun deleteFile(file: String) {
//        file.delete()
//
//        fileEvents.add(Event.Delete(file))
    }

    abstract fun applyToVfs()

    protected class DirCreateEvent(val parent: Path, val name: String)

    protected sealed class Event {
        class Create(val file: File, val content: String): Event()
        class Write(val file: File, val content: String): Event()
        class Delete(val file: File): Event()
    }

}

class RefreshBasedVfsBatch : VfsBatch() {
    override fun applyToVfs() {
        checkWriteAccessAllowed()

        if (fileEvents.isNotEmpty() || dirEvents.isNotEmpty()) {
            val files = dirEvents.map { it.toFile().toFile() } + fileEvents.map { it.toFile() }
            LocalFileSystem.getInstance().refreshIoFiles(files, /* async = */ false, /* recursive = */ false, null)
            fileEvents.clear()
        }
    }

    private fun DirCreateEvent.toFile(): Path {
        return parent.resolve(name)
    }

    private fun Event.toFile(): File = when (this) {
        is Event.Create -> file
        is Event.Write -> file
        is Event.Delete -> file
    }
}
