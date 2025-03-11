package ee.carlrobert.codegpt.toolwindow.chat.structure.data

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.io.await
import ee.carlrobert.codegpt.psistructure.PsiStructureProvider
import ee.carlrobert.codegpt.settings.chat.ChatSettingsListener
import ee.carlrobert.codegpt.ui.textarea.header.tag.CurrentGitChangesTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.DocumentationTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorSelectionTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EditorTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.EmptyTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.FolderTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.GitCommitTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.PersonaTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.SelectionTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManagerListener
import ee.carlrobert.codegpt.ui.textarea.header.tag.WebTagDetails
import ee.carlrobert.codegpt.util.coroutines.CoroutineDispatchers
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PsiStructureRepository(
    parentDisposable: Disposable,
    private val project: Project,
    private val tagManager: TagManager,
    private val psiStructureProvider: PsiStructureProvider,
    private val dispatchers: CoroutineDispatchers,
) {

    private val mutex = Mutex()
    private val coroutineScope = DisposableCoroutineScope(dispatchers.io())

    @Volatile
    private var updatePsiStructureJob: Job? = null

    private val tagsListener = object : TagManagerListener {
        override fun onTagAdded(tag: TagDetails) {
            updatePsiStructureIfNeeded()
        }

        override fun onTagRemoved(tag: TagDetails) {
            updatePsiStructureIfNeeded()
        }

        override fun onTagSelectionChanged(tag: TagDetails) {
            val tags = tagManager.getTags().getPsiAnalyzedTags()
            update(tags)
        }

        private fun updatePsiStructureIfNeeded() {
            val tags = tagManager.getTags().getPsiAnalyzedTags()
            if (isNeedUpdatePsiStructure(tags)) {
                update(tags)
            }
        }

        private fun isNeedUpdatePsiStructure(tagsForAnalyze: Set<TagDetails>): Boolean {
            val currentlyAnalyzedTags = when (val currentState = _structureState.value) {
                is PsiStructureState.Content -> currentState.currentlyAnalyzedTags
                is PsiStructureState.UpdateInProgress -> currentState.currentlyAnalyzedTags
                PsiStructureState.Disabled -> emptySet()
            }
            return tagsForAnalyze.toVirtualFilesSet() != currentlyAnalyzedTags.toVirtualFilesSet()
        }
    }

    private val asyncFileListener = AsyncFileListener { events ->
        val currentlyAnalyzedTags = when (val currentState = _structureState.value) {
            is PsiStructureState.Content -> currentState.currentlyAnalyzedTags
            is PsiStructureState.UpdateInProgress -> currentState.currentlyAnalyzedTags
            PsiStructureState.Disabled -> emptySet()
        }

        val currentlyAnalyzedFiles = currentlyAnalyzedTags.toVirtualFilesSet()

        val hasRelevantChanges = events.any { event ->
            event.file?.let { it in currentlyAnalyzedFiles } == true
        }

        if (hasRelevantChanges) {
            object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    update(currentlyAnalyzedTags)
                }
            }
        } else {
            null
        }
    }

    init {
        Disposer.register(parentDisposable, coroutineScope)
        tagManager.addListener(tagsListener)
        Disposer.register(parentDisposable) {
            tagManager.removeListener(tagsListener)
        }
        VirtualFileManager.getInstance().addAsyncFileListener(asyncFileListener, parentDisposable)

        val connection = ApplicationManager.getApplication().messageBus
            .connect(parentDisposable)

        connection.subscribe(
            ChatSettingsListener.TOPIC,
            ChatSettingsListener { newState ->
                if (newState.psiStructureEnabled) {
                    enable()
                } else {
                    disable()
                }
            })
    }

    private val _structureState: MutableStateFlow<PsiStructureState> = MutableStateFlow(
        PsiStructureState.Content(emptySet(), emptySet())
    )
    val structureState = _structureState.asStateFlow()

    private fun disable() {
        updatePsiStructureJob?.cancel()
        _structureState.value = PsiStructureState.Disabled
    }

    private fun enable() {
        val tags = tagManager.getTags().getPsiAnalyzedTags()
        _structureState.value = PsiStructureState.UpdateInProgress(tags)
        update(tags)
    }

    private fun update(tags: Set<TagDetails>) {
        updatePsiStructureJob?.cancel()
        updatePsiStructureJob = coroutineScope.launch {
            mutex.withLock {
                if (_structureState.value == PsiStructureState.Disabled) return@launch
                _structureState.value = PsiStructureState.UpdateInProgress(tags)

                val tagsVirtualFiles = tags.toVirtualFilesSet()
                val coroutineContext = currentCoroutineContext()

                val psiFiles = ReadAction.nonBlocking<List<PsiFile>> {
                    tagsVirtualFiles
                        .mapNotNull { virtualFile ->
                            coroutineContext.ensureActive()
                            PsiManager.getInstance(project).findFile(virtualFile)
                        }
                }
                    .inSmartMode(project)
                    .submit(dispatchers.default().asExecutor())
                    .await()

                val virtualFilesToRemoveFromStructure = tags.getExcludedVirtualFiles()
                val result = psiStructureProvider.get(psiFiles)
                    .filter { classStructure ->
                        !virtualFilesToRemoveFromStructure.contains(classStructure.virtualFile)
                    }
                    .toSet()

                _structureState.value = PsiStructureState.Content(tags, result)
            }
        }
    }

    private fun Set<TagDetails>.getExcludedVirtualFiles(): Set<VirtualFile> =
        mapNotNull { tagDetails ->
            if (!tagDetails.selected) {
                null
            } else {
                when (tagDetails) {
                    is SelectionTagDetails -> tagDetails.virtualFile
                    is FileTagDetails -> tagDetails.virtualFile
                    is EditorTagDetails -> tagDetails.virtualFile

                    // Maybe need recursive find all files
                    is FolderTagDetails -> null

                    is EditorSelectionTagDetails -> null
                    is DocumentationTagDetails -> null
                    is CurrentGitChangesTagDetails -> null
                    is GitCommitTagDetails -> null
                    is PersonaTagDetails -> null
                    is EmptyTagDetails -> null
                    is WebTagDetails -> null
                }
            }
        }
            .toSet()

    private fun Set<TagDetails>.getPsiAnalyzedTags(): Set<TagDetails> =
        filter { tagDetails ->
            when (tagDetails) {
                is SelectionTagDetails -> tagDetails.selected
                is FileTagDetails -> tagDetails.selected
                is EditorSelectionTagDetails -> tagDetails.selected
                is EditorTagDetails -> tagDetails.selected

                // Maybe need recursive find all files
                is FolderTagDetails -> false

                is DocumentationTagDetails -> false
                is CurrentGitChangesTagDetails -> false
                is GitCommitTagDetails -> false
                is PersonaTagDetails -> false
                is EmptyTagDetails -> false
                is WebTagDetails -> false
            }
        }
            .toSet()

    private fun Set<TagDetails>.toVirtualFilesSet(): Set<VirtualFile> =
        mapNotNull { tagDetails ->
            if (!tagDetails.selected) {
                null
            } else {
                when (tagDetails) {
                    is SelectionTagDetails -> tagDetails.virtualFile
                    is FileTagDetails -> tagDetails.virtualFile
                    is EditorSelectionTagDetails -> tagDetails.virtualFile
                    is EditorTagDetails -> tagDetails.virtualFile

                    // Maybe need recursive find all files
                    is FolderTagDetails -> null

                    is DocumentationTagDetails -> null
                    is CurrentGitChangesTagDetails -> null
                    is GitCommitTagDetails -> null
                    is PersonaTagDetails -> null
                    is EmptyTagDetails -> null
                    is WebTagDetails -> null
                }
            }
        }
            .toSet()
}