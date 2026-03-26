package com.mikeyb.electricsheepcamera

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class SheepCameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SheepCameraUiState())
    val uiState: StateFlow<SheepCameraUiState> = _uiState

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)

    // ── basic controls ────────────────────────────────────────────────────────

    fun setIntensity(value: Float) = _uiState.update { it.copy(intensity = value) }
    fun nextOverlay() = _uiState.update { it.copy(overlayIndex = (it.overlayIndex + 1) % 8) }
    fun setMode(mode: CameraMode) = _uiState.update { it.copy(mode = mode) }
    fun pulse() = _uiState.update {
        val next = if (it.pulse > 0.8f) 0f else it.pulse + 0.22f
        it.copy(pulse = next)
    }

    // ── camera flip ───────────────────────────────────────────────────────────
    fun toggleCamera() = _uiState.update { it.copy(useFrontCamera = !it.useFrontCamera) }

    // ── video recording ───────────────────────────────────────────────────────
    fun setRecording(recording: Boolean) = _uiState.update { it.copy(isRecording = recording) }

    // ── face tracking ─────────────────────────────────────────────────────────
    fun updateFaces(faces: List<TrackedFace>) = _uiState.update { it.copy(trackedFaces = faces) }

    // ── audio reactive ────────────────────────────────────────────────────────
    fun startAudioReactive() {
        if (audioJob?.isActive == true) return
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            ).also { it.startRecording() }

            audioJob = viewModelScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize / 2)
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        val rms = sqrt(buffer.take(read).fold(0.0) { acc, s ->
                            acc + (s.toDouble() * s.toDouble())
                        } / read).toFloat()
                        val normalised = (rms / 4000f).coerceIn(0f, 1f)
                        val peak = buffer.take(read).maxOfOrNull { abs(it.toInt()) }
                            ?.let { it / 32768f } ?: 0f
                        _uiState.update { it.copy(audioLevel = normalised, audioPeak = peak) }
                    }
                    delay(30)
                }
            }
            _uiState.update { it.copy(audioReactive = true) }
        } catch (e: SecurityException) {
            // Permission not yet granted
        }
    }

    fun stopAudioReactive() {
        audioJob?.cancel()
        audioJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _uiState.update { it.copy(audioReactive = false, audioLevel = 0f, audioPeak = 0f) }
    }

    fun toggleAudioReactive() {
        if (_uiState.value.audioReactive) stopAudioReactive() else startAudioReactive()
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioReactive()
    }
}

data class SheepCameraUiState(
    val intensity: Float = 0.45f,
    val overlayIndex: Int = 0,
    val pulse: Float = 0f,
    val mode: CameraMode = CameraMode.DREAM,
    val useFrontCamera: Boolean = false,
    val isRecording: Boolean = false,
    val audioReactive: Boolean = false,
    val audioLevel: Float = 0f,
    val audioPeak: Float = 0f,
    val trackedFaces: List<TrackedFace> = emptyList()
)

data class TrackedFace(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val confidence: Float = 1f
)

enum class CameraMode(val label: String) {
    DREAM("Dream"),
    GLITCH("Glitch"),
    NEURAL("Neural")
}
