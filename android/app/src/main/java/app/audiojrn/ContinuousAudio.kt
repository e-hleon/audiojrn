package app.audiojrn

import android.media.AudioRecord
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

const val AUDIO_SAMPLE_RATE = 16_000
const val AUDIO_FRAME_MILLIS = 20
const val AUDIO_FRAME_SAMPLES = AUDIO_SAMPLE_RATE * AUDIO_FRAME_MILLIS / 1_000
interface PcmAudioSource { fun start(); fun read(buffer: ShortArray): Int; fun stopAndRelease() }
class AndroidAudioRecordSource(private val record: AudioRecord) : PcmAudioSource {
    override fun start() = record.startRecording()
    override fun read(buffer: ShortArray) = record.read(buffer, 0, buffer.size)
    override fun stopAndRelease() { runCatching { record.stop() }; record.release() }
}

class PcmFramer(private val frameSamples: Int = AUDIO_FRAME_SAMPLES) {
    init { require(frameSamples > 0) { "El tamaño de frame debe ser positivo" } }
    private val pending = ArrayList<Short>()
    fun add(samples: ShortArray): List<ShortArray> {
        pending.ensureCapacity(pending.size + samples.size)
        samples.forEach { pending.add(it) }
        val frames = mutableListOf<ShortArray>()
        while (pending.size >= frameSamples) {
            frames += pending.take(frameSamples).toShortArray()
            repeat(frameSamples) { pending.removeAt(0) }
        }
        return frames
    }
    fun flush(): ShortArray { val result = pending.toShortArray(); pending.clear(); return result }
}

/** Baseline VAD energético adaptativo; no es un modelo de aprendizaje automático. */
class EnergyVad(
    private val initialNoiseDb: Double = -55.0,
    private val marginDb: Double = 12.0,
    private val hysteresisDb: Double = 3.0,
    private val calibrationFrames: Int = 100
) {
    init { require(calibrationFrames >= 0) { "La calibración no puede ser negativa" } }
    private var noiseDb = initialNoiseDb
    private val calibration = ArrayList<Double>(calibrationFrames)
    var calibrated: Boolean = calibrationFrames == 0
        private set

    fun energyDb(frame: ShortArray): Double {
        if (frame.isEmpty()) return -160.0
        val sum = frame.fold(0.0) { total, sample -> total + sample.toDouble() * sample.toDouble() }
        val rms = sqrt(sum / frame.size) / Short.MAX_VALUE.toDouble()
        return 20.0 * log10(maxOf(rms, 1e-8))
    }
    fun isSpeech(frame: ShortArray, previousSpeech: Boolean = false): Boolean {
        val current = energyDb(frame)
        if (!calibrated) {
            calibration += current
            if (calibration.size == calibrationFrames) {
                noiseDb = calibration.sorted()[calibration.size / 2]
                calibrated = true
            }
            return false
        }
        val speech = current >= noiseDb + marginDb - if (previousSpeech) hysteresisDb else 0.0
        if (!speech) noiseDb = noiseDb * .98 + current * .02
        return speech
    }
}

data class PendingSegment(
    val file: File,
    val recordedAt: Instant,
    val captureMode: String = "continuous",
    val captureSessionId: String? = null,
    val chunkIndex: Int? = null,
    val captureChunkId: String = UUID.randomUUID().toString(),
)
class SegmentQueue(private val directory: File, private val maxSegments: Int = 20) {
    init { require(maxSegments > 0) { "La capacidad debe ser positiva" } }
    private val pending = ArrayDeque<PendingSegment>()
    init {
        directory.mkdirs()
        directory.listFiles { file -> file.name.startsWith("segment-") && file.extension == "wav" }
            ?.sortedBy { it.name }?.forEachIndexed { index, file ->
                val parts = file.name.removePrefix("segment-").removeSuffix(".wav").split('-')
                val recordedAt = parts.firstOrNull()?.toLongOrNull()?.let(Instant::ofEpochMilli)
                    ?: Instant.ofEpochMilli(file.lastModified())
                val explicitMode = parts.getOrNull(1) == "continuous"
                val modeOffset = if (explicitMode) 1 else 0
                val chunkIdStart = parts.size - 5
                val structured = (explicitMode && parts.size >= 9) || (!explicitMode && parts.size >= 8)
                val chunkId = if (structured) parts.subList(chunkIdStart, parts.size).joinToString("-") else UUID.nameUUIDFromBytes(file.name.toByteArray()).toString()
                val indexPosition = chunkIdStart - 1
                val chunkIndex = if (structured) parts.getOrNull(indexPosition)?.toIntOrNull() else null
                val sessionParts = if (structured) parts.subList(1 + modeOffset, indexPosition) else emptyList()
                val sessionId = sessionParts.joinToString("-").takeUnless { it.isBlank() || it == "legacy" }
                val mode = "continuous"
                pending.addLast(PendingSegment(file, recordedAt, mode, sessionId, chunkIndex, chunkId))
            }
    }
    @Synchronized fun offer(segment: PendingSegment): Boolean { if (pending.size >= maxSegments) return false; pending.addLast(segment); return true }
    @Synchronized fun poll(): PendingSegment? = pending.removeFirstOrNull()
    @Synchronized fun requeue(segment: PendingSegment) { pending.addFirst(segment) }
    @get:Synchronized val size: Int get() = pending.size
    val capacity: Int get() = maxSegments
}

/** Serializa el drenaje: solo puede existir un consumidor efectivo de la cola. */
class SegmentUploadCoordinator(
    private val queue: SegmentQueue,
    private val upload: suspend (PendingSegment) -> Unit
) {
    private val mutex = Mutex()

    suspend fun drain(): Boolean = mutex.withLock {
        var segment = queue.poll()
        while (segment != null) {
            try {
                upload(segment)
                segment.file.delete()
            } catch (error: Exception) {
                queue.requeue(segment)
                if (error is CancellationException) throw error
                return@withLock false
            }
            segment = queue.poll()
        }
        true
    }

}

/** Segmentación acústica heurística: busca una pausa después del mínimo y aplica un hard cap. */
class ContinuousChunker(
    private val minimumSeconds: Int = 25,
    private val naturalStartSeconds: Int = 25,
    private val pauseFrames: Int = 100,
    private val hardCapSeconds: Int = 55,
    // Continuous empieza a capturar inmediatamente; no puede asumir silencio inicial.
    private val vad: EnergyVad = EnergyVad(calibrationFrames = 0),
) {
    init {
        require(minimumSeconds > 0 && naturalStartSeconds >= minimumSeconds && pauseFrames > 0)
        require(hardCapSeconds > naturalStartSeconds && hardCapSeconds < 60)
    }
    private val framer = PcmFramer()
    private val current = ArrayList<Short>()
    private var silentFrames = 0
    private val naturalStartSamples get() = naturalStartSeconds * AUDIO_SAMPLE_RATE
    private val hardCapSamples get() = hardCapSeconds * AUDIO_SAMPLE_RATE

    fun add(samples: ShortArray): List<ShortArray> {
        val result = mutableListOf<ShortArray>()
        for (frame in framer.add(samples)) {
            current.addAll(frame.toList())
            if (vad.isSpeech(frame)) silentFrames = 0 else silentFrames++
            if (current.size >= hardCapSamples || (current.size >= naturalStartSamples && silentFrames >= pauseFrames)) {
                result += cut()
            }
        }
        return result
    }

    fun flush(): ShortArray? {
        val remainder = framer.flush()
        if (remainder.isNotEmpty()) current.addAll(remainder.toList())
        return if (current.isEmpty()) null else cut()
    }

    private fun cut(): ShortArray {
        val result = current.toShortArray()
        current.clear(); silentFrames = 0
        return result
    }
}

/** Inicio temporal del siguiente chunk según la línea temporal PCM, no según el tiempo de I/O. */
fun chunkStartForSampleOffset(sessionStart: Instant, emittedSamples: Long): Instant {
    require(emittedSamples >= 0) { "El desplazamiento no puede ser negativo" }
    return sessionStart.plusNanos(emittedSamples * 1_000_000_000L / AUDIO_SAMPLE_RATE)
}

/** El cierre semántico se arma por duración y espera silencio independiente del chunker. */
class SemanticBlockAutoClose(
    private val targetSeconds: Int = 30 * 60,
    private val silenceSeconds: Int = 5,
    private val vad: EnergyVad = EnergyVad(calibrationFrames = 0),
) {
    private val framer = PcmFramer()
    private var samples = 0L
    private var silentFrames = 0
    fun add(input: ShortArray): Boolean {
        samples += input.size
        for (frame in framer.add(input)) {
            silentFrames = if (vad.isSpeech(frame)) 0 else silentFrames + 1
            if (samples >= targetSeconds.toLong() * AUDIO_SAMPLE_RATE &&
                silentFrames >= silenceSeconds * 1_000 / AUDIO_FRAME_MILLIS) return true
        }
        return false
    }
}

fun normalizedPcmAmplitude(samples: ShortArray): Float =
    (samples.maxOfOrNull { abs(it.toInt()) } ?: 0).toFloat().div(Short.MAX_VALUE).coerceIn(0f, 1f)

fun appendAmplitude(values: List<Float>, value: Float, capacity: Int = 96): List<Float> {
    require(capacity > 0)
    return (values + value.coerceIn(0f, 1f)).takeLast(capacity)
}

object WavWriter {
    fun write(file: File, samples: ShortArray, sampleRate: Int = AUDIO_SAMPLE_RATE) {
        require(sampleRate > 0) { "La frecuencia debe ser positiva" }
        require(samples.size <= (Int.MAX_VALUE - 36) / 2) { "El WAV es demasiado grande" }
        val temporary = File(file.parentFile, file.name + ".part")
        FileOutputStream(temporary).buffered().use { out ->
            val dataSize = samples.size * 2
            fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII).also { out.write(it) }
            fun le(value: Int) { out.write(value and 255); out.write(value shr 8 and 255); out.write(value shr 16 and 255); out.write(value shr 24 and 255) }
            fun leShort(value: Int) { out.write(value and 255); out.write(value shr 8 and 255) }
            ascii("RIFF"); le(36 + dataSize); ascii("WAVE"); ascii("fmt "); le(16); leShort(1); leShort(1); le(sampleRate); le(sampleRate * 2); leShort(2); leShort(16); ascii("data"); le(dataSize)
            samples.forEach { leShort(it.toInt()) }
        }
        check(temporary.renameTo(file)) { "No se pudo guardar el WAV" }
    }
}
