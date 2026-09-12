package app.audiojrn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

class DataExportTest {
    @Test fun zip_has_canonical_utf8_files_and_portable_content() {
        val interaction = interaction(
            id = "chunk-1",
            text = "Línea con áéí, \"comillas\"\ny salto",
            recordedAt = "2026-09-08T08:30:00Z",
            sessionId = "session-1",
            chunkIndex = 0,
        )
        val secondChunk = interaction(
            id = "chunk-2", text = "Segundo fragmento", recordedAt = "2026-09-08T08:31:00Z",
            sessionId = "session-1", chunkIndex = 1,
        )
        val backend = ExportBackendData(
            timezone = "Europe/Madrid",
            interactions = listOf(interaction, secondChunk),
            continuousSessions = listOf(ExportContinuousSession(
                id = "session-1", startedAt = "2026-09-08T08:30:00Z", lastChunkIndex = 0,
                status = "complete", analysis = Analysis(), finalizedAt = "2026-09-08T08:31:00Z",
                createdAt = "2026-09-08T08:30:00Z", updatedAt = "2026-09-08T08:31:00Z",
            )),
            dailySummaries = listOf(ExportDailySummary(
                day = "2026-09-08", timezone = "Europe/Madrid", summary = "Resumen útil",
                highlights = listOf("Destacado ñ"), generatedAt = "2026-09-08T20:00:00Z",
                updatedAt = "2026-09-08T20:00:00Z", manuallyEdited = true,
            )),
            actions = emptyList(),
        )
        val task = ExportTask(
            id = "task-1", text = "línea, \"uno\"\nsegunda", completed = false,
            group = "TFM", allDay = false, sortOrder = 2,
        )
        val bytes = ByteArrayOutputStream().also {
            DataExportWriter().write(it, PreparedDataExport(backend, listOf(task), Instant.parse("2026-09-09T10:00:00Z"), "0.1", listOf(DiaryNote("2026-09-08", "# Nota ñ\n- café"))))
        }.toByteArray()
        val files = unzip(bytes)

        assertEquals(DATA_EXPORT_FILES, files.keys.toList())
        assertTrue(files.getValue("manifest.json").contains("\"schema_version\": 2"))
        assertTrue(files.getValue("manifest.json").contains("\"notes\": 1"))
        assertTrue(files.getValue("diary.json").contains("\"schema_version\": 2"))
        assertTrue(files.getValue("diary.json").contains("Nota ñ"))
        assertTrue(files.getValue("diary.md").indexOf("Nota del día") < files.getValue("diary.md").indexOf("Resumen del día"))
        assertTrue(files.getValue("manifest.json").contains("Europe/Madrid"))
        assertFalse(files.getValue("diary.md").contains("Sesión continua"))
        assertFalse(files.getValue("diary.md").contains("Fragmento"))
        assertTrue(files.getValue("diary.md").contains("### 10:30:00"))
        assertTrue(files.getValue("diary.md").contains("### 10:31:00"))
        assertTrue(files.getValue("diary.md").contains("Línea con áéí, \"comillas\"\ny salto"))
        assertFalse(files.getValue("diary.json").contains("compute_type"))
        assertTrue(files.getValue("tasks.csv").contains("\"línea, \"\"uno\"\"\nsegunda\""))
    }

    @Test fun blank_continuous_rows_are_excluded_from_both_diary_exports() {
        val blank = interaction("blank", "   ", "2026-09-08T08:30:00Z", "session", 0)
        val backend = ExportBackendData("UTC", listOf(blank), emptyList(), emptyList(), emptyList())
        val bytes = ByteArrayOutputStream().also { DataExportWriter().write(it, PreparedDataExport(backend, emptyList(), Instant.EPOCH, "test")) }.toByteArray()
        val files = unzip(bytes)
        assertFalse(files.getValue("diary.json").contains("blank"))
        assertFalse(files.getValue("diary.md").contains("08:30:00"))
        assertTrue(files.getValue("manifest.json").contains("\"interactions\": 0"))
    }

    @Test fun export_errors_distinguish_connection_http_and_serialization() {
        assertEquals("No se pudo conectar al backend", exportPreparationError(IOException()))
        assertEquals("No se pudieron interpretar los datos de exportación", exportPreparationError(SerializationException("bad")))
        val http = HttpException(Response.error<Unit>(500, "".toResponseBody()))
        assertEquals("No se pudieron preparar los datos de exportación", exportPreparationError(http))
    }

    @Test fun local_task_export_keeps_pending_values_and_excludes_tombstones() {
        val tasks = listOf(
            local("create", "Actual creada", TaskSyncState.PENDING_CREATE),
            local("update", "Actual editada", TaskSyncState.PENDING_UPDATE),
            local("synced", "Sincronizada", TaskSyncState.SYNCED),
            local("delete", "No exportar", TaskSyncState.PENDING_DELETE),
        )
        val exported = localTasksForExport(tasks)
        assertEquals(setOf("create", "update", "synced"), exported.map { it.id }.toSet())
        assertEquals("Actual editada", exported.single { it.id == "update" }.text)
        assertFalse(exported.any { it.id == "delete" })
    }

    private fun interaction(id: String, text: String, recordedAt: String, sessionId: String?, chunkIndex: Int?) = InteractionResponse(
        id = id, recordedAt = recordedAt, createdAt = recordedAt,
        transcription = Transcription(text, "es", "base"), analysis = Analysis(),
        captureMode = if (sessionId == null) "manual" else "continuous",
        captureSessionId = sessionId, chunkIndex = chunkIndex,
    )

    private fun local(id: String, text: String, state: TaskSyncState) = LocalTaskEntity(
        id, text, false, "Grupo", null, 0, null, false, null,
        "2026-09-09T10:00:00Z", "2026-09-09T10:00:00Z", state,
    )

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return result
    }
}
