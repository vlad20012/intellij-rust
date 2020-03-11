/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.model.impl

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.BackgroundTaskQueue
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.Consumer
import com.intellij.util.indexing.LightDirectoryIndex
import com.intellij.util.io.exists
import com.intellij.util.io.systemIndependentPath
import org.jdom.Element
import org.jetbrains.annotations.TestOnly
import org.rust.cargo.CargoConstants
import org.rust.cargo.CfgOptions
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.CargoProject.UpdateStatus
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.model.RustcInfo
import org.rust.cargo.project.model.setup
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.project.toolwindow.CargoToolWindow.Companion.initializeToolWindowIfNeeded
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.project.workspace.StandardLibrary
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.toolchain.RustToolchain
import org.rust.cargo.toolchain.Rustup
import org.rust.cargo.util.AutoInjectedCrates
import org.rust.cargo.util.DownloadResult
import org.rust.ide.notifications.showBalloon
import org.rust.openapiext.*
import org.rust.stdext.AsyncValue
import org.rust.stdext.applyWithSymlink
import org.rust.stdext.joinAll
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference


@State(name = "CargoProjects", storages = [
    Storage(StoragePathMacros.WORKSPACE_FILE),
    Storage("misc.xml", deprecated = true)
])
open class CargoProjectsServiceImpl(
    val project: Project
) : CargoProjectsService, PersistentStateComponent<Element> {
    init {
        with(project.messageBus.connect()) {
            subscribe(VirtualFileManager.VFS_CHANGES, CargoTomlWatcher(fun() {
                if (!project.rustSettings.autoUpdateEnabled) return
                refreshAllProjects()
            }))

            subscribe(RustProjectSettingsService.TOOLCHAIN_TOPIC, object : RustProjectSettingsService.ToolchainListener {
                override fun toolchainChanged() {
                    refreshAllProjects()
                }
            })

            subscribe(CargoProjectsService.CARGO_PROJECTS_TOPIC, object : CargoProjectsService.CargoProjectsListener {
                override fun cargoProjectsUpdated(projects: Collection<CargoProject>) {
                    initializeToolWindowIfNeeded(project)
                }
            })
        }
    }

    /**
     * While in theory Cargo and rustup are concurrency-safe, in practice
     * it's better to serialize their execution, and this queue does
     * exactly that. Not that [AsyncValue] [projects] also provides
     * serialization grantees, so this queue is no strictly necessary.
     */
    val taskQueue = BackgroundTaskQueue(project, "Cargo update")

    /**
     * The heart of the plugin Project model. Care must be taken to ensure
     * this is thread-safe, and that refreshes are scheduled after
     * set of projects changes.
     */
    private val projects = AsyncValue<List<CargoProjectImpl>>(emptyList())


    private val noProjectMarker = CargoProjectImpl(Paths.get(""), this)
    /**
     * [directoryIndex] allows to quickly map from a [VirtualFile] to
     * a containing [CargoProject].
     */
    private val directoryIndex: LightDirectoryIndex<CargoProjectImpl> =
        LightDirectoryIndex(project, noProjectMarker, Consumer { index ->
            val visited = mutableSetOf<VirtualFile>()

            fun VirtualFile.put(cargoProject: CargoProjectImpl) {
                if (this in visited) return
                visited += this
                index.putInfo(this, cargoProject)
            }

            fun CargoWorkspace.Package.put(cargoProject: CargoProjectImpl) {
                contentRoot?.put(cargoProject)
                outDir?.put(cargoProject)
                for (target in targets) {
                    target.crateRoot?.parent?.put(cargoProject)
                }
            }

            val lowPriority = mutableListOf<Pair<CargoWorkspace.Package, CargoProjectImpl>>()

            for (cargoProject in projects.currentState) {
                cargoProject.rootDir?.put(cargoProject)
                for (pkg in cargoProject.workspace?.packages.orEmpty()) {
                    if (pkg.origin == PackageOrigin.WORKSPACE) {
                        pkg.put(cargoProject)
                    } else {
                        lowPriority += pkg to cargoProject
                    }
                }
            }

            for ((pkg, cargoProject) in lowPriority) {
                pkg.put(cargoProject)
            }
        })

    private val packageIndex: CargoPackageIndex = CargoPackageIndex(project, this)

    override val allProjects: Collection<CargoProject>
        get() = projects.currentState

    override val hasAtLeastOneValidProject: Boolean
        get() = hasAtLeastOneValidProject(allProjects)

    // Guarded by the platform RWLock
    override var initialized: Boolean = false

    override fun findProjectForFile(file: VirtualFile): CargoProject? =
        file.applyWithSymlink { directoryIndex.getInfoForFile(it).takeIf { it !== noProjectMarker } }

    override fun findPackageForFile(file: VirtualFile): CargoWorkspace.Package? =
        file.applyWithSymlink(packageIndex::findPackageForFile)

    override fun attachCargoProject(manifest: Path): Boolean {
        if (isExistingProject(allProjects, manifest)) return false
        modifyProjects { projects ->
            if (isExistingProject(projects, manifest))
                CompletableFuture.completedFuture(projects)
            else
                doRefresh(project, projects + CargoProjectImpl(manifest, this))
        }
        return true
    }

    override fun detachCargoProject(cargoProject: CargoProject) {
        modifyProjects { projects ->
            CompletableFuture.completedFuture(projects.filter { it.manifest != cargoProject.manifest })
        }
    }

    override fun refreshAllProjects(): CompletableFuture<out List<CargoProject>> =
        modifyProjects { doRefresh(project, it) }

    override fun discoverAndRefresh(): CompletableFuture<out List<CargoProject>> {
        val guessManifest = suggestManifests().firstOrNull()
            ?: return CompletableFuture.completedFuture(projects.currentState)

        return modifyProjects { projects ->
            if (hasAtLeastOneValidProject(projects)) return@modifyProjects CompletableFuture.completedFuture(projects)
            doRefresh(project, listOf(CargoProjectImpl(guessManifest.pathAsPath, this)))
        }
    }

    override fun suggestManifests(): Sequence<VirtualFile> =
        project.modules
            .asSequence()
            .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
            .mapNotNull { it.findChild(RustToolchain.CARGO_TOML) }

    /**
     * All modifications to project model except for low-level `loadState` should
     * go through this method: it makes sure that when we update various IDEA listeners,
     * [allProjects] contains fresh projects.
     */
    protected fun modifyProjects(
        f: (List<CargoProjectImpl>) -> CompletableFuture<List<CargoProjectImpl>>
    ): CompletableFuture<List<CargoProjectImpl>> =
        projects.updateAsync(f)
            .thenApply { projects ->
                ApplicationManager.getApplication().invokeAndWait {
                    runWriteAction {
                        directoryIndex.resetIndex()
                        ProjectRootManagerEx.getInstanceEx(project)
                            .makeRootsChange(EmptyRunnable.getInstance(), false, true)
                        project.messageBus.syncPublisher(CargoProjectsService.CARGO_PROJECTS_TOPIC)
                            .cargoProjectsUpdated(projects)
                        initialized = true
                    }
                }

                projects
            }

    override fun getState(): Element {
        val state = Element("state")
        for (cargoProject in allProjects) {
            val cargoProjectElement = Element("cargoProject")
            cargoProjectElement.setAttribute("FILE", cargoProject.manifest.systemIndependentPath)
            state.addContent(cargoProjectElement)
        }
        return state
    }

    override fun loadState(state: Element) {
        val loaded = state.getChildren("cargoProject")
            .mapNotNull { it.getAttributeValue("FILE") }
            .map { CargoProjectImpl(Paths.get(it), this) }
        // Refresh projects via `invokeLater` to avoid model modifications
        // while the project is being opened. Use `updateSync` directly
        // instead of `modifyProjects` for this reason
        projects.updateSync { _ -> loaded }
            .whenComplete { _, _ ->
                ApplicationManager.getApplication().invokeLater { refreshAllProjects() }
            }
    }

    override fun noStateLoaded() {
        // Do nothing: in theory, we might try to do [discoverAndRefresh]
        // here, but the `RustToolchain` is most likely not ready.
        //
        // So the actual "Let's guess a project model if it is not imported
        // explicitly" happens in [org.rust.ide.notifications.MissingToolchainNotificationProvider]
    }

    override fun toString(): String =
        "CargoProjectsService(projects = $allProjects)"
}

data class CargoProjectImpl(
    override val manifest: Path,
    private val projectService: CargoProjectsServiceImpl,
    private val rawWorkspace: CargoWorkspace? = null,
    private val stdlib: StandardLibrary? = null,
    override val rustcInfo: RustcInfo? = null,
    override val workspaceStatus: UpdateStatus = UpdateStatus.NeedsUpdate,
    override val stdlibStatus: UpdateStatus = UpdateStatus.NeedsUpdate,
    override val rustcInfoStatus: UpdateStatus = UpdateStatus.NeedsUpdate
) : UserDataHolderBase(), CargoProject {
    override val project get() = projectService.project
    private val toolchain get() = project.toolchain
    override val workspace: CargoWorkspace? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val rawWorkspace = rawWorkspace ?: return@lazy null
        val stdlib = stdlib ?: return@lazy rawWorkspace
        rawWorkspace.withStdlib(stdlib, rawWorkspace.cfgOptions, rustcInfo)
    }

    override val presentableName: String
        get() = workingDirectory.fileName.toString()

    private val rootDirCache = AtomicReference<VirtualFile>()
    override val rootDir: VirtualFile?
        get() {
            val cached = rootDirCache.get()
            if (cached != null && cached.isValid) return cached
            val file = LocalFileSystem.getInstance().findFileByIoFile(workingDirectory.toFile())
            rootDirCache.set(file)
            return file
        }

    override val workspaceRootDir: VirtualFile? by CachedVirtualFile(workspace?.workspaceRootPath?.toUri()?.toString())

    @TestOnly
    fun setRootDir(dir: VirtualFile) = rootDirCache.set(dir)

    fun refresh(): CompletableFuture<CargoProjectImpl> {
        if (!workingDirectory.exists()) {
            return CompletableFuture.completedFuture(copy(
                stdlibStatus = UpdateStatus.UpdateFailed("Project directory does not exist"))
            )
        }
        return refreshRustcInfo()
            .thenCompose { it.refreshWorkspace() }
            .thenCompose { it.refreshStdlib() }
    }

    private fun refreshStdlib(): CompletableFuture<CargoProjectImpl> {
        if (doesProjectLooksLikeRustc()) {
            // rust-lang/rust contains stdlib inside the project
            val std = StandardLibrary.fromPath(manifest.parent.toString())
            if (std != null) {
                return CompletableFuture.completedFuture(withStdlib(TaskResult.Ok(std)))
            }
        }

        val rustup = toolchain?.rustup(workingDirectory)
        if (rustup == null) {
            val explicitPath = project.rustSettings.explicitPathToStdlib
            val lib = explicitPath?.let { StandardLibrary.fromPath(it) }
            val result = when {
                explicitPath == null -> TaskResult.Err<StandardLibrary>("no explicit stdlib or rustup found")
                lib == null -> TaskResult.Err("invalid standard library: $explicitPath")
                else -> TaskResult.Ok(lib)
            }
            return CompletableFuture.completedFuture(withStdlib(result))
        }

        return fetchStdlib(project, projectService.taskQueue, rustup).thenApply(this::withStdlib)
    }

    // Checks that the project is https://github.com/rust-lang/rust
    private fun doesProjectLooksLikeRustc(): Boolean {
        val workspace = workspace
        return workspace?.findPackage(AutoInjectedCrates.STD) != null &&
            workspace.findPackage(AutoInjectedCrates.CORE) != null &&
            workspace.findPackage("rustc") != null
    }

    private fun withStdlib(result: TaskResult<StandardLibrary>): CargoProjectImpl = when (result) {
        is TaskResult.Ok -> copy(stdlib = result.value, stdlibStatus = UpdateStatus.UpToDate)
        is TaskResult.Err -> copy(stdlibStatus = UpdateStatus.UpdateFailed(result.reason))
    }

    private fun refreshWorkspace(): CompletableFuture<CargoProjectImpl> {
        val toolchain = toolchain
            ?: return CompletableFuture.completedFuture(copy(workspaceStatus = UpdateStatus.UpdateFailed(
                "Can't update Cargo project, no Rust toolchain"
            )))

        return fetchCargoWorkspace(project, projectService.taskQueue, toolchain, workingDirectory)
            .thenApply(this::withWorkspace)
    }

    private fun withWorkspace(result: TaskResult<CargoWorkspace>): CargoProjectImpl = when (result) {
        is TaskResult.Ok -> copy(rawWorkspace = result.value, workspaceStatus = UpdateStatus.UpToDate)
        is TaskResult.Err -> copy(workspaceStatus = UpdateStatus.UpdateFailed(result.reason))
    }

    private fun refreshRustcInfo(): CompletableFuture<CargoProjectImpl> {
        val toolchain = toolchain
            ?: return CompletableFuture.completedFuture(copy(rustcInfoStatus = UpdateStatus.UpdateFailed(
                "Can't get rustc info, no Rust toolchain"
            )))

        return fetchRustcInfo(project, projectService.taskQueue, toolchain, workingDirectory)
            .thenApply(this::withRustcInfo)
    }

    private fun withRustcInfo(result: TaskResult<RustcInfo>): CargoProjectImpl = when (result) {
        is TaskResult.Ok -> copy(rustcInfo = result.value, rustcInfoStatus = UpdateStatus.UpToDate)
        is TaskResult.Err -> copy(rustcInfoStatus = UpdateStatus.UpdateFailed(result.reason))
    }

    override fun toString(): String =
        "CargoProject(manifest = $manifest)"
}

val CargoProjectsService.allPackages: Sequence<CargoWorkspace.Package>
    get() = allProjects.asSequence().mapNotNull { it.workspace }.flatMap { it.packages.asSequence() }

val CargoProjectsService.allTargets: Sequence<CargoWorkspace.Target>
    get() = allPackages.flatMap { it.targets.asSequence() }

private fun hasAtLeastOneValidProject(projects: Collection<CargoProject>) =
    projects.any { it.manifest.exists() }

private fun isExistingProject(projects: Collection<CargoProject>, manifest: Path): Boolean {
    if (projects.any { it.manifest == manifest }) return true
    return projects.mapNotNull { it.workspace }.flatMap { it.packages }
        .filter { it.origin == PackageOrigin.WORKSPACE }
        .any { it.rootDirectory == manifest.parent }
}

private fun doRefresh(project: Project, projects: List<CargoProjectImpl>): CompletableFuture<List<CargoProjectImpl>> {
    return projects.map { it.refresh() }
        .joinAll()
        .thenApply { updatedProjects ->
            for (p in updatedProjects) {
                val status = p.mergedStatus
                if (status is UpdateStatus.UpdateFailed) {
                    project.showBalloon(
                        "Cargo project update failed:<br>${status.reason}",
                        NotificationType.ERROR
                    )
                    break
                }
            }

            setupProjectRoots(project, updatedProjects)
            updatedProjects
        }
}

private fun setupProjectRoots(project: Project, cargoProjects: List<CargoProject>) {
    ApplicationManager.getApplication().invokeAndWait {
        runWriteAction {
            if (project.isDisposed) return@runWriteAction
            ProjectRootManagerEx.getInstanceEx(project).mergeRootsChangesDuring {
                for (cargoProject in cargoProjects) {
                    cargoProject.workspaceRootDir?.setupContentRoots(project) { contentRoot ->
                        addExcludeFolder(FileUtil.join(contentRoot.url, CargoConstants.ProjectLayout.target))
                    }

                    val alreadySetUp = hashSetOf<CargoWorkspace.Package>()

                    fun setupPackage(pkg: CargoWorkspace.Package, module: Module) {
                        if (pkg in alreadySetUp) return
                        alreadySetUp += pkg
                        if (pkg.origin == PackageOrigin.WORKSPACE) {
                            pkg.contentRoot?.setupContentRoots(module, ContentEntry::setup)
                        }
                        val outDir = pkg.outDir
                        if (outDir != null) {
                            ModuleRootModificationUtil.updateModel(module) { rootModel ->
                                val entry = rootModel.contentEntries.singleOrNull() ?: return@updateModel
                                entry.addSourceFolder(outDir, false)
                            }
                        }
                        for (dependency in pkg.dependencies) {
                            setupPackage(dependency.pkg, module)
                        }
                    }

                    val workspacePackages = cargoProject.workspace?.packages
                        .orEmpty()
                        .filter { it.origin == PackageOrigin.WORKSPACE }

                    for (pkg in workspacePackages) {
                        val contentRoot = pkg.contentRoot ?: continue
                        val packageModule = ModuleUtilCore.findModuleForFile(contentRoot, project) ?: continue
                        setupPackage(pkg, packageModule)
                    }
                }
            }
        }
    }
}

private fun VirtualFile.setupContentRoots(project: Project, setup: ContentEntry.(VirtualFile) -> Unit) {
    val packageModule = ModuleUtilCore.findModuleForFile(this, project) ?: return
    setupContentRoots(packageModule, setup)
}

private fun VirtualFile.setupContentRoots(packageModule: Module, setup: ContentEntry.(VirtualFile) -> Unit) {
    ModuleRootModificationUtil.updateModel(packageModule) { rootModel ->
        rootModel.contentEntries.singleOrNull()?.setup(this)
    }
}

private fun fetchStdlib(
    project: Project,
    queue: BackgroundTaskQueue,
    rustup: Rustup
): CompletableFuture<TaskResult<StandardLibrary>> {
    return runAsyncTask(project, queue, "Getting Rust stdlib") {
        progress.isIndeterminate = true
        when (val download = rustup.downloadStdlib()) {
            is DownloadResult.Ok -> {
                val lib = StandardLibrary.fromFile(download.value)
                if (lib == null) {
                    err("" +
                        "corrupted standard library: ${download.value.presentableUrl}"
                    )
                } else {
                    ok(lib)
                }
            }
            is DownloadResult.Err -> err(
                "download failed: ${download.error}"
            )
        }
    }
}

private fun fetchCargoWorkspace(
    project: Project,
    queue: BackgroundTaskQueue,
    toolchain: RustToolchain,
    projectDirectory: Path
): CompletableFuture<TaskResult<CargoWorkspace>> {
    return runAsyncTask(project, queue, "Updating cargo") {
        progress.isIndeterminate = true
        if (!toolchain.looksLikeValidToolchain()) {
            return@runAsyncTask err(
                "invalid Rust toolchain ${toolchain.presentableLocation}"
            )
        }
        val cargo = toolchain.cargoOrWrapper(projectDirectory)
        try {
            val projectDescriptionData = cargo.fullProjectDescription(project, projectDirectory, object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<Any>) {
                    val text = event.text.trim { it <= ' ' }
                    if (text.startsWith("Updating") || text.startsWith("Downloading")) {
                        progress.text = text
                    }
                }
            })
            val manifestPath = projectDirectory.resolve("Cargo.toml")

            // Running "cargo rustc -- --print cfg" causes an error when run in a project with multiple targets
            // error: extra arguments to `rustc` can only be passed to one target, consider filtering
            // the package by passing e.g. `--lib` or `--bin NAME` to specify a single target
            // Running "cargo rustc --bin=projectname  -- --print cfg" we can get around this
            // but it also compiles the whole project, which is probably not wanted
            //TODO: This does not query the target specific cfg flags during cross compilation :-(
            val rawCfgOptions = toolchain.getCfgOptions(projectDirectory) ?: emptyList()
            val cfgOptions = CfgOptions.parse(rawCfgOptions)
            val ws = CargoWorkspace.deserialize(manifestPath, projectDescriptionData, cfgOptions)
            ok(ws)
        } catch (e: ExecutionException) {
            err(e.message ?: "failed to run Cargo")
        }
    }
}

private fun fetchRustcInfo(
    project: Project,
    queue: BackgroundTaskQueue,
    toolchain: RustToolchain,
    projectDirectory: Path
): CompletableFuture<TaskResult<RustcInfo>> {
    return runAsyncTask(project, queue, "Getting toolchain version") {
        progress.isIndeterminate = true
        if (!toolchain.looksLikeValidToolchain()) {
            return@runAsyncTask err(
                "invalid Rust toolchain ${toolchain.presentableLocation}"
            )
        }

        val sysroot = toolchain.getSysroot(projectDirectory)
            ?: return@runAsyncTask err("failed to get project sysroot")
        val versions = toolchain.queryVersions()

        ok(RustcInfo(sysroot, versions.rustc))
    }
}
