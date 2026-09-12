package app.audiojrn

import android.app.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

enum class CaptureSessionState {
    IDLE, RECORDING_MANUAL, PAUSED_MANUAL, RECORDING_CONTINUOUS, PAUSED_CONTINUOUS, FINALIZING
}

fun CaptureSessionState.isRecording() = this == CaptureSessionState.RECORDING_MANUAL || this == CaptureSessionState.RECORDING_CONTINUOUS
fun CaptureSessionState.isPaused() = this == CaptureSessionState.PAUSED_MANUAL || this == CaptureSessionState.PAUSED_CONTINUOUS
fun CaptureSessionState.isActiveSession() = isRecording() || isPaused() || this == CaptureSessionState.FINALIZING
fun recordingCaptureState(mode: CaptureMode) = if (mode == CaptureMode.MANUAL) CaptureSessionState.RECORDING_MANUAL else CaptureSessionState.RECORDING_CONTINUOUS
fun pausedCaptureState(mode: CaptureMode) = if (mode == CaptureMode.MANUAL) CaptureSessionState.PAUSED_MANUAL else CaptureSessionState.PAUSED_CONTINUOUS
fun activeElapsedSeconds(accumulatedMs: Long, activeStartedAtMs: Long?, nowMs: Long): Long =
    (accumulatedMs + (activeStartedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)) / 1_000
fun globalRecordingLabel(state: CaptureSessionState, mode: CaptureMode?): String? = if (!state.isActiveSession() || mode == null) null else
    "${if (state.isPaused()) "Pausado" else "Grabando"} · ${if (mode == CaptureMode.CONTINUOUS) "Continuo" else "Manual"}"

data class CaptureRuntimeSnapshot(
    val state: CaptureSessionState,
    val mode: CaptureMode?,
    val elapsedSeconds: Long,
    val currentSessionId: String?,
)

/** Pending Manual captures are archived work and never alter the current capture state. */
fun withDiscoveredPendingManual(snapshot: CaptureRuntimeSnapshot, @Suppress("UNUSED_PARAMETER") hasPending: Boolean): CaptureRuntimeSnapshot = snapshot

data class PendingStartResult(val modeToStart: CaptureMode?, val remaining: CaptureMode?)
fun consumePendingStart(pending: CaptureMode?, permissionGranted: Boolean): PendingStartResult =
    PendingStartResult(pending.takeIf { permissionGranted }, null)
enum class CapturePermissionStep { NONE, REQUEST_MICROPHONE, REQUEST_NOTIFICATIONS, START }
fun nextCapturePermissionStep(
    pending: CaptureMode?,
    microphoneGranted: Boolean,
    notificationRuntimePermission: Boolean,
    notificationGranted: Boolean,
    notificationDecisionRequested: Boolean,
): CapturePermissionStep = when {
    pending == null -> CapturePermissionStep.NONE
    !microphoneGranted -> CapturePermissionStep.REQUEST_MICROPHONE
    notificationRuntimePermission && !notificationGranted && !notificationDecisionRequested -> CapturePermissionStep.REQUEST_NOTIFICATIONS
    else -> CapturePermissionStep.START
}
fun pendingCaptureRetryMessage(pendingBefore: Int, backendAvailable: Boolean, pendingAfter: Int): String = when {
    pendingBefore == 0 -> "No hay capturas pendientes"
    !backendAvailable -> "Configura una URL válida para procesarlas"
    pendingAfter == 0 -> "Capturas pendientes procesadas"
    else -> "Quedan capturas pendientes para reintentar"
}
fun clearedCaptureError(): String? = null
fun continuousFinishMessage(backendAvailable: Boolean, remainsPending: Boolean): String =
    if (!backendAvailable || remainsPending) "Captura guardada; pendiente de procesar" else "Captura enviada para procesar"
fun newContinuousSessionId(previous: String? = null): String = generateSequence { UUID.randomUUID().toString() }
    .first { it != previous }

class CaptureUiEvents {
    private val channel = Channel<String>(Channel.BUFFERED)
    val flow = channel.receiveAsFlow()
    fun emit(message: String) { channel.trySend(message) }
}

class CaptureForegroundService : Service() {
    companion object {
        const val ACTION_START_MANUAL = "app.audiojrn.START_MANUAL"
        const val ACTION_START_CONTINUOUS = "app.audiojrn.START_CONTINUOUS"
        const val ACTION_START = ACTION_START_CONTINUOUS
        const val ACTION_PAUSE = "app.audiojrn.PAUSE_CAPTURE"
        const val ACTION_RESUME = "app.audiojrn.RESUME_CAPTURE"
        const val ACTION_FINISH = "app.audiojrn.FINISH_CAPTURE"
        const val ACTION_STOP = ACTION_FINISH
        const val ACTION_DISCARD = "app.audiojrn.DISCARD_CAPTURE"
        const val ACTION_PROCESS_NOW = "app.audiojrn.PROCESS_CAPTURE_NOW"
        const val EXTRA_BACKEND = "backend"
        const val MANUAL_MAX_SECONDS = 30 * 60
        private const val CHANNEL = "capture"
        private const val NOTIFICATION = 42
        private const val WAVEFORM_CAPACITY = 96

        private val mutableState = MutableStateFlow(CaptureSessionState.IDLE); val state = mutableState.asStateFlow()
        private val mutableMode = MutableStateFlow<CaptureMode?>(null); val mode = mutableMode.asStateFlow()
        private val mutableElapsedSeconds = MutableStateFlow(0L); val elapsedSeconds = mutableElapsedSeconds.asStateFlow()
        private val mutableWaveform = MutableStateFlow<List<Float>>(emptyList()); val waveform = mutableWaveform.asStateFlow()
        private val mutableError = MutableStateFlow<String?>(null); val error = mutableError.asStateFlow()
        private val mutableCanProcess = MutableStateFlow(false); val canProcess = mutableCanProcess.asStateFlow()
        private val mutableCurrentSessionId = MutableStateFlow<String?>(null); val currentSessionId = mutableCurrentSessionId.asStateFlow()
        val running = MutableStateFlow(false)
        val stopping = MutableStateFlow(false)
        val startedAtElapsedRealtime = MutableStateFlow<Long?>(null)
        private val uiEvents = CaptureUiEvents(); val events = uiEvents.flow

        fun showMessage(message: String) { uiEvents.emit(message) }
    }

    private enum class EndOperation { NONE, PAUSE, FINISH }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var uploadJob: Job? = null
    private var timerJob: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var endOperation = EndOperation.NONE
    @Volatile private var processRequested = false
    private var manualRecorder: MediaRecorderAudioRecorder? = null
    private var activeAccumulatedMs = 0L
    private var activeStartedAtMs: Long? = null
    private lateinit var queue: SegmentQueue
    private lateinit var directory: File
    private lateinit var finalizations: PendingFinalizations
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var uploader: SegmentUploadCoordinator? = null
    private var backend: BackendRepository? = null
    private var backendUrl = ""

    override fun onCreate() {
        super.onCreate()
        directory = pendingSegmentDirectory(filesDir, cacheDir)
        queue = SegmentQueue(directory, 256)
        finalizations = PendingFinalizations(File(filesDir, "pending-finalizations"))
        manualRecorder = MediaRecorderAudioRecorder(applicationContext, onLimitReached = { finishCapture() })
        manualRecorder?.pending()
        migrateLegacyFinalizePreference()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Captura de audio", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_BACKEND) == true) backendUrl = intent.getStringExtra(EXTRA_BACKEND).orEmpty()
        if (mutableMode.value == CaptureMode.CONTINUOUS && mutableState.value.isActiveSession() && backend == null && prepareBackendIfAvailable()) {
            ensureUploadLoop()
        }
        when (intent?.action) {
            ACTION_START_MANUAL -> startManual()
            ACTION_START_CONTINUOUS -> startContinuousSession()
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
            ACTION_FINISH -> finishCapture()
            ACTION_DISCARD -> discardCapture()
            ACTION_PROCESS_NOW -> if (mutableState.value == CaptureSessionState.RECORDING_CONTINUOUS && mutableCanProcess.value) processRequested = true
        }
        return START_NOT_STICKY
    }

    private fun prepareCapture(mode: CaptureMode) {
        check(mutableState.value == CaptureSessionState.IDLE)
        mutableMode.value = mode; mutableError.value = null; mutableWaveform.value = emptyList()
        mutableElapsedSeconds.value = 0; activeAccumulatedMs = 0; activeStartedAtMs = SystemClock.elapsedRealtime()
        startedAtElapsedRealtime.value = activeStartedAtMs; running.value = true; stopping.value = false
        prepareBackendIfAvailable()
        startTimer()
    }

    private fun prepareBackendIfAvailable(): Boolean {
        val normalized = backendUrlIfAvailable(backendUrl)
        if (normalized == null) { backend = null; uploader = null; return false }
        backendUrl = normalized
        backend = RetrofitBackendRepository()
        uploader = SegmentUploadCoordinator(queue) { item ->
            backend!!.process(CapturedAudio(item.file, item.recordedAt, item.captureMode, item.captureSessionId, item.chunkIndex, item.captureChunkId), backendUrl)
        }
        return true
    }

    private fun startManual() {
        if (mutableState.value != CaptureSessionState.IDLE) return
        runCatching {
            prepareCapture(CaptureMode.MANUAL); manualRecorder!!.start()
            mutableState.value = recordingCaptureState(CaptureMode.MANUAL); startForegroundNotification()
        }.onFailure { failStart("No se pudo iniciar el micrófono") }
    }

    private fun startContinuousSession() {
        if (mutableState.value != CaptureSessionState.IDLE) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableError.value = "No se puede acceder al micrófono"
            showMessage("Concede el permiso de micrófono para iniciar la captura")
            return
        }
        runCatching {
            prepareCapture(CaptureMode.CONTINUOUS); mutableState.value = recordingCaptureState(CaptureMode.CONTINUOUS)
            startForegroundNotification(); if (backend != null) ensureUploadLoop(); startContinuousBlock()
        }.onFailure { failStart("No se pudo iniciar la captura") }
    }

    private fun startContinuousBlock() {
        endOperation = EndOperation.NONE; processRequested = false; activeStartedAtMs = SystemClock.elapsedRealtime()
        mutableState.value = recordingCaptureState(CaptureMode.CONTINUOUS); updateNotification()
        captureJob = scope.launch {
            try { continuousLoop() }
            catch (error: Throwable) {
                if (error !is CancellationException && endOperation == EndOperation.NONE) {
                    mutableError.value = "Captura interrumpida; el audio pendiente se conserva"
                    endOperation = EndOperation.FINISH
                    completeContinuousStop()
                }
            }
        }
    }

    private suspend fun continuousLoop() {
        val minimum = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is not granted")
        }
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, AUDIO_FRAME_SAMPLES * 8))
        check(record.state == AudioRecord.STATE_INITIALIZED); audioRecord = record
        val source: PcmAudioSource = AndroidAudioRecordSource(record); val buffer = ShortArray(AUDIO_FRAME_SAMPLES * 4)
        val captureStart = Instant.now(); var emitted = 0L; var sessionId = newContinuousSessionId(); var index = 0
        var chunkStart = captureStart; var chunker = ContinuousChunker(); var autoClose = SemanticBlockAutoClose()
        mutableCurrentSessionId.value = sessionId
        fun emit(samples: ShortArray) {
            save(samples, chunkStart, sessionId, index++); emitted += samples.size
            chunkStart = chunkStartForSampleOffset(captureStart, emitted)
        }
        fun close(feedback: String? = null) {
            if (index > 0) { finalizations.remember(sessionId, index - 1); wakeups.trySend(Unit) }
            sessionId = newContinuousSessionId(sessionId); mutableCurrentSessionId.value = sessionId; index = 0
            mutableCanProcess.value = false; autoClose = SemanticBlockAutoClose(); feedback?.let(uiEvents::emit)
        }
        try {
            source.start()
            while (endOperation == EndOperation.NONE && currentCoroutineContext().isActive) {
                val count = source.read(buffer)
                if (count <= 0) { if (endOperation != EndOperation.NONE) break else continue }
                val samples = buffer.copyOf(count)
                mutableWaveform.value = appendAmplitude(mutableWaveform.value, normalizedPcmAmplitude(samples), WAVEFORM_CAPACITY)
                mutableCanProcess.value = true; chunker.add(samples).forEach(::emit)
                if (autoClose.add(samples)) { chunker.flush()?.let(::emit); close(); chunker = ContinuousChunker() }
                if (processRequested) {
                    processRequested = false; chunker.flush()?.let(::emit)
                    close("Bloque enviado para procesar; la grabación continúa"); chunker = ContinuousChunker()
                }
            }
        } finally {
            runCatching { chunker.flush()?.let(::emit) }
            val completedId = sessionId; val lastIndex = index - 1
            source.stopAndRelease(); audioRecord = null; mutableCanProcess.value = false
            when (endOperation) {
                EndOperation.PAUSE, EndOperation.FINISH -> if (lastIndex >= 0) { finalizations.remember(completedId, lastIndex); wakeups.trySend(Unit) }
                EndOperation.NONE -> Unit
            }
            mutableCurrentSessionId.value = null
            when (endOperation) {
                EndOperation.PAUSE -> { mutableState.value = CaptureSessionState.PAUSED_CONTINUOUS; updateNotification(); uiEvents.emit("Captura pausada; el bloque anterior se ha enviado para procesar") }
                EndOperation.FINISH -> completeContinuousStop()
                EndOperation.NONE -> Unit
            }
        }
    }

    private fun pauseCapture() = when (mutableState.value) {
        CaptureSessionState.RECORDING_MANUAL -> runCatching {
            accumulateActiveTime(); manualRecorder!!.pause(); mutableState.value = CaptureSessionState.PAUSED_MANUAL; updateNotification()
        }.onFailure { activeStartedAtMs = SystemClock.elapsedRealtime(); mutableError.value = "No se pudo pausar la grabación" }.let { Unit }
        CaptureSessionState.RECORDING_CONTINUOUS -> { accumulateActiveTime(); mutableState.value = CaptureSessionState.FINALIZING; endOperation = EndOperation.PAUSE; runCatching { audioRecord?.stop() }; Unit }
        else -> Unit
    }

    private fun resumeCapture() = when (mutableState.value) {
        CaptureSessionState.PAUSED_MANUAL -> runCatching {
            manualRecorder!!.resume(); activeStartedAtMs = SystemClock.elapsedRealtime(); mutableState.value = CaptureSessionState.RECORDING_MANUAL; updateNotification()
        }.onFailure { mutableError.value = "No se pudo reanudar la grabación" }.let { Unit }
        CaptureSessionState.PAUSED_CONTINUOUS -> startContinuousBlock()
        else -> Unit
    }

    private fun finishCapture() = when (mutableState.value) {
        CaptureSessionState.RECORDING_MANUAL, CaptureSessionState.PAUSED_MANUAL -> finishManual()
        CaptureSessionState.RECORDING_CONTINUOUS -> { accumulateActiveTime(); mutableState.value = CaptureSessionState.FINALIZING; stopping.value = true; endOperation = EndOperation.FINISH; runCatching { audioRecord?.stop() }; Unit }
        CaptureSessionState.PAUSED_CONTINUOUS -> completeContinuousStop()
        else -> Unit
    }

    private fun discardCapture() = when (mutableState.value) {
        CaptureSessionState.RECORDING_MANUAL, CaptureSessionState.PAUSED_MANUAL -> {
            if (mutableState.value.isRecording()) accumulateActiveTime()
            manualRecorder?.discard(); mutableError.value = clearedCaptureError(); completeStop()
        }
        else -> Unit
    }

    private fun finishManual() {
        if (mutableState.value == CaptureSessionState.RECORDING_MANUAL) accumulateActiveTime()
        runCatching { manualRecorder!!.stop() }.getOrElse { mutableError.value = "No se pudo finalizar la grabación"; return }
        val normalized = backendUrlIfAvailable(backendUrl)
        val pendingAtFinish = recoverManualCaptures(File(filesDir, "pending-manual"))
        resetCaptureState()
        if (normalized == null) {
            uiEvents.emit("Grabación guardada; pendiente de procesar")
            stopSelf()
            return
        }
        scope.launch {
            val repository = RetrofitBackendRepository()
            val result = retryPendingManualCaptures(pendingAtFinish) {
                repository.process(it, normalized)
            }
            uiEvents.emit(if (result.remaining == 0) "Grabación enviada para procesar" else "Grabación guardada; pendiente de procesar")
            if (!mutableState.value.isActiveSession()) stopSelf()
        }
    }

    private fun completeContinuousStop() { scope.launch {
        val backendAvailable = backend != null
        wakeups.trySend(Unit); uploader?.drain(); backend?.let { finalizations.retry(it, backendUrl) }
        val remainsPending = queue.size > 0 || finalizations.pending().isNotEmpty()
        uiEvents.emit(continuousFinishMessage(backendAvailable, remainsPending))
        completeStop()
    } }
    private fun completeStop() {
        resetCaptureState()
        stopSelf()
    }
    private fun resetCaptureState() {
        timerJob?.cancel(); activeStartedAtMs = null; mutableState.value = CaptureSessionState.IDLE; mutableMode.value = null
        mutableCurrentSessionId.value = null; mutableCanProcess.value = false; running.value = false; stopping.value = false
        startedAtElapsedRealtime.value = null; stopForeground(STOP_FOREGROUND_REMOVE)
    }
    private fun accumulateActiveTime() {
        val now = SystemClock.elapsedRealtime(); activeStartedAtMs?.let { activeAccumulatedMs += (now - it).coerceAtLeast(0) }
        activeStartedAtMs = null; mutableElapsedSeconds.value = activeAccumulatedMs / 1_000
    }
    private fun startTimer() {
        timerJob?.cancel(); timerJob = scope.launch {
            while (isActive) {
                mutableElapsedSeconds.value = activeElapsedSeconds(activeAccumulatedMs, activeStartedAtMs, SystemClock.elapsedRealtime())
                if (mutableState.value == CaptureSessionState.RECORDING_MANUAL) {
                    mutableWaveform.value = appendAmplitude(mutableWaveform.value, manualRecorder!!.maxAmplitude().toFloat() / Short.MAX_VALUE, WAVEFORM_CAPACITY)
                    if (mutableElapsedSeconds.value >= MANUAL_MAX_SECONDS) { finishCapture(); break }
                }
                delay(75)
            }
        }
    }
    private fun ensureUploadLoop() {
        if (uploadJob?.isActive == true) return
        uploadJob = scope.launch { wakeups.trySend(Unit); while (isActive) { wakeups.receive(); uploader?.drain(); backend?.let { finalizations.retry(it, backendUrl) } } }
    }
    private fun save(samples: ShortArray, at: Instant, sessionId: String, index: Int) {
        val chunkId = UUID.randomUUID().toString(); val file = File(directory, "segment-${at.toEpochMilli()}-continuous-$sessionId-$index-$chunkId.wav")
        WavWriter.write(file, samples); finalizations.recordChunk(sessionId, index)
        val pending = PendingSegment(file, at, "continuous", sessionId, index, chunkId)
        if (!queue.offer(pending)) { queue.requeue(pending); mutableError.value = "Cola llena: la captura se detiene y el audio se conserva"; endOperation = EndOperation.FINISH }
        wakeups.trySend(Unit)
    }
    private fun startForegroundNotification() {
        val value = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIFICATION, value)
    }
    private fun updateNotification() { if (mutableState.value.isActiveSession()) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }
    private fun notification(): Notification {
        val paused = mutableState.value.isPaused(); val label = if (mutableMode.value == CaptureMode.MANUAL) "Manual" else "Continuo"
        fun command(action: String, request: Int) = PendingIntent.getService(this, request, Intent(this, javaClass).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_CAPTURE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("${if (paused) "Pausado" else "Grabando"} · $label").setContentText("Toca para volver a Captura")
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (paused) "REANUDAR" else "PAUSAR", command(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "FINALIZAR", command(ACTION_FINISH, 2))
        if (mutableMode.value == CaptureMode.CONTINUOUS && !paused) builder.addAction(0, "PROCESAR", command(ACTION_PROCESS_NOW, 3))
        if (!paused) builder.setWhen(System.currentTimeMillis() - (activeAccumulatedMs + (activeStartedAtMs?.let { SystemClock.elapsedRealtime() - it } ?: 0))).setUsesChronometer(true)
        else builder.setShowWhen(false)
        return builder.build()
    }
    private fun failStart(message: String) { mutableError.value = message; manualRecorder?.discard(); completeStop() }
    private fun migrateLegacyFinalizePreference() {
        val preferences = getSharedPreferences("audiojrn", MODE_PRIVATE); val session = preferences.getString("pending_finalize_session", null); val index = preferences.getInt("pending_finalize_last_index", -1)
        if (session != null && index >= 0) { finalizations.remember(session, index); preferences.edit().remove("pending_finalize_session").remove("pending_finalize_last_index").commit() }
    }
    override fun onDestroy() {
        captureJob?.cancel(); timerJob?.cancel(); audioRecord?.let { runCatching { it.stop() } }
        if (mutableState.value.isActiveSession()) { mutableState.value = CaptureSessionState.IDLE; mutableMode.value = null; running.value = false }
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
