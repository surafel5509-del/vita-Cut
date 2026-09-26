package com.vitacut.feature.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitacut.core.ai.AudioAnalyzers
import com.vitacut.core.ai.ObjectDetector
import com.vitacut.core.ai.SubjectSegmenter
import com.vitacut.core.ai.beat.BeatDetector
import com.vitacut.core.ai.reframe.AutoReframeAnalyzer
import com.vitacut.core.ai.tracking.MotionTracker
import com.vitacut.core.ai.tracking.NormalizedRegion
import com.vitacut.core.captions.CaptionEditor
import com.vitacut.core.captions.SubtitleImporter
import com.vitacut.core.captions.TranscriptionRegistry
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.common.result.VitaResult
import com.vitacut.core.datastore.SettingsStore
import com.vitacut.core.media.metadata.MediaMetadataReader
import com.vitacut.core.media.preprocess.ReverseTranscoder
import com.vitacut.core.media.record.RecorderState
import com.vitacut.core.media.record.VoiceRecorder
import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.CaptionAnimation
import com.vitacut.core.model.CaptionSet
import com.vitacut.core.model.CaptionStyle
import com.vitacut.core.model.EffectInstance
import com.vitacut.core.model.EffectKind
import com.vitacut.core.model.FilterState
import com.vitacut.core.model.ItemId
import com.vitacut.core.model.KeyframeProperty
import com.vitacut.core.model.MediaAsset
import com.vitacut.core.model.MediaKind
import com.vitacut.core.model.Project
import com.vitacut.core.model.SpeedCurve
import com.vitacut.core.model.SpeedCurvePreset
import com.vitacut.core.model.SpeedModel
import com.vitacut.core.model.StickerItem
import com.vitacut.core.model.StickerSource
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.TimelineItem
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.TrackPath
import com.vitacut.core.model.TransitionKind
import com.vitacut.core.model.TransitionState
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.timeline.ProjectDocument
import com.vitacut.core.timeline.TimelineEngine
import com.vitacut.core.timeline.TimelineQueries
import com.vitacut.domain.repository.ProjectRepository
import com.vitacut.domain.usecase.edit.ApplyAutoCutUseCase
import com.vitacut.domain.usecase.edit.ApplyAutoReframeUseCase
import com.vitacut.domain.usecase.edit.ApplySilenceRemovalUseCase
import com.vitacut.domain.usecase.project.AutosaveProjectUseCase
import com.vitacut.domain.usecase.project.CommitProjectUseCase
import com.vitacut.domain.usecase.project.OpenProjectUseCase
import com.vitacut.feature.editor.preview.PreviewController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Which tool sheet is open (null = none). */
enum class EditorSheet {
    MEDIA, CLIP, SPEED, AUDIO, TEXT, STICKER, FILTERS, EFFECTS, ADJUST,
    TRANSITION, CANVAS, CAPTIONS, AI, KEYFRAME,
    TRANSFORM, MASK, CHROMA, OVERLAY,
}

data class EditorUiState(
    val isLoading: Boolean = true,
    val loadErrorKey: String? = null,
    val projectId: String = "",
    val projectName: String = "",
    val playheadUs: Long = 0L,
    val durationUs: Long = 0L,
    val isPlaying: Boolean = false,
    val selectedItemId: ItemId? = null,
    val activeSheet: EditorSheet? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** Key of an in-flight long operation (reverse, tracking, captions, AI…) → busy overlay. */
    val busyKey: String? = null,
    val busyPercent: Int = -1,
    /** One-shot localized message for the snackbar/toast surface. */
    val messageKey: String? = null,
    val messageArgs: List<Any> = emptyList(),
    val recorderState: RecorderState = RecorderState.IDLE,
    val recordDurationMs: Long = 0L,
    /** Set while the back press awaits the save-confirmation dialog. */
    val showDiscardDialog: Boolean = false,
)

/**
 * The editor brain. Owns the [ProjectDocument] (undo/redo + dirty tracking), the autosave loop,
 * the [PreviewController] and every editing operation. All mutations go through the document so
 * each lands in history as one labeled step.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val openProject: OpenProjectUseCase,
    private val autosaveProject: AutosaveProjectUseCase,
    private val commitProject: CommitProjectUseCase,
    private val projectRepository: ProjectRepository,
    private val settingsStore: SettingsStore,
    private val metadataReader: MediaMetadataReader,
    private val voiceRecorder: VoiceRecorder,
    private val reverseTranscoder: ReverseTranscoder,
    private val transcriptionRegistry: TranscriptionRegistry,
    private val audioAnalyzers: AudioAnalyzers,
    private val objectDetector: ObjectDetector,
    private val subjectSegmenter: SubjectSegmenter,
    private val motionTracker: MotionTracker,
    private val applyAutoCut: ApplyAutoCutUseCase,
    private val applySilenceRemoval: ApplySilenceRemovalUseCase,
    private val applyAutoReframe: ApplyAutoReframeUseCase,
    val previewController: PreviewController,
) : ViewModel() {

    private val projectId: String = savedStateHandle.get<String>("projectId").orEmpty()

    private var document: ProjectDocument? = null

    private val _uiState = MutableStateFlow(EditorUiState(projectId = projectId))
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private var autosaveJob: Job? = null
    private var voiceoverStartUs = 0L

    init {
        viewModelScope.launch {
            val doc = runCatching { openProject(projectId) }.getOrNull()
            if (doc == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadErrorKey = "error_project_unreadable",
                )
                return@launch
            }
            document = doc
            _project.value = doc.project
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                projectName = doc.project.name,
                durationUs = doc.project.durationUs,
            )
            // Mirror document flows into UI state.
            launch {
                doc.projectFlow.collect { project ->
                    _project.value = project
                    _uiState.value = _uiState.value.copy(
                        durationUs = project.durationUs,
                        projectName = project.name,
                    )
                }
            }
            launch {
                doc.historyState.collect { history ->
                    _uiState.value = _uiState.value.copy(
                        canUndo = history.canUndo,
                        canRedo = history.canRedo,
                    )
                }
            }
            launch {
                doc.dirty.collect { dirty ->
                    _uiState.value = _uiState.value.copy(dirty = dirty)
                }
            }
            launch {
                previewController.timelinePositionUs.collect { timeUs ->
                    if (_uiState.value.isPlaying) {
                        _uiState.value = _uiState.value.copy(playheadUs = timeUs)
                    }
                }
            }
            launch {
                previewController.playbackEnded.collect { ended ->
                    if (ended) _uiState.value = _uiState.value.copy(isPlaying = false)
                }
            }
            startAutosaveLoop()
        }
    }

    private fun startAutosaveLoop() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            val settings = runCatching { settingsStore.settings.first() }.getOrNull()
            if (settings?.autoSaveEnabled != true) return@launch
            val intervalMs = (settings.autoSaveIntervalSeconds.coerceIn(5, 600)) * 1000L
            while (true) {
                delay(intervalMs)
                val doc = document ?: continue
                if (doc.dirty.value) {
                    _uiState.value = _uiState.value.copy(saving = true)
                    runCatching { autosaveProject(doc) }
                    _uiState.value = _uiState.value.copy(saving = false)
                }
            }
        }
    }

    // region Transport

    fun play() {
        val doc = document ?: return
        _uiState.value = _uiState.value.copy(isPlaying = true)
        previewController.play(doc.project, _uiState.value.playheadUs)
    }

    fun pause() {
        _uiState.value = _uiState.value.copy(isPlaying = false)
        previewController.pause()
    }

    fun togglePlay() {
        if (_uiState.value.isPlaying) pause() else play()
    }

    fun seekTo(timeUs: Long) {
        val doc = document ?: return
        val clamped = timeUs.coerceIn(0L, doc.project.durationUs.coerceAtLeast(0L))
        _uiState.value = _uiState.value.copy(playheadUs = clamped)
        previewController.seek(doc.project, clamped, _uiState.value.isPlaying)
    }

    // endregion

    // region Selection & sheets

    fun selectItem(itemId: ItemId?) {
        _uiState.value = _uiState.value.copy(selectedItemId = itemId)
    }

    fun selectedItem(): TimelineItem? {
        val id = _uiState.value.selectedItemId ?: return null
        return document?.project?.item(id)
    }

    fun openSheet(sheet: EditorSheet?) {
        // Clip-scoped sheets need a selection; nudge the user instead of showing empty panels.
        val needsSelection = sheet in setOf(
            EditorSheet.CLIP, EditorSheet.SPEED,
            EditorSheet.TRANSITION, EditorSheet.KEYFRAME,
            EditorSheet.TRANSFORM, EditorSheet.MASK, EditorSheet.CHROMA,
        )
        if (needsSelection && _uiState.value.selectedItemId == null) {
            message("editor_no_clip_selected")
            return
        }
        if (sheet == EditorSheet.CLIP || sheet == EditorSheet.SPEED) {
            val item = selectedItem()
            if (item is TextItem || item is StickerItem) {
                _uiState.value = _uiState.value.copy(activeSheet = EditorSheet.TEXT)
                return
            }
        }
        _uiState.value = _uiState.value.copy(activeSheet = sheet)
    }

    // endregion

    // region Generic document mutation

    fun update(label: String, transform: (Project) -> Project) {
        document?.update(label, transform)
    }

    fun undo() { document?.undo() }
    fun redo() { document?.redo() }

    // endregion

    // region Media import

    /** Photo Picker / SAF results: validate, register assets, append to the timeline. */
    fun addMedia(uris: List<Uri>) {
        val doc = document ?: return
        if (uris.isEmpty()) return
        busy("media_importing")
        viewModelScope.launch {
            val assets = metadataReader.readAssets(uris).filterNotNull()
            if (assets.isEmpty()) {
                done()
                message("error_unsupported_media")
                return@launch
            }
            doc.update("media.add") { project ->
                var p = TimelineEngine.addAssets(project, assets)
                for (asset in assets) {
                    p = when (asset.kind) {
                        MediaKind.VIDEO, MediaKind.IMAGE, MediaKind.GIF ->
                            TimelineEngine.appendClip(p, asset)

                        MediaKind.AUDIO -> appendAudioAsset(p, asset, atUs = playheadOrEnd(p))
                    }
                }
                p
            }
            done()
            val skipped = uris.size - assets.size
            if (skipped > 0) message("error_unsupported_media")
        }
    }

    private fun playheadOrEnd(project: Project): Long =
        _uiState.value.playheadUs.coerceAtMost(project.durationUs)

    private fun appendAudioAsset(project: Project, asset: MediaAsset, atUs: Long): Project {
        val item = AudioClipItem(
            assetId = asset.id,
            timelineStartUs = atUs,
            sourceInUs = 0L,
            sourceOutUs = asset.durationUs,
            durationUs = asset.durationUs,
        )
        return TimelineEngine.insertItem(project, null, TrackKind.AUDIO, item)
    }

    // endregion

    // region Voiceover

    fun startVoiceover() {
        val started = voiceRecorder.start()
        if (!started) {
            message("editor_record_permission")
            return
        }
        voiceoverStartUs = _uiState.value.playheadUs
        observeRecorder()
    }

    fun stopVoiceover() {
        val file: File? = voiceRecorder.stop()
        _uiState.value = _uiState.value.copy(recorderState = RecorderState.IDLE, recordDurationMs = 0L)
        if (file == null) return
        val doc = document ?: return
        viewModelScope.launch {
            val asset = metadataReader.readAsset(Uri.fromFile(file))
            if (asset == null) {
                message("error_unsupported_media")
                return@launch
            }
            doc.update("voiceover.add") { project ->
                val withAsset = TimelineEngine.addAssets(project, listOf(asset))
                appendAudioAsset(withAsset, asset, atUs = voiceoverStartUs)
            }
        }
    }

    fun cancelVoiceover() {
        voiceRecorder.cancel()
        _uiState.value = _uiState.value.copy(recorderState = RecorderState.IDLE, recordDurationMs = 0L)
    }

    private fun observeRecorder() {
        viewModelScope.launch {
            voiceRecorder.state.collect { state ->
                _uiState.value = _uiState.value.copy(
                    recorderState = state,
                    recordDurationMs = voiceRecorder.recordedDurationMs(),
                )
            }
        }
    }

    // endregion

    // region Structural edits

    fun splitAtPlayhead() {
        val doc = document ?: return
        val time = _uiState.value.playheadUs
        val position = TimelineQueries.playbackPositionAt(doc.project, time)
        val target = _uiState.value.selectedItemId
            ?: position?.clip?.id
            ?: return
        doc.update("item.split") { TimelineEngine.splitItem(it, target, time) }
    }

    fun deleteSelected() {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("item.delete") { TimelineEngine.deleteItem(it, id) }
        _uiState.value = _uiState.value.copy(selectedItemId = null)
    }

    fun duplicateSelected() {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("item.duplicate") { TimelineEngine.duplicateItem(it, id) }
    }

    fun moveItem(itemId: ItemId, newStartUs: Long) {
        document?.update("item.move") { TimelineEngine.moveItem(it, itemId, newStartUs) }
    }

    fun trimItem(itemId: ItemId, newStartUs: Long, trimFromStart: Boolean) {
        document?.update("item.trim") { project ->
            if (trimFromStart) {
                TimelineEngine.trimStart(project, itemId, newStartUs)
            } else {
                TimelineEngine.trimEnd(project, itemId, newStartUs)
            }
        }
    }

    fun setSpeed(speed: Float) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val model = SpeedModel.Constant(speed.coerceIn(0.1f, 8f))
        doc.update("speed.set") { TimelineEngine.setSpeed(it, id, model) }
    }

    fun setSpeedPreset(preset: SpeedCurvePreset) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val model = if (preset == SpeedCurvePreset.NORMAL) {
            SpeedModel.NORMAL
        } else {
            SpeedModel.Curve(curve = SpeedCurve.forPreset(preset), preset = preset)
        }
        doc.update("speed.curve") { TimelineEngine.setSpeed(it, id, model) }
    }

    fun setVolume(volume: Float) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("audio.volume") { TimelineEngine.setClipVolume(it, id, volume.coerceIn(0f, 4f)) }
    }

    fun setFades(fadeInUs: Long, fadeOutUs: Long) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("audio.fades") { TimelineEngine.setClipFades(it, id, fadeInUs, fadeOutUs) }
    }

    fun freezeFrame() {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("freeze.add") {
            TimelineEngine.addFreezeFrame(it, id, _uiState.value.playheadUs, 1_500_000L)
        }
    }

    fun detachAudio() {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("audio.detach") { TimelineEngine.detachAudio(it, id) }
    }

    // endregion

    // region Text & stickers

    fun addText(text: String) {
        val doc = document ?: return
        val item = TextItem(
            text = text,
            timelineStartUs = _uiState.value.playheadUs,
            durationUs = 3_000_000L,
        )
        doc.update("text.add") { TimelineEngine.insertItem(it, null, TrackKind.TEXT, item) }
        _uiState.value = _uiState.value.copy(selectedItemId = item.id)
    }

    fun updateSelectedText(text: String) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("text.edit") { TimelineEngine.updateText(it, id) { item -> item.copy(text = text) } }
    }

    fun setSelectedTextStyle(transform: (com.vitacut.core.model.TextStyle) -> com.vitacut.core.model.TextStyle) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("text.style") {
            TimelineEngine.updateText(it, id) { item -> item.copy(style = transform(item.style)) }
        }
    }

    fun setSelectedTextAnimation(
        inAnim: com.vitacut.core.model.TextAnimationIn? = null,
        outAnim: com.vitacut.core.model.TextAnimationOut? = null,
        loop: com.vitacut.core.model.TextAnimationLoop? = null,
    ) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("text.animation") {
            TimelineEngine.updateText(it, id) { item ->
                item.copy(
                    animations = item.animations.copy(
                        inAnimation = inAnim ?: item.animations.inAnimation,
                        outAnimation = outAnim ?: item.animations.outAnimation,
                        loopAnimation = loop ?: item.animations.loopAnimation,
                    ),
                )
            }
        }
    }

    fun addSticker(source: StickerSource) {
        val doc = document ?: return
        val item = StickerItem(
            source = source,
            timelineStartUs = _uiState.value.playheadUs,
            durationUs = 3_000_000L,
        )
        doc.update("sticker.add") { TimelineEngine.insertItem(it, null, TrackKind.STICKER, item) }
        _uiState.value = _uiState.value.copy(selectedItemId = item.id)
    }

    // endregion

    // region Look: filters, effects, grading, transitions, canvas

    fun setFilter(filterId: String?) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("filter.set") {
            TimelineEngine.setFilter(it, id, FilterState(filterId = filterId))
        }
    }

    fun setFilterIntensity(intensity: Float) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("filter.intensity") {
            TimelineEngine.setFilter(
                it,
                id,
                (it.item(id) as? VideoClipItem)?.filter?.copy(intensity = intensity) ?: FilterState.NONE,
            )
        }
    }

    fun addEffect(kind: EffectKind) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("effect.add") {
            TimelineEngine.addEffect(it, id, EffectInstance(kind = kind))
        }
    }

    fun removeEffect(effectId: String) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("effect.remove") { TimelineEngine.removeEffect(it, id, effectId) }
    }

    fun setEffectIntensity(effectId: String, intensity: Float) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("effect.intensity") {
            TimelineEngine.setEffectIntensity(it, id, effectId, intensity)
        }
    }

    fun setGrading(grading: com.vitacut.core.model.Grading) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("grading.set") { TimelineEngine.setGrading(it, id, grading) }
    }

    fun setTransition(kind: TransitionKind?, atEnd: Boolean, durationUs: Long = 500_000L) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val state = kind?.let { TransitionState(kind = it, durationUs = durationUs, atEnd = atEnd) }
        doc.update("transition.set") { TimelineEngine.setTransition(it, id, state, atEnd) }
    }

    fun setCanvas(canvas: com.vitacut.core.model.CanvasSettings) {
        document?.update("canvas.set") { TimelineEngine.setCanvas(it, canvas) }
    }

    fun setCanvasBackground(background: com.vitacut.core.model.CanvasBackground) {
        document?.update("canvas.background") { TimelineEngine.setCanvasBackground(it, background) }
    }

    fun setOpacity(opacity: Float) {
        updateTransform { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
    }

    /** Digital stabilize: crop the shaky edges and zoom in. Toggle restores full frame. */
    fun stabilizeSelected() {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val clip = doc.project.item(id) as? VideoClipItem ?: return
        val enable = clip.crop.isFullFrame
        doc.update("clip.stabilize") { project ->
            val nextTransform = clip.transform.copy(
                scaleX = if (enable) maxOf(clip.transform.scaleX, 1.08f) else 1f,
                scaleY = if (enable) maxOf(clip.transform.scaleY, 1.08f) else 1f,
            )
            val afterTransform = TimelineEngine.setTransform(project, id, nextTransform)
            TimelineEngine.setCrop(
                afterTransform,
                id,
                if (enable) {
                    com.vitacut.core.model.CropSettings(0.04f, 0.04f, 0.96f, 0.96f)
                } else {
                    com.vitacut.core.model.CropSettings.FULL
                },
            )
        }
    }

    fun updateTransform(transform: (com.vitacut.core.model.SpatialTransform) -> com.vitacut.core.model.SpatialTransform) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val item = doc.project.item(id) ?: return
        val current = when (item) {
            is VideoClipItem -> item.transform
            is TextItem -> item.transform
            is StickerItem -> item.transform
            else -> return
        }
        doc.update("transform.set") { TimelineEngine.setTransform(it, id, transform(current)) }
    }

    fun rotateSelected(deltaDegrees: Float = 90f) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("transform.rotate") { TimelineEngine.rotateItem(it, id, deltaDegrees) }
    }

    fun flipSelected(horizontal: Boolean) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("transform.flip") { TimelineEngine.flipItem(it, id, horizontal) }
    }

    fun setBlendMode(mode: com.vitacut.core.model.BlendMode) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("blend.set") { TimelineEngine.setBlendMode(it, id, mode) }
    }

    fun setMask(mask: com.vitacut.core.model.MaskSettings) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("mask.set") { TimelineEngine.setMask(it, id, mask) }
    }

    fun setChromaKey(chroma: com.vitacut.core.model.ChromaKeySettings) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("chroma.set") { TimelineEngine.setChromaKey(it, id, chroma) }
    }

    fun setAudioEffects(effects: com.vitacut.core.model.AudioEffects) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("audio.fx") { TimelineEngine.setAudioEffects(it, id, effects) }
    }

    fun setCrop(crop: com.vitacut.core.model.CropSettings) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("crop.set") { TimelineEngine.setCrop(it, id, crop) }
    }

    fun setContentFit(fit: com.vitacut.core.model.ContentFit) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        doc.update("fit.set") { TimelineEngine.setContentFit(it, id, fit) }
    }

    fun applyTextPreset(presetId: String) {
        val preset = com.vitacut.core.model.TextStyleLibrary.byId(presetId) ?: return
        val doc = document ?: return
        val id = _uiState.value.selectedItemId
        if (id == null) {
            addText("Text")
        }
        val target = _uiState.value.selectedItemId ?: return
        doc.update("text.preset") {
            TimelineEngine.updateText(it, target) { item ->
                item.copy(
                    style = preset.style,
                    animations = item.animations.copy(
                        inAnimation = preset.inAnimation,
                        outAnimation = preset.outAnimation,
                        loopAnimation = preset.loopAnimation,
                    ),
                )
            }
        }
    }

    fun addOverlay(uris: List<Uri>) {
        val doc = document ?: return
        if (uris.isEmpty()) return
        busy("media_importing")
        viewModelScope.launch {
            val assets = metadataReader.readAssets(uris).filterNotNull()
            if (assets.isEmpty()) {
                done()
                message("error_unsupported_media")
                return@launch
            }
            doc.update("overlay.add") { project ->
                var p = TimelineEngine.addAssets(project, assets)
                for (asset in assets) {
                    p = TimelineEngine.insertOverlayClip(p, asset, atUs = playheadOrEnd(p))
                }
                p
            }
            done()
        }
    }

    fun pasteAttributesFromPrevious() {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val clips = doc.project.videoClips()
        val index = clips.indexOfFirst { it.id == id }
        if (index <= 0) return
        doc.update("look.paste") { TimelineEngine.pasteAttributes(it, clips[index - 1].id, id) }
    }

    // endregion

    // region Keyframes

    fun addKeyframeAtPlayhead(property: KeyframeProperty, value: Float) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val item = doc.project.item(id) ?: return
        val localTime = (_uiState.value.playheadUs - item.timelineStartUs).coerceAtLeast(0L)
        doc.addKeyframe(id, property, localTime, value)
    }

    fun removeKeyframeAtPlayhead(property: KeyframeProperty) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val item = doc.project.item(id) ?: return
        val localTime = (_uiState.value.playheadUs - item.timelineStartUs).coerceAtLeast(0L)
        doc.removeKeyframe(id, property, localTime)
    }

    // endregion

    // region Captions

    fun generateCaptions() {
        val doc = document ?: return
        val audioUri = primaryAudioUri(doc.project)
        if (audioUri == null) {
            message("captions_no_speech_detected")
            return
        }
        busy("captions_generating")
        viewModelScope.launch {
            when (val result = transcriptionRegistry.transcribe(audioUri, doc.project.captions.languageTag) { p ->
                busyPercent((p * 100).toInt())
            }) {
                is VitaResult.Success -> {
                    val set = CaptionEditor.withEstimatedTimings(
                        result.data.toCaptionSet(doc.project.captions.style),
                    )
                    doc.setCaptionSet(set)
                    done()
                    if (result.data.needsReview) message("captions_review_hint")
                }

                is VitaResult.Failure -> {
                    done()
                    message(result.error.messageKey)
                }
            }
        }
    }

    /** First usable audio source: main video track audio, else the first AUDIO-track asset. */
    private fun primaryAudioUri(project: Project): Uri? {
        val videoAsset = project.tracks
            .filter { it.kind == TrackKind.VIDEO }
            .flatMap { it.items }
            .filterIsInstance<VideoClipItem>()
            .firstNotNullOfOrNull { clip -> project.asset(clip.assetId) }
        if (videoAsset != null && videoAsset.kind == MediaKind.VIDEO) {
            return Uri.parse(videoAsset.uri)
        }
        val audioAsset = project.tracks
            .filter { it.isAudioKind }
            .flatMap { it.items }
            .filterIsInstance<AudioClipItem>()
            .firstNotNullOfOrNull { project.asset(it.assetId) }
        return audioAsset?.let { Uri.parse(it.uri) }
    }

    fun setCaptionStyle(style: CaptionStyle) {
        val doc = document ?: return
        doc.setCaptionSet(doc.project.captions.copy(style = style))
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        val doc = document ?: return
        doc.setCaptionSet(doc.project.captions.copy(enabled = enabled))
    }

    fun updateCaptionText(captionId: com.vitacut.core.model.CaptionId, text: String) {
        val doc = document ?: return
        val existing = doc.project.captions.captions.firstOrNull { it.id == captionId } ?: return
        doc.updateCaption(existing.copy(text = text))
    }

    fun splitCaption(captionId: com.vitacut.core.model.CaptionId) {
        val doc = document ?: return
        val cue = doc.project.captions.captions.firstOrNull { it.id == captionId } ?: return
        val updated = CaptionEditor.split(doc.project.captions, captionId, (cue.startUs + cue.endUs) / 2)
        doc.setCaptionSet(updated)
    }

    fun deleteCaption(captionId: com.vitacut.core.model.CaptionId) {
        document?.deleteCaption(captionId)
    }

    /** Writes SRT/VTT into the cache and returns a shareable file (null on failure). */
    fun exportCaptions(vtt: Boolean): File? {
        val doc = document ?: return null
        val captions = doc.project.captions.captions
        if (captions.isEmpty()) return null
        return runCatching {
            val content = if (vtt) SubtitleImporter.exportVtt(captions) else SubtitleImporter.exportSrt(captions)
            val dir = File(previewController.context.cacheDir, "captions").apply { mkdirs() }
            val name = "${doc.project.name.ifBlank { "captions" }.replace(' ', '_')}.${if (vtt) "vtt" else "srt"}"
            File(dir, name).apply { writeText(content) }
        }.getOrNull()
    }

    fun importCaptions(content: String) {
        val doc = document ?: return
        val imported = SubtitleImporter.import(content)
        if (imported.isEmpty()) {
            message("error_unsupported_media")
            return
        }
        doc.setCaptionSet(doc.project.captions.copy(captions = imported))
    }

    // endregion

    // region AI tools

    fun removeSilences() {
        val doc = document ?: return
        val audioUri = primaryAudioUri(doc.project) ?: run {
            message("ai_no_silence_found")
            return
        }
        busy("ai_analyzing")
        viewModelScope.launch {
            when (val result = audioAnalyzers.detectSilences(audioUri)) {
                is VitaResult.Success -> {
                    val ranges = result.data.map { it.startUs..(it.endUs - 1) }
                    if (ranges.isEmpty()) {
                        message("ai_no_silence_found")
                    } else {
                        applySilenceRemoval(doc, ranges)
                        val seconds = result.data.sumOf { it.durationUs } / 1_000_000.0
                        message("ai_silence_found", listOf(String.format("%.1fs", seconds)))
                    }
                    done()
                }

                is VitaResult.Failure -> {
                    done()
                    message(result.error.messageKey)
                }
            }
        }
    }

    fun beatSync() {
        val doc = document ?: return
        val audioUri = primaryAudioUri(doc.project) ?: run {
            message("ai_beats_not_found")
            return
        }
        busy("ai_analyzing")
        viewModelScope.launch {
            when (val result = audioAnalyzers.detectBeats(audioUri)) {
                is VitaResult.Success -> {
                    val beats = result.data
                    if (beats.beatTimesUs.size < 4) {
                        message("ai_beats_not_found")
                    } else {
                        // Cut the main video at every beat within its extent.
                        val cuts = beats.beatTimesUs.filter { it < doc.project.durationUs }
                        applyAutoCut(doc, cuts, emptyList())
                        message("ai_beats_found", listOf(beats.bpm))
                    }
                    done()
                }

                is VitaResult.Failure -> {
                    done()
                    message(result.error.messageKey)
                }
            }
        }
    }

    fun autoCut() {
        val doc = document ?: return
        val audioUri = primaryAudioUri(doc.project) ?: run {
            message("ai_beats_not_found")
            return
        }
        busy("ai_analyzing")
        viewModelScope.launch {
            when (val result = audioAnalyzers.autoCutPlan(audioUri, doc.project.durationUs)) {
                is VitaResult.Success -> {
                    val plan = result.data
                    applyAutoCut(
                        doc,
                        plan.cutPointsUs,
                        plan.removeRanges.map { it.startUs..(it.endUs - 1) },
                    )
                    message("ai_autocut_applied", listOf(plan.cutPointsUs.size))
                    done()
                }

                is VitaResult.Failure -> {
                    done()
                    message(result.error.messageKey)
                }
            }
        }
    }

    fun autoReframe(targetAspect: Float) {
        val doc = document ?: return
        val clip = TimelineQueries.playbackPositionAt(doc.project, _uiState.value.playheadUs)?.clip
            ?: doc.project.tracks.filter { it.kind == TrackKind.VIDEO }
                .flatMap { it.items }.filterIsInstance<VideoClipItem>().firstOrNull()
        if (clip == null) {
            message("editor_empty_timeline")
            return
        }
        val asset = doc.project.asset(clip.assetId) ?: return
        busy("ai_analyzing")
        viewModelScope.launch {
            // Sample up to 8 frames across the clip, detect the primary subject per frame.
            val samples = 8
            val subjects = mutableListOf<AutoReframeAnalyzer.SubjectFrame>()
            val thumbProvider = previewController.thumbnailProvider
            for (i in 0 until samples) {
                val fraction = if (samples == 1) 0f else i.toFloat() / (samples - 1)
                val sourceTime = clip.sourceInUs +
                    ((clip.sourceOutUs - clip.sourceInUs) * fraction).toLong()
                val frame = thumbProvider.frameAt(asset.uri, sourceTime, 480)
                if (frame != null) {
                    val detections = objectDetector.detect(frame).getOrNull()
                    objectDetector.primarySubject(detections.orEmpty())?.let { subject ->
                        subjects += objectDetector.toSubjectFrame(
                            subject,
                            timeUs = (clip.durationUs * fraction).toLong(),
                        )
                    }
                    frame.recycle()
                }
                busyPercent(((i + 1) * 100) / samples)
            }
            val result = AutoReframeAnalyzer.analyze(
                subjects = subjects,
                sourceAspect = asset.displayWidth.toFloat() / asset.displayHeight.coerceAtLeast(1),
                targetAspect = targetAspect,
                durationUs = clip.durationUs,
            )
            applyAutoReframe(doc, clip.id, result.path, result.scale)
            done()
            message("ai_reframe_applied")
        }
    }

    fun removeBackgroundOfSelected() {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val clip = doc.project.item(id) as? VideoClipItem ?: return
        val asset = doc.project.asset(clip.assetId)
        if (asset == null || (asset.kind != MediaKind.IMAGE && asset.kind != MediaKind.VIDEO)) {
            message("ai_segmentation_failed")
            return
        }
        busy("ai_analyzing")
        viewModelScope.launch {
            val result = if (asset.kind == MediaKind.IMAGE) {
                subjectSegmenter.removeBackground(Uri.parse(asset.uri))
            } else {
                // Video: segment the current frame and add it as a still overlay.
                val frame = previewController.thumbnailProvider.frameAt(
                    asset.uri,
                    clip.sourceInUs + _uiState.value.playheadUs - clip.timelineStartUs,
                    720,
                )
                if (frame == null) {
                    VitaResult.Failure(com.vitacut.core.common.result.VitaError.CapabilityUnavailable("ai_segmentation_failed"))
                } else {
                    subjectSegmenter.removeBackground(frame)
                }
            }
            when (result) {
                is VitaResult.Success -> {
                    val dir = File(previewController.context.cacheDir, "cutouts").apply { mkdirs() }
                    val file = File(dir, "cutout_${System.currentTimeMillis()}.png")
                    val saved = runCatching {
                        file.outputStream().use { out ->
                            result.data.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                        file
                    }.getOrNull()
                    done()
                    if (saved == null) {
                        message("error_insufficient_storage")
                        return@launch
                    }
                    val cutoutAsset = metadataReader.readAsset(Uri.fromFile(saved))
                        ?: MediaAsset(
                            uri = Uri.fromFile(saved).toString(),
                            kind = MediaKind.IMAGE,
                            width = result.data.width,
                            height = result.data.height,
                        )
                    doc.update("ai.remove_bg") { project ->
                        val withAsset = TimelineEngine.addAssets(project, listOf(cutoutAsset))
                        val overlay = VideoClipItem(
                            assetId = cutoutAsset.id,
                            timelineStartUs = clip.timelineStartUs,
                            durationUs = clip.durationUs,
                        )
                        TimelineEngine.insertItem(withAsset, null, TrackKind.OVERLAY, overlay)
                    }
                    message("ai_bg_removed")
                }

                is VitaResult.Failure -> {
                    done()
                    message(result.error.messageKey)
                }
            }
        }
    }

    fun detectObjects() {
        val doc = document ?: return
        val position = TimelineQueries.playbackPositionAt(doc.project, _uiState.value.playheadUs)
            ?: return
        busy("ai_analyzing")
        viewModelScope.launch {
            val frame = previewController.thumbnailProvider.frameAt(
                position.asset.uri,
                position.sourceTimeUs,
                720,
            )
            if (frame == null) {
                done()
                message("error_generic_media")
                return@launch
            }
            val detections = objectDetector.detect(frame)
            frame.recycle()
            done()
            when (detections) {
                is VitaResult.Success -> message("ai_objects_found", listOf(detections.data.size))
                is VitaResult.Failure -> message(detections.error.messageKey)
            }
        }
    }

    fun trackSelectedSticker(region: NormalizedRegion) {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val sticker = doc.project.item(id) as? StickerItem ?: return
        val position = TimelineQueries.playbackPositionAt(doc.project, sticker.timelineStartUs)
            ?: return
        busy("tool_tracking")
        viewModelScope.launch {
            when (
                val result = motionTracker.track(
                    uri = Uri.parse(position.asset.uri),
                    region = region,
                    startTimeUs = position.sourceTimeUs,
                    endTimeUs = (position.sourceTimeUs +
                        (sticker.timelineEndUs - sticker.timelineStartUs)).coerceAtMost(position.asset.durationUs),
                ) { p -> busyPercent((p * 100).toInt()) }
            ) {
                is VitaResult.Success -> {
                    val bindingId = "track_${id.value}"
                    doc.update("tracking.bind") { project ->
                        val withPath = project.copy(
                            trackingPaths = project.trackingPaths + (bindingId to result.data),
                        )
                        TimelineEngine.mapItem(withPath, id) { item ->
                            if (item is StickerItem) item.copy(trackingBindingId = bindingId) else item
                        }
                    }
                    done()
                }

                is VitaResult.Failure -> {
                    done()
                    message(result.error.messageKey)
                }
            }
        }
    }

    // endregion

    // region Reverse (real implementation)

    fun reverseSelectedClip() {
        val doc = document ?: return
        val id = _uiState.value.selectedItemId ?: return
        val clip = doc.project.item(id) as? VideoClipItem ?: return
        val asset = doc.project.asset(clip.assetId) ?: return
        busy("tool_reversing")
        viewModelScope.launch {
            val outputFile = File(
                previewController.context.cacheDir,
                "reversed/${doc.project.id.value}_${id.value}.mp4",
            ).apply { parentFile?.mkdirs() }
            when (
                val result = reverseTranscoder.reverseSegment(
                    sourceUri = Uri.parse(asset.uri),
                    sourceInUs = clip.sourceInUs,
                    sourceOutUs = clip.sourceOutUs,
                    outputFile = outputFile,
                ) { p -> busyPercent((p * 100).toInt()) }
            ) {
                is VitaResult.Success -> {
                    val proxyUri = Uri.fromFile(result.data).toString()
                    doc.update("clip.reverse") {
                        TimelineEngine.setReversed(it, id, reversed = true, proxyUri = proxyUri)
                    }
                    done()
                }

                is VitaResult.Failure -> {
                    done()
                    message(result.error.messageKey)
                }
            }
        }
    }

    // endregion

    // region Save / exit

    fun requestBack(onExit: () -> Unit) {
        val doc = document
        if (doc == null || !doc.dirty.value) {
            onExit()
            return
        }
        _uiState.value = _uiState.value.copy(showDiscardDialog = true)
        pendingExit = onExit
    }

    private var pendingExit: (() -> Unit)? = null

    fun dismissDiscardDialog() {
        _uiState.value = _uiState.value.copy(showDiscardDialog = false)
        pendingExit = null
    }

    fun saveAndExit() {
        val doc = document
        _uiState.value = _uiState.value.copy(showDiscardDialog = false, saving = true)
        viewModelScope.launch {
            if (doc != null) runCatching { commitProject(doc) }
            runCatching { previewController.release() }
            _uiState.value = _uiState.value.copy(saving = false)
            pendingExit?.invoke()
            pendingExit = null
        }
    }

    fun exitWithoutSaving() {
        _uiState.value = _uiState.value.copy(showDiscardDialog = false)
        viewModelScope.launch {
            runCatching { previewController.release() }
            pendingExit?.invoke()
            pendingExit = null
        }
    }

    // endregion

    // region helpers

    private fun busy(key: String) {
        _uiState.value = _uiState.value.copy(busyKey = key, busyPercent = -1)
    }

    private fun busyPercent(percent: Int) {
        _uiState.value = _uiState.value.copy(busyPercent = percent)
    }

    private fun done() {
        _uiState.value = _uiState.value.copy(busyKey = null, busyPercent = -1)
    }

    private fun message(key: String, args: List<Any> = emptyList()) {
        _uiState.value = _uiState.value.copy(messageKey = key, messageArgs = args)
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(messageKey = null, messageArgs = emptyList())
    }

    fun consumeLoadError() {
        _uiState.value = _uiState.value.copy(loadErrorKey = null)
    }

    override fun onCleared() {
        super.onCleared()
        previewController.release()
    }

    // endregion
}

/** Kept for reference: caption karaoke toggle used by the captions sheet. */
fun CaptionSet.withKaraoke(enabled: Boolean): CaptionSet = copy(
    style = style.copy(
        animation = if (enabled) CaptionAnimation.KARAOKE else CaptionAnimation.NONE,
    ),
)
