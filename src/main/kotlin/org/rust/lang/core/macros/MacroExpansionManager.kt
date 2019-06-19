/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.AppTopics
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.*
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.progress.*
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.progress.impl.ProgressManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.PairConsumer
import com.intellij.util.concurrency.QueueProcessor
import com.intellij.util.concurrency.QueueProcessor.ThreadToUse
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.IndexableFileSet
import com.intellij.util.io.createDirectories
import com.intellij.util.io.delete
import com.intellij.util.io.exists
import org.apache.commons.lang.RandomStringUtils
import org.jetbrains.annotations.TestOnly
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.ide.search.RsWithMacrosScope
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsPsiTreeChangeEvent.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.indexes.RsMacroCallIndex
import org.rust.openapiext.*
import org.rust.stdext.ThreadLocalDelegate
import org.rust.stdext.cleanDirectory
import org.rust.stdext.waitForWithCheckCanceled
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.*

interface MacroExpansionManager {
    val indexableDirectory: VirtualFile?
    fun ensureUpToDate()
    fun getExpansionFor(call: RsMacroCall): MacroExpansion?
    fun getExpandedFrom(element: RsExpandedElement): RsMacroCall?
    fun isExpansionFile(file: VirtualFile): Boolean
    fun reexpand()

    var isResolvingMacro: Boolean
    val macroExpansionMode: MacroExpansionMode

    @TestOnly
    fun setUnitTestExpansionModeAndDirectory(mode: MacroExpansionScope, cacheDirectory: String = ""): Disposable

    @TestOnly
    fun unbindPsi()
}

inline fun MacroExpansionManager.withResolvingMacro(action: () -> Boolean): Boolean {
    isResolvingMacro = true
    try {
        if (action()) return true
    } finally {
        isResolvingMacro = false
    }
    return false
}

val MACRO_LOG = Logger.getInstance("rust.macros")
private const val RUST_EXPANDED_MACROS = "rust_expanded_macros"

fun getBaseMacroExpansionDir(): Path =
    Paths.get(PathManager.getSystemPath()).resolve(RUST_EXPANDED_MACROS)

@State(name = "MacroExpansionManager", storages = [
    Storage(StoragePathMacros.WORKSPACE_FILE),
    Storage("misc.xml", deprecated = true)
])
class MacroExpansionManagerImpl(
    val project: Project
) : MacroExpansionManager,
    ProjectComponent,
    PersistentStateComponent<MacroExpansionManagerImpl.PersistentState> {

    data class PersistentState(var directoryName: String? = null)

    private var dirs: Dirs? = null
    private var innerFuture: Future<MacroExpansionServiceImplInner?>? = null
    private val inner: MacroExpansionServiceImplInner? get() = innerFuture?.waitForWithCheckCanceled()

    override fun getState(): PersistentState =
        PersistentState(inner?.save())

    override fun loadState(state: PersistentState) {
        // initialized manually at setUnitTestExpansionModeAndDirectory
        if (isUnitTestMode) return
        dirs = updateDirs(state.directoryName)
    }

    override fun noStateLoaded() {
        loadState(PersistentState(null))
    }

    override fun projectOpened() {
        if (isUnitTestMode) return // initialized manually at setUnitTestExpansionModeAndDirectory

        innerFuture = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            val impl = MacroExpansionServiceImplInner.build(project, dirs!!)
            runReadAction {
                if (project.isDisposed) return@runReadAction null
                impl.projectOpened()
                impl
            }
        })
    }

    override val indexableDirectory: VirtualFile?
        get() = dirs?.expansionsDirVi

    override fun ensureUpToDate() {
        inner?.ensureUpToDate()
    }

    override fun getExpansionFor(call: RsMacroCall): MacroExpansion? {
        val impl = inner
        return when {
            call.macroName == "include" -> expandIncludeMacroCall(call)
            impl != null -> impl.getExpansionFor(call)
            isUnitTestMode -> expandMacroOld(call)
            else -> null
        }
    }

    private fun expandIncludeMacroCall(call: RsMacroCall): MacroExpansion? {
        val includingFile = call.findIncludingFile() ?: return null
        val items = includingFile.stubChildrenOfType<RsExpandedElement>()
        return MacroExpansion.Items(includingFile, items)
    }

    override fun getExpandedFrom(element: RsExpandedElement): RsMacroCall? {
        // For in-memory expansions
        element.getUserData(RS_EXPANSION_MACRO_CALL)?.let { return it as RsMacroCall }

        val inner = inner
        return if (inner != null && inner.isExpansionModeNew) {
            inner.getExpandedFrom(element)
        } else {
            null
        }
    }

    override fun isExpansionFile(file: VirtualFile): Boolean =
        inner?.isExpansionFile(file) == true

    override fun reexpand() {
        inner?.reexpand()
    }

    override var isResolvingMacro: Boolean
        get() = inner?.isResolvingMacro != false
        set(value) {
            inner?.isResolvingMacro = value
        }

    override val macroExpansionMode: MacroExpansionMode
        get() = inner?.expansionMode ?: MacroExpansionMode.OLD

    override fun setUnitTestExpansionModeAndDirectory(mode: MacroExpansionScope, cacheDirectory: String): Disposable {
        check(isUnitTestMode)
        val dir = updateDirs(if (cacheDirectory.isNotEmpty()) cacheDirectory else null)
        val impl = MacroExpansionServiceImplInner.build(project, dir)
        this.dirs = dir
        this.innerFuture = CompletableFuture.completedFuture(impl)
        impl.macroExpansionMode = mode
        val saveCacheOnDispose = cacheDirectory.isNotEmpty()
        val disposable = impl.setupForUnitTests(saveCacheOnDispose)
        Disposer.register(disposable, Disposable {
            this.innerFuture = null
            this.dirs = null
        })
        return disposable
    }

    override fun unbindPsi() {
        inner?.unbindPsi()
    }

    object Testmarks {
        val stubBasedRebind = Testmark("stubBasedRebind")
        val hashBasedRebind = Testmark("hashBasedRebind")
        val hashBasedRebindExactHit = Testmark("hashBasedRebindExactHit")
        val hashBasedRebindCallHit = Testmark("hashBasedRebindCallHit")
        val hashBasedRebindNotHit = Testmark("hashBasedRebindNotHit")
    }
}

private fun updateDirs(projectDirName: String?): Dirs {
    return updateDirs0(projectDirName ?: RandomStringUtils.randomAlphabetic(8))
}

private fun updateDirs0(projectDirName: String): Dirs {
    val baseProjectDir = getBaseMacroExpansionDir().resolve(projectDirName)
        .also { it.createDirectories() }
    val expansionsDir = baseProjectDir.resolve("expansions")
        .also { it.createDirectories() }
    return Dirs(
        baseProjectDir,
        baseProjectDir.resolve("data.dat"),
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(expansionsDir.toFile())!!
    )
}

private data class Dirs(
    val baseProjectDir: Path,
    val dataFile: Path,
    val expansionsDirVi: VirtualFile
)

private class MacroExpansionServiceImplInner(
    private val project: Project,
    val dirs: Dirs,
    private val storage: ExpandedMacroStorage
) {
    companion object {
        fun build(project: Project, dirs: Dirs): MacroExpansionServiceImplInner {
            val dataFile = dirs.dataFile
            val storage = ExpandedMacroStorage.load(project, dataFile) ?: run {
                MACRO_LOG.debug("Using fresh ExpandedMacroStorage")
                ExpandedMacroStorage(project)
            }
            return MacroExpansionServiceImplInner(project, dirs, storage)
        }
    }

    private val taskQueue = MacroExpansionTaskQueue(project)

    /**
     * We must use a separate pool because:
     * 1. [ForkJoinPool.commonPool] is heavily used by the platform
     * 2. [ForkJoinPool] can start execute a task when joining ([ForkJoinTask.get]) another task
     * 3. the platform sometimes join ForkJoinTasks under read lock
     * 4. for macro expansion it's critically important that tasks are executed without read lock.
     *
     * In short, use of [ForkJoinPool.commonPool] in this place leads to crashes.
     * See [issue](https://github.com/intellij-rust/intellij-rust/issues/3966)
     */
    private val pool = Executors.newWorkStealingPool()

    private val dataFile: Path
        get() = dirs.dataFile

    @TestOnly
    var macroExpansionMode: MacroExpansionScope = MacroExpansionScope.NONE

    var isResolvingMacro: Boolean by ThreadLocalDelegate { false }

    fun isExpansionFile(file: VirtualFile): Boolean =
        VfsUtil.isAncestor(dirs.expansionsDirVi, file, true)

    fun save(): String {
        ExpandedMacroStorage.saveStorage(storage, dataFile) // TODO async
        return dirs.baseProjectDir.fileName.toString()
    }

    private fun cleanMacrosDirectory() {
        taskQueue.run(object : Task.Backgroundable(project, "Cleaning outdated macros", false) {
            override fun run(indicator: ProgressIndicator) {
                checkReadAccessNotAllowed()
                dirs.expansionsDirVi.pathAsPath.cleanDirectory()
                dirs.dataFile.delete()
                WriteAction.runAndWait<Throwable> {
                    VfsUtil.markDirtyAndRefresh(false, true, true, dirs.expansionsDirVi)
                    storage.clear()
                    if (!project.isDisposed) {
                        project.rustPsiManager.incRustStructureModificationCount()
                    }
                }
            }
        })
    }

    private fun checkStorageConsistency() {
        taskQueue.run(object : Task.Backgroundable(project, "Cleaning outdated macros", false) {
            override fun run(indicator: ProgressIndicator) {
                checkReadAccessNotAllowed()

                refreshExpansionDirectory()
                findAndDeleteLeakedExpansionFiles()
                findAndRemoveInvalidExpandedMacroInfosFromStorage()
            }

            private fun refreshExpansionDirectory() {
                val latch = CountDownLatch(1)
                LocalFileSystem.getInstance().refreshFiles(listOf(dirs.expansionsDirVi), true, true) {
                    latch.countDown()
                }
                latch.await()
            }

            private fun findAndDeleteLeakedExpansionFiles() {
                val toDelete = mutableListOf<VirtualFile>()
                runReadAction {
                    VfsUtil.iterateChildrenRecursively(dirs.expansionsDirVi, null, ContentIterator {
                        if (!it.isDirectory && storage.getInfoForExpandedFile(it) == null) {
                            toDelete += it
                        }
                        true
                    })
                }
                if (toDelete.isNotEmpty()) {
                    WriteAction.runAndWait<Throwable> {
                        toDelete.forEach { it.delete(null) }
                    }
                }
            }

            private fun findAndRemoveInvalidExpandedMacroInfosFromStorage() {
                val toRemove = mutableListOf<ExpandedMacroInfo>()
                runReadAction {
                    storage.processExpandedMacroInfos { info ->
                        if (info.expansionFile != null && !info.expansionFile.isValid) {
                            toRemove.add(info)
                        }
                    }
                }
                if (toRemove.isNotEmpty()) {
                    WriteAction.runAndWait<Throwable> {
                        toRemove.forEach { storage.removeInvalidInfo(it, true) }
                    }
                }
            }
        })
    }

    fun projectOpened() {
        check(!isUnitTestMode) // initialized manually at setUnitTestExpansionModeAndDirectory
        setupListeners()

        if (project.cargoProjects.hasAtLeastOneValidProject) {
            processUnprocessedMacros()
        }
    }

    private fun setupListeners(disposable: Disposable? = null) {
        if (storage.isEmpty) {
            cleanMacrosDirectory()
        } else {
            checkStorageConsistency()
        }

        run {
            // Some experimental builds stored expansion to this directory
            // TODO remove it someday
            val oldDirPath = Paths.get(PathManager.getTempPath()).resolve("rust_expanded_macros")
            if (oldDirPath.exists()) {
                oldDirPath.delete()
                ApplicationManager.getApplication().invokeLater {
                    runWriteAction {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(oldDirPath.toFile())
                    }
                }
            }
        }

        val treeChangeListener = ChangedMacroUpdater()
        if (disposable != null) {
            PsiManager.getInstance(project).addPsiTreeChangeListener(treeChangeListener, disposable)
        } else {
            PsiManager.getInstance(project).addPsiTreeChangeListener(treeChangeListener)
        }
        ApplicationManager.getApplication().addApplicationListener(treeChangeListener, disposable ?: project)

        val indexableSet = object : IndexableFileSet {
            override fun isInSet(file: VirtualFile): Boolean =
                isExpansionFile(file)

            override fun iterateIndexableFilesIn(file: VirtualFile, iterator: ContentIterator) {
                VfsUtilCore.visitChildrenRecursively(file, object : VirtualFileVisitor<Any>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (!isInSet(file)) {
                            return false
                        }

                        if (!file.isDirectory) {
                            iterator.processFile(file)
                        }

                        return true
                    }
                })
            }
        }

        val index = FileBasedIndex.getInstance()
        index.registerIndexableSet(indexableSet, project)
        if (disposable != null) {
            Disposer.register(disposable, Disposable { index.removeIndexableSet(indexableSet) })
        }

        val connect = if (disposable != null) project.messageBus.connect(disposable) else project.messageBus.connect()

        connect.subscribe(AppTopics.FILE_DOCUMENT_SYNC, MyFileDocumentManagerListener())

        connect.subscribe(CargoProjectsService.CARGO_PROJECTS_TOPIC, object : CargoProjectsService.CargoProjectsListener {
            override fun cargoProjectsUpdated(projects: Collection<CargoProject>) {
                if (!isExpansionModeNew) {
                    cleanMacrosDirectory()
                }
                processUnprocessedMacros()
            }
        })

        connect.subscribe(RUST_STRUCTURE_CHANGE_TOPIC, treeChangeListener)
    }

    private enum class ChangedMacrosScope { NONE, WORKSPACE, ALL }

    private operator fun ChangedMacrosScope.plus(other: ChangedMacrosScope): ChangedMacrosScope =
        if (ordinal > other.ordinal) this else other

    private inner class ChangedMacroUpdater : RsPsiTreeChangeAdapter(),
                                              RustStructureChangeListener,
                                              ApplicationListener {

        private var shouldProcessChangedMacrosOnWriteActionFinish: ChangedMacrosScope = ChangedMacrosScope.NONE

        override fun handleEvent(event: RsPsiTreeChangeEvent) {
            if (!isExpansionModeNew) return
            val file = event.file as? RsFile ?: return
            val virtualFile = file.virtualFile ?: return
            if (virtualFile !is VirtualFileWithId || isExpansionFile(virtualFile)) return

            val element = when (event) {
                is ChildAddition.After -> event.child
                is ChildReplacement.After -> event.newChild
                is ChildrenChange.After -> if (!event.isGenericChange) event.parent else return
                else -> return
            }

            if (element.stubDescendantOfTypeOrSelf<RsMacroCall>() != null) {
                taskQueue.runSimple {
                    runWriteAction {
                        val sf = storage.getOrCreateSourceFile(file.virtualFile) ?: return@runWriteAction
                        sf.invalidateStubIndices()
                        scheduleChangedMacrosUpdate(file.isWorkspaceMember())
                    }
                }
            }
        }

        override fun rustStructureChanged(file: PsiFile?, changedElement: PsiElement?) {
            if (!isExpansionModeNew) return
            if (file is RsFile && changedElement != null && file.virtualFile?.let { isExpansionFile(it) } == false) {
                val isWorkspace = file.isWorkspaceMember()
                scheduleChangedMacrosUpdate(isWorkspace)
            }
        }

        override fun writeActionFinished(action: Any) {
            when (shouldProcessChangedMacrosOnWriteActionFinish) {
                ChangedMacrosScope.NONE -> Unit
                ChangedMacrosScope.WORKSPACE -> processChangedMacros(true)
                ChangedMacrosScope.ALL -> processChangedMacros(false)
            }
            shouldProcessChangedMacrosOnWriteActionFinish = ChangedMacrosScope.NONE
        }

        private fun scheduleChangedMacrosUpdate(workspaceOnly: Boolean) {
            shouldProcessChangedMacrosOnWriteActionFinish += if (workspaceOnly) ChangedMacrosScope.WORKSPACE else ChangedMacrosScope.ALL
        }
    }

    private inner class MyFileDocumentManagerListener : FileDocumentManagerListener {
        override fun beforeDocumentSaving(document: Document) {
            if (!isExpansionModeNew) return
            val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return
            if (virtualFile.fileType != RsFileType) return
            if (isExpansionFile(virtualFile)) return

            storage.getSourceFile(virtualFile)?.updateStubIndices()
        }
    }

    private fun RsFile.isWorkspaceMember(): Boolean {
        // Must be dumb-aware
        val pkg = project.cargoProjects.findPackageForFile(virtualFile ?: return false)
        return pkg?.origin == PackageOrigin.WORKSPACE
    }

    fun reexpand() {
        cleanMacrosDirectory()
        processUnprocessedMacros()
    }

    val expansionMode: MacroExpansionMode
        get() {
            return if (isUnitTestMode) {
                MacroExpansionMode.New(macroExpansionMode)
            } else {
                project.rustSettings.macroExpansionEngine.toMode()
            }
        }

    val isExpansionModeNew: Boolean
        get() = expansionMode is MacroExpansionMode.New

    private fun processUnprocessedMacros() {
        MACRO_LOG.trace("processUnprocessedMacros")
        if (!isExpansionModeNew) return
        class ProcessUnprocessedMacrosTask : MacroExpansionTaskBase(
            project,
            storage,
            pool,
            dirs.expansionsDirVi.pathAsPath
        ) {
            override fun getMacrosToExpand(): Sequence<List<Extractable>> {
                val mode = expansionMode

                val scope = when (mode.toScope()) {
                    MacroExpansionScope.ALL -> GlobalSearchScope.allScope(project)
                    MacroExpansionScope.WORKSPACE -> GlobalSearchScope.projectScope(project)
                    MacroExpansionScope.NONE -> return emptySequence() // GlobalSearchScope.EMPTY_SCOPE
                }

                val calls = runReadActionInSmartMode(project) {
                    val calls = RsMacroCallIndex.getMacroCalls(project, RsWithMacrosScope(project, scope))
                    MACRO_LOG.debug("Macros to expand: ${calls.size}")
                    calls.groupBy { it.containingFile.virtualFile }

                }
                return storage.makeExpansionTask(calls)
            }

            override fun canEat(other: MacroExpansionTaskBase): Boolean = true // eat everything

            override val isProgressBarDelayed: Boolean get() = false
        }
        taskQueue.run(ProcessUnprocessedMacrosTask())
    }

    private fun processChangedMacros(workspaceOnly: Boolean) {
        MACRO_LOG.trace("processChangedMacros")
        if (!isExpansionModeNew) return
        class ProcessModifiedMacrosTask(private val workspaceOnly: Boolean) : MacroExpansionTaskBase(
            project,
            storage,
            pool,
            dirs.expansionsDirVi.pathAsPath
        ) {
            override fun getMacrosToExpand(): Sequence<List<Extractable>> {
                return runReadAction { storage.makeValidationTask(workspaceOnly) }
            }

            override fun canEat(other: MacroExpansionTaskBase): Boolean {
                return other is ProcessModifiedMacrosTask && (other.workspaceOnly || !workspaceOnly)
            }
        }

        val task = ProcessModifiedMacrosTask(workspaceOnly)

        taskQueue.run(task)
    }

    fun ensureUpToDate() {
        if (!isExpansionModeNew) return
        ProgressManager.checkCanceled()

        taskQueue.ensureUpToDate()
    }

    fun getExpansionFor(call: RsMacroCall): MacroExpansion? {
        checkReadAccessAllowed()
        if (isResolvingMacro) return null

        if (expansionMode == MacroExpansionMode.OLD) {
            return expandMacroOld(call)
        }

        if (!call.isTopLevelExpansion || call.containingFile.virtualFile?.fileSystem !is LocalFileSystem) {
            return expandMacroToMemoryFile(call, storeRangeMap = true)
        }

        ensureUpToDate()
        return storage.getInfoForCall(call)?.getExpansion()
    }

    fun getExpandedFrom(element: RsExpandedElement): RsMacroCall? {
        checkReadAccessAllowed()
        if (element.stubParent is RsFile /*TODO*/) {
            val file = element.containingFile.virtualFile ?: return null
            if (isExpansionFile(file)) {
                return storage.getInfoForExpandedFile(file)?.getMacroCall()
            }
        }

        return null
    }

    @TestOnly
    fun setupForUnitTests(saveCacheOnDispose: Boolean): Disposable {
        val disposable = Disposable { disposeUnitTest(saveCacheOnDispose) }

        setupListeners(disposable)

        return disposable
    }

    private fun disposeUnitTest(saveCacheOnDispose: Boolean) {
        check(isUnitTestMode)

        taskQueue.cancelAll()

        if (saveCacheOnDispose) {
            save()
        } else {
            dirs.baseProjectDir.delete()
        }
    }

    fun unbindPsi() {
        storage.unbindPsi()
    }
}

/** Inspired by [BackgroundTaskQueue] */
private class MacroExpansionTaskQueue(val project: Project) {
    private val processor = QueueProcessor<ContinuableRunnable>(
        PairConsumer { obj, t -> obj.run(t) },
        true,
        ThreadToUse.AWT,
        project.disposed
    )

    // Guarded by self object monitor (@Synchronized)
    private val cancelableTasks: MutableList<BackgroundableTaskData> = mutableListOf()

    @Synchronized
    fun run(task: MacroExpansionTaskBase) {
        cancelableTasks.removeIf {
            if (it.task is MacroExpansionTaskBase && task.canEat(it.task)) {
                it.cancel()
                true
            } else {
                false
            }
        }
        val data = BackgroundableTaskData(task)
        cancelableTasks += data
        processor.add(data)
    }

    fun run(task: Task.Backgroundable) {
        processor.add(BackgroundableTaskData(task))
    }

    fun runSimple(runnable: () -> Unit) {
        processor.add(SimpleTaskData(runnable))
    }

    fun ensureUpToDate() {
        if (isUnitTestMode && ApplicationManager.getApplication().isDispatchThread && !processor.isEmpty) {
            check(!ApplicationManager.getApplication().isWriteAccessAllowed)
            while (!processor.isEmpty && !project.isDisposed) {
                LaterInvocator.dispatchPendingFlushes()
                Thread.sleep(10)
            }
        }
    }

    @Synchronized
    fun cancelAll() {
        for (task in cancelableTasks) {
            task.cancel()
        }
        cancelableTasks.clear()
    }

    private interface ContinuableRunnable {
        fun run(continuation: Runnable)
    }

    private class BackgroundableTaskData(val task: Task.Backgroundable) : ContinuableRunnable {
        private var state: State = State.Pending

        @Synchronized
        override fun run(continuation: Runnable) {
            // BackgroundableProcessIndicator should be created from EDT
            checkIsDispatchThread()
            if (state != State.Pending) {
                continuation.run()
                return
            }

            val indicator = when {
                ApplicationManager.getApplication().isHeadlessEnvironment ->
                    EmptyProgressIndicator()

                task is MacroExpansionTaskBase && task.isProgressBarDelayed ->
                    DelayedBackgroundableProcessIndicator(task, 2000)

                else -> BackgroundableProcessIndicator(task)
            }

            state = State.Running(indicator)

            val pm = ProgressManager.getInstance() as ProgressManagerImpl
            pm.runProcessWithProgressAsynchronously(task, indicator, continuation, ModalityState.NON_MODAL)
        }

        @Synchronized
        fun cancel() {
            when (val state = state) {
                State.Pending -> this.state = State.Canceled
                is State.Running -> state.indicator.cancel()
                State.Canceled -> Unit
            }
        }

        private sealed class State {
            object Pending : State()
            object Canceled : State()
            data class Running(val indicator: ProgressIndicator) : State()
        }
    }

    private class SimpleTaskData(val task: () -> Unit) : ContinuableRunnable {
        override fun run(continuation: Runnable) {
            try {
                task()
            } finally {
                continuation.run()
            }
        }

    }
}

private fun expandMacroOld(call: RsMacroCall): MacroExpansion? {
    // Most of std macros contain the only `impl`s which are not supported for now, so ignoring them
    if (call.containingCargoTarget?.pkg?.origin == PackageOrigin.STDLIB) {
        return null
    }
    return expandMacroToMemoryFile(
        call,
        // Old macros already consume too much memory, don't force them to consume more by range maps
        storeRangeMap = isUnitTestMode // false
    )
}

private fun expandMacroToMemoryFile(call: RsMacroCall, storeRangeMap: Boolean): MacroExpansion? {
    val context = call.context as? RsElement ?: return null
    val def = call.resolveToMacro() ?: return null
    val project = call.project
    val result = MacroExpander(project).expandMacro(
        def,
        call,
        RsPsiFactory(project, markGenerated = false),
        storeRangeMap
    )
    result?.elements?.forEach {
        it.setContext(context)
        it.setExpandedFrom(call)
    }

    return result
}

private val RS_EXPANSION_MACRO_CALL = Key.create<RsElement>("org.rust.lang.core.psi.RS_EXPANSION_MACRO_CALL")

private fun RsExpandedElement.setExpandedFrom(call: RsMacroCall) {
    putUserData(RS_EXPANSION_MACRO_CALL, call)
}

enum class MacroExpansionScope {
    ALL, WORKSPACE, NONE
}

sealed class MacroExpansionMode {
    object Disabled : MacroExpansionMode()
    object Old : MacroExpansionMode()
    data class New(val scope: MacroExpansionScope) : MacroExpansionMode()

    companion object {
        val DISABLED = MacroExpansionMode.Disabled
        val OLD = MacroExpansionMode.Old
        val NEW_ALL = New(MacroExpansionScope.ALL)
    }
}

fun MacroExpansionMode.toScope(): MacroExpansionScope = when (this) {
    is MacroExpansionMode.New -> scope
    else -> MacroExpansionScope.NONE
}

private fun RustProjectSettingsService.MacroExpansionEngine.toMode(): MacroExpansionMode = when (this) {
    RustProjectSettingsService.MacroExpansionEngine.DISABLED -> MacroExpansionMode.DISABLED
    RustProjectSettingsService.MacroExpansionEngine.OLD -> MacroExpansionMode.OLD
    RustProjectSettingsService.MacroExpansionEngine.NEW -> MacroExpansionMode.NEW_ALL
}

val Project.macroExpansionManager: MacroExpansionManager
    get() = getComponent(MacroExpansionManager::class.java)
