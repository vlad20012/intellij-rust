/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl
import com.intellij.util.BitUtil
import com.intellij.util.io.DigestUtil
import org.rust.openapiext.fileId
import org.rust.stdext.HashCode
import java.io.IOException
import java.security.MessageDigest

object VfsInternals {
    /** [com.intellij.openapi.vfs.newvfs.persistent.PersistentFS.MUST_RELOAD_CONTENT] */
    @VisibleForTesting
    val MUST_RELOAD_CONTENT: Int = 0x08

    /** [com.intellij.openapi.vfs.newvfs.persistent.FSRecords.CONTENT_HASH_DIGEST] */
    private val CONTENT_HASH_DIGEST: MessageDigest = DigestUtil.sha1()

    @VisibleForTesting
    fun isMarkedForContentReload(file: VirtualFile) =
        BitUtil.isSet(PersistentFS.getInstance().getFileAttributes(file.fileId), MUST_RELOAD_CONTENT)

    @VisibleForTesting
    @Throws(IOException::class)
    fun reloadFileIfNeeded(file: VirtualFile) {
        if (isMarkedForContentReload(file)) {
            file.contentsToByteArray(false)
        }
    }

    /** `null` means disabled hashing or invalid file */
    @VisibleForTesting
    fun getContentHashIfStored(file: VirtualFile): HashCode? =
        (PersistentFS.getInstance() as PersistentFSImpl).getContentHashIfStored(file)
            ?.let { HashCode.fromByteArray(it) }

    fun calculateContentHash(fileContent: ByteArray): HashCode =
        HashCode.fromByteArray(DigestUtil.calculateContentHash(CONTENT_HASH_DIGEST, fileContent))

    fun getUpToDateContentHash(file: VirtualFile): ContentHashResult {
        try {
            reloadFileIfNeeded(file) // Re-computes the hash code if the file is changed
        } catch (e: IOException) {
            // See `MacroExpansionFileSystem.xcontentsToByteArray`
            return ContentHashResult.Err(e)
        }

        // `null` means disabled file hashes
        val hash = getContentHashIfStored(file)
        return if (hash == null) ContentHashResult.Disabled else ContentHashResult.Ok(hash)
    }

    sealed class ContentHashResult {
        object Disabled : ContentHashResult()
        class Ok(val hash: HashCode) : ContentHashResult()
        class Err(val error: IOException) : ContentHashResult()
    }
}
