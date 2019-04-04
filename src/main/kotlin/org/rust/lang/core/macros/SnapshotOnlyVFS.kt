/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.util.io.FileAttributes
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.impl.local.LocalFileSystemBase
import com.intellij.openapi.vfs.newvfs.impl.FakeVirtualFile
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.util.LocalTimeCounter
import com.intellij.util.containers.ContainerUtil
import java.io.*

class SnapshotOnlyVFS : LocalFileSystemBase() {
    companion object {
        private const val PROTOCOL: String = "snapshot"
        fun getInstance(): SnapshotOnlyVFS {
            return VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as SnapshotOnlyVFS
        }
    }

    private val tempVals: MutableMap<String, TempVals> = ContainerUtil.newConcurrentMap()
    private data class TempVals(
        val length: Int,
        val timeStamp: Long
    )

    override fun replaceWatchedRoots(
        watchRequests: Collection<WatchRequest>,
        recursiveRoots: Collection<String>?,
        flatRoots: Collection<String>?
    ): MutableSet<WatchRequest> {
        error("")
    }

    override fun extractRootPath(path: String): String {
        return "/"
    }

    override fun getProtocol(): String {
        return PROTOCOL
    }

    @Throws(IOException::class)
    override fun copyFile(requestor: Any?,
                          file: VirtualFile,
                          newParent: VirtualFile,
                          copyName: String): VirtualFile {
        return VfsUtilCore.copyFile(requestor, file, newParent, copyName)
    }

    override fun deleteFile(requestor: Any?, file: VirtualFile) {

    }

    @Throws(IOException::class)
    override fun moveFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile) {
    }

    override fun renameFile(requestor: Any?, file: VirtualFile, newName: String) {

    }

    override fun exists(fileOrDirectory: VirtualFile): Boolean {
        return PersistentFS.getInstance().exists(fileOrDirectory)
    }

    override fun list(file: VirtualFile): Array<String> {
        return PersistentFS.getInstance().listPersisted(file)
    }

    override fun getCanonicallyCasedName(file: VirtualFile): String {
        return file.name
    }

    override fun isDirectory(file: VirtualFile): Boolean {
        return PersistentFS.getInstance().isDirectory(file)
    }

    override fun getTimeStamp(file: VirtualFile): Long {
        return PersistentFS.getInstance().getTimeStamp(file)
    }

    override fun setTimeStamp(file: VirtualFile, timeStamp: Long) {
        tempVals[file.path]?.let { tempVals[file.path] = it.copy(timeStamp = timeStamp) }
    }

    override fun isWritable(file: VirtualFile): Boolean {
        return true
    }

    override fun setWritable(file: VirtualFile, writableFlag: Boolean) {
    }

    @Throws(IOException::class)
    override fun contentsToByteArray(file: VirtualFile): ByteArray {
        if (!exists(file)) throw FileNotFoundException()
        return byteArrayOf()
    }

    @Throws(IOException::class)
    override fun getInputStream(file: VirtualFile): InputStream {
        error("")
    }

    @Throws(IOException::class)
    override fun getOutputStream(file: VirtualFile,
                                 requestor: Any?,
                                 modStamp: Long,
                                 timeStamp: Long): OutputStream {
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                tempVals[file.path] = TempVals(buf.size, if (modStamp > 0) modStamp else LocalTimeCounter.currentTime())
            }
        }
    }

    override fun getLength(file: VirtualFile): Long {
        return tempVals[file.path]?.length?.toLong() ?: PersistentFS.getInstance().getLastRecordedLength(file)
    }

    override fun getAttributes(file: VirtualFile): FileAttributes? {
        if (file.path == "/") return FileAttributes(true, false, false, false, 0, 0, true)
        if (file is FakeVirtualFile) return FileAttributes(!file.name.contains('.'), false, false, false, 0, 0, true)
        if (file !is VirtualFileWithId) return null
        val temp = tempVals[file.path]
        val attrs = PersistentFS.getInstance().getFileAttributes((file as VirtualFileWithId).id)
        return FileAttributes(
            PersistentFS.isDirectory(attrs),
            PersistentFS.isSpecialFile(attrs),
            PersistentFS.isSymLink(attrs),
            PersistentFS.isHidden(attrs),
            getLength(file),
            temp?.timeStamp ?: 0,
            true
        )
    }
}
