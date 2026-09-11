package es.hector.audio_diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState { data object Idle : UiState; data class Recording(val seconds: Int) : UiState; data class Ready(val audio: CapturedAudio) : UiState; data class Processing(val audio: CapturedAudio) : UiState; data class Success(val response: ProcessResponse) : UiState; data class Error(val message: String, val audio: CapturedAudio?) : UiState }
class CaptureViewModel(private val recorder: AudioRecorder, private val backend: BackendRepository, private val defaultMaxSeconds: Int = 30 * 60) : ViewModel() {
    private val mutableState = MutableStateFlow<UiState>(UiState.Idle); val state: StateFlow<UiState> = mutableState.asStateFlow(); private var timer: Job? = null
    private var lastMaxSeconds = defaultMaxSeconds.coerceIn(1, 30 * 60)
    init { recorder.pending()?.let { mutableState.value = UiState.Ready(it) } }
    fun startRecording(maxSeconds: Int = lastMaxSeconds) = runCatching { require(maxSeconds in 1..30 * 60); lastMaxSeconds = maxSeconds; recorder.start() }.onSuccess { audio ->
        mutableState.value = UiState.Recording(0); timer = viewModelScope.launch { repeat(maxSeconds) { delay(1000); mutableState.value = UiState.Recording(it + 1) }; stopRecording() }
    }.onFailure { mutableState.value = UiState.Error("No se pudo iniciar el micrófono", null) }
    fun stopRecording() { if (state.value !is UiState.Recording) return; timer?.cancel(); runCatching { recorder.stop() }.onSuccess { mutableState.value = UiState.Ready(it) }.onFailure { mutableState.value = UiState.Error("No se pudo detener la grabación", null) } }
    fun send(url: String) {
        val audio: CapturedAudio = when (val current = state.value) {
            is UiState.Ready -> current.audio
            is UiState.Error -> current.audio ?: return
            else -> return
        }
        mutableState.value = UiState.Processing(audio)
        viewModelScope.launch { runCatching { backend.process(audio, url) }.onSuccess { audio.file.delete(); mutableState.value = UiState.Success(it) }.onFailure { mutableState.value = UiState.Error(errorMessage(it), audio) } }
    }
    fun discard() { timer?.cancel(); recorder.discard(); (state.value as? UiState.Ready)?.audio?.file?.delete(); (state.value as? UiState.Error)?.audio?.file?.delete(); mutableState.value = UiState.Idle }
    fun newRecording() {
        if (state.value is UiState.Success) {
            recorder.pending()?.let { mutableState.value = UiState.Ready(it) } ?: startRecording()
        }
    }
    override fun onCleared() { if (state.value is UiState.Recording) discard() }
    private fun errorMessage(error: Throwable) = when (error) { is IllegalArgumentException -> "URL del backend no válida"; is SecurityException -> "No se puede acceder a la red; comprueba el permiso de Internet"; is java.net.SocketTimeoutException -> "El procesamiento tardó demasiado"; is java.net.ConnectException -> "No se puede conectar con el servidor"; is retrofit2.HttpException -> if (error.code() in 400..499) "El servidor rechazó el audio" else "El servidor no pudo procesar el audio"; else -> "Se produjo un error durante el procesamiento" }
}
