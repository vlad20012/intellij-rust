/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.openapiext

import com.intellij.concurrency.SensitiveProgressWrapper
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.TrailingSpacesStripper
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.*
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.reference.SoftReference
import org.jdom.Element
import org.jdom.input.SAXBuilder
import org.rust.cargo.RustfmtWatcher
import org.rust.ide.annotator.RsExternalLinterPass
import java.lang.reflect.Field
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty

fun <T> Project.runWriteCommandAction(command: () -> T): T {
    return WriteCommandAction.runWriteCommandAction(this, Computable<T> { command() })
}

val Project.modules: Collection<Module>
    get() = ModuleManager.getInstance(this).modules.toList()


fun <T> recursionGuard(key: Any, block: Computable<T>, memoize: Boolean = true): T? =
    RecursionManager.doPreventingRecursion(key, memoize, block)


fun checkWriteAccessAllowed() {
    check(ApplicationManager.getApplication().isWriteAccessAllowed) {
        "Needs write action"
    }
}

fun checkReadAccessAllowed() {
    check(ApplicationManager.getApplication().isReadAccessAllowed) {
        "Needs read action"
    }
}

fun checkReadAccessNotAllowed() {
    check(!ApplicationManager.getApplication().isReadAccessAllowed)
}

fun checkIsDispatchThread() {
    check(ApplicationManager.getApplication().isDispatchThread) {
        "Should be invoked on the Swing dispatch thread"
    }
}

fun checkIsBackgroundThread() {
    check(!ApplicationManager.getApplication().isDispatchThread) {
        "Long running operation invoked on UI thread"
    }
}

fun checkIsSmartMode(project: Project) {
    if (DumbService.getInstance(project).isDumb) throw IndexNotReadyException.create()
}

fun fullyRefreshDirectory(directory: VirtualFile) {
    VfsUtil.markDirtyAndRefresh(/* async = */ false, /* recursive = */ true, /* reloadChildren = */ true, directory)
}

fun VirtualFile.findFileByMaybeRelativePath(path: String): VirtualFile? =
    if (FileUtil.isAbsolute(path))
        fileSystem.findFileByPath(path)
    else
        findFileByRelativePath(path)

val VirtualFile.pathAsPath: Path get() = Paths.get(path)

fun VirtualFile.toPsiFile(project: Project): PsiFile? =
    PsiManager.getInstance(project).findFile(this)

fun VirtualFile.toPsiDirectory(project: Project): PsiDirectory? =
    PsiManager.getInstance(project).findDirectory(this)

val Document.virtualFile: VirtualFile?
    get() = FileDocumentManager.getInstance().getFile(this)

val VirtualFile.document: Document?
    get() = FileDocumentManager.getInstance().getDocument(this)

inline fun <Key, reified Psi : PsiElement> getElements(
    indexKey: StubIndexKey<Key, Psi>,
    key: Key, project: Project,
    scope: GlobalSearchScope?
): Collection<Psi> =
    StubIndex.getElements(indexKey, key, project, scope, Psi::class.java)


fun Element.toXmlString() = JDOMUtil.writeElement(this)
fun elementFromXmlString(xml: String): org.jdom.Element =
    SAXBuilder().build(xml.byteInputStream()).rootElement

class CachedVirtualFile(private val url: String?) {
    private val cache = AtomicReference<VirtualFile>()

    operator fun getValue(thisRef: Any?, property: KProperty<*>): VirtualFile? {
        if (url == null) return null
        val cached = cache.get()
        if (cached != null && cached.isValid) return cached
        val file = VirtualFileManager.getInstance().findFileByUrl(url)
        cache.set(file)
        return file
    }
}

val isUnitTestMode: Boolean get() = ApplicationManager.getApplication().isUnitTestMode

fun saveAllDocuments() = FileDocumentManager.getInstance().saveAllDocuments()

/**
 * Calling of [saveAllDocuments] uses [TrailingSpacesStripper] to format all unsaved documents.
 *
 * In case of [RsExternalLinterPass] it backfires:
 * 1. Calling [TrailingSpacesStripper.strip] on *every* file change.
 * 2. Double run of external linter, because [TrailingSpacesStripper.strip] generates new "PSI change" events.
 *
 * This function saves all documents "as they are" (see [FileDocumentManager.saveDocumentAsIs]), but also fires that
 * these documents should be stripped later (when [saveAllDocuments] is called).
 */
fun saveAllDocumentsAsTheyAre(reformatLater: Boolean = true) {
    val documentManager = FileDocumentManager.getInstance()
    val rustfmtWatcher = RustfmtWatcher.getInstance()
    rustfmtWatcher.withoutReformatting {
        for (document in documentManager.unsavedDocuments) {
            documentManager.saveDocumentAsIs(document)
            documentManager.stripDocumentLater(document)
            if (reformatLater) rustfmtWatcher.reformatDocumentLater(document)
        }
    }
}

private fun FileDocumentManager.stripDocumentLater(document: Document): Boolean {
    if (this !is FileDocumentManagerImpl) return false
    val trailingSpacesStripper = trailingSpacesStripperField
        ?.get(this) as? TrailingSpacesStripper ?: return false
    @Suppress("UNCHECKED_CAST")
    val documentsToStripLater = documentsToStripLaterField
        ?.get(trailingSpacesStripper) as? MutableSet<Document> ?: return false
    return documentsToStripLater.add(document)
}

private val trailingSpacesStripperField: Field? =
    initFieldSafely<FileDocumentManagerImpl>("myTrailingSpacesStripper")

private val documentsToStripLaterField: Field? =
    initFieldSafely<TrailingSpacesStripper>("myDocumentsToStripLater")

private inline fun <reified T> initFieldSafely(fieldName: String): Field? =
    try {
        T::class.java
            .getDeclaredField(fieldName)
            .apply { isAccessible = true }
    } catch (e: Throwable) {
        if (isUnitTestMode) throw e else null
    }

inline fun testAssert(action: () -> Boolean) {
    testAssert(action) { "Assertion failed" }
}

inline fun testAssert(action: () -> Boolean, lazyMessage: () -> Any) {
    if (isUnitTestMode && !action()) {
        val message = lazyMessage()
        throw AssertionError(message)
    }
}

fun <T> runWithCheckCanceled(callable: () -> T): T =
    ApplicationUtil.runWithCheckCanceled(callable, ProgressManager.getInstance().progressIndicator)

fun <T> Project.computeWithCancelableProgress(title: String, supplier: () -> T): T {
    if (isUnitTestMode) {
        return supplier()
    }
    return ProgressManager.getInstance().runProcessWithProgressSynchronously<T, Exception>(supplier, title, true, this)
}

inline fun <T> UserDataHolderEx.getOrPut(key: Key<T>, defaultValue: () -> T): T =
    getUserData(key) ?: putUserDataIfAbsent(key, defaultValue())

inline fun <T> UserDataHolderEx.getOrPutSoft(key: Key<SoftReference<T>>, defaultValue: () -> T): T =
    getUserData(key)?.get() ?: run {
        val value = defaultValue()
        putUserDataIfAbsent(key, SoftReference(value)).get() ?: value
    }

const val PLUGIN_ID: String = "org.rust.lang"

fun plugin(): IdeaPluginDescriptor = PluginManager.getPlugin(PluginId.getId(PLUGIN_ID))!!

val String.escaped: String get() = StringUtil.escapeXmlEntities(this)

fun <T> runReadActionInSmartMode(project: Project, action: () -> T): T {
    ProgressManager.checkCanceled()
    if (project.isDisposed) throw ProcessCanceledException()
    return DumbService.getInstance(project).runReadActionInSmartMode(Computable {
        ProgressManager.checkCanceled()
        action()
    })
}

fun <T : Any> executeUnderProgressWithWriteActionPriorityWithRetries(indicator: ProgressIndicator, action: () -> T): T {
    checkReadAccessNotAllowed()
    var result: T? = null
    do {
        val success = runWithWriteActionPriority(SensitiveProgressWrapper(indicator)) {
            result = action()
        }
        if (!success) {
            indicator.checkCanceled()
            // wait for write action to complete
            ApplicationManager.getApplication().runReadAction(EmptyRunnable.getInstance())
        }
    } while (!success)
    return result!!
}

fun runWithWriteActionPriority(indicator: ProgressIndicator, action: () -> Unit): Boolean =
    ProgressIndicatorUtils.runWithWriteActionPriority(action, indicator)

fun submitTransaction(parentDisposable: Disposable, runnable: Runnable) {
    ApplicationManager.getApplication().invokeLater(Runnable {
        TransactionGuard.submitTransaction(parentDisposable, runnable)
    }, ModalityState.any(), Condition.FALSE)
}

class TransactionExecutor(val project: Project) : Executor {
    override fun execute(command: Runnable) {
        submitTransaction(project, command)
    }
}

fun <T> executeUnderProgress(indicator: ProgressIndicator, action: () -> T): T {
    var result: T? = null
    ProgressManager.getInstance().executeProcessUnderProgress({ result = action() }, indicator)
    @Suppress("UNCHECKED_CAST")
    return result ?: (null as T)
}

fun <T : PsiElement> T.createSmartPointer(): SmartPsiElementPointer<T> =
    SmartPointerManager.getInstance(project).createSmartPsiElementPointer(this)
