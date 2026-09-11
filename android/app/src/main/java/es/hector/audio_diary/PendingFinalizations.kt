package es.hector.audio_diary

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Un marcador durable por bloque cerrado; se elimina solo tras confirmar finalize. */
class PendingFinalizations(private val directory: File) {
    init { directory.mkdirs() }

    fun recordChunk(sessionId: String, lastIndex: Int) {
        UUID.fromString(sessionId)
        val temporary = File(directory, "$sessionId.open.part")
        temporary.writeText(lastIndex.toString())
        check(temporary.renameTo(File(directory, "$sessionId.open"))) { "No se pudo registrar el chunk" }
    }

    fun interruptedCount(): Int = directory.listFiles().orEmpty().count { it.extension == "open" }

    fun remember(sessionId: String, lastIndex: Int) {
        UUID.fromString(sessionId)
        require(lastIndex >= 0)
        val destination = File(directory, "$sessionId.finalize")
        val temporary = File(directory, "$sessionId.finalize.part")
        temporary.writeText(lastIndex.toString())
        check(temporary.renameTo(destination)) { "No se pudo guardar STOP" }
        File(directory, "$sessionId.open").delete()
    }

    fun pending(): List<Pair<String, Int>> = directory.listFiles().orEmpty()
        .filter { it.extension == "finalize" }
        .sortedBy { it.name }
        .mapNotNull { file -> file.readText().toIntOrNull()?.let { file.nameWithoutExtension to it } }

    fun pendingSessionIds(): Set<String> = pending().mapTo(mutableSetOf()) { it.first }

    fun discard(sessionId: String) {
        File(directory, "$sessionId.open").delete()
        File(directory, "$sessionId.finalize").delete()
        File(directory, "$sessionId.finalize.part").delete()
    }

    suspend fun retry(backend: BackendRepository, url: String) {
        for ((sessionId, lastIndex) in pending()) {
            try {
                val result = backend.finalizeSession(sessionId, lastIndex, url)
                if (result.status == "complete" && result.lastChunkIndex == lastIndex) {
                    File(directory, "$sessionId.finalize").delete()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Keep this marker and continue with other independent sessions.
            }
        }
    }
}

fun pendingSegmentDirectory(filesDir: File, cacheDir: File): File {
    val directory = File(filesDir, "pending-segments").apply { mkdirs() }
    File(cacheDir, "pending-segments").listFiles().orEmpty().forEach { old ->
        if (old.isFile && old.extension == "wav") {
            val target = File(directory, old.name)
            check(!target.exists() && old.renameTo(target)) { "No se pudo recuperar un WAV pendiente" }
        }
    }
    return directory
}
