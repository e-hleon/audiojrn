package es.hector.audio_diary

import android.content.Context
import android.media.MediaRecorder
import java.io.File
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AudioRecorder {
    fun start(): CapturedAudio
    fun stop(): CapturedAudio
    fun discard()
    fun pause() {}
    fun resume() {}
    fun maxAmplitude(): Int = 0
    fun pending(): CapturedAudio? = null
}

fun recoverManualCaptures(directory: File): List<CapturedAudio> = directory.listFiles().orEmpty()
    .filter { it.name.startsWith("capture-") && it.extension == "m4a" }
    .sortedBy { it.name }.map { file ->
        val timestamp = file.name.removePrefix("capture-").substringBefore('-').toLongOrNull()
        CapturedAudio(file, Instant.ofEpochMilli(timestamp ?: file.lastModified()),
            captureChunkId = java.util.UUID.nameUUIDFromBytes(file.name.toByteArray()).toString())
    }

fun recoverManualCapture(directory: File): CapturedAudio? = recoverManualCaptures(directory).firstOrNull()

data class ManualPendingRetryResult(val attempted: Int, val processed: Int, val remaining: Int)
private val manualPendingRetryMutex = Mutex()
suspend fun retryPendingManualCaptures(
    directory: File,
    upload: suspend (CapturedAudio) -> Unit,
): ManualPendingRetryResult = retryPendingManualCaptures(recoverManualCaptures(directory), upload)

suspend fun retryPendingManualCaptures(
    pending: List<CapturedAudio>,
    upload: suspend (CapturedAudio) -> Unit,
): ManualPendingRetryResult = manualPendingRetryMutex.withLock {
    val existing = pending.filter { it.file.exists() }
    var processed = 0
    existing.forEach { audio ->
        try {
            upload(audio)
            if (audio.file.delete() || !audio.file.exists()) processed++
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }
    ManualPendingRetryResult(existing.size, processed, existing.count { it.file.exists() })
}

fun clearAbandonedCaptures(cacheDir: File) {
    cacheDir.listFiles { file -> file.name.startsWith("capture-") && file.name.endsWith(".m4a") }
        ?.forEach { it.delete() }
}

const val MANUAL_MAX_BYTES = 40L * 1024 * 1024
class MediaRecorderAudioRecorder(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val onLimitReached: () -> Unit = {},
) : AudioRecorder {
    private val directory = File(context.filesDir, "pending-manual").apply { mkdirs() }
    override fun pending(): CapturedAudio? {
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("capture-") && it.extension == "m4a" }.forEach { old ->
            val destination = File(directory, old.name)
            if (!destination.exists()) old.renameTo(destination)
        }
        return recoverManualCapture(directory)
    }
    private var recorder: MediaRecorder? = null
    private var captured: CapturedAudio? = null
    override fun start(): CapturedAudio {
        check(recorder == null) { "Ya hay una grabación activa" }
        val startedAt = Instant.now(clock)
        val file = File.createTempFile("capture-${startedAt.toEpochMilli()}-", ".m4a", directory)
        val media = MediaRecorder()
        try {
            media.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setMaxDuration(30 * 60 * 1_000)
                setMaxFileSize(MANUAL_MAX_BYTES)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimitReached()
                }
                setOutputFile(file.absolutePath); prepare(); start()
            }
            return CapturedAudio(file, startedAt, captureChunkId = java.util.UUID.nameUUIDFromBytes(file.name.toByteArray()).toString()).also { captured = it; recorder = media }
        } catch (error: Exception) { media.release(); file.delete(); throw error }
    }
    override fun stop(): CapturedAudio {
        val current = checkNotNull(captured) { "No hay grabación activa" }
        try { recorder?.stop() } finally { recorder?.release(); recorder = null; captured = null }
        return current
    }
    override fun pause() { checkNotNull(recorder).pause() }
    override fun resume() { checkNotNull(recorder).resume() }
    override fun maxAmplitude(): Int = recorder?.maxAmplitude ?: 0
    override fun discard() { runCatching { recorder?.stop() }; recorder?.release(); recorder = null; captured?.file?.delete(); captured = null }
}
