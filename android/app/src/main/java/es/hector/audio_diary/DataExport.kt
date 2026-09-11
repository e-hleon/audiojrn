package es.hector.audio_diary

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import retrofit2.HttpException

val DATA_EXPORT_FILES = listOf(
    "manifest.json", "diary.json", "tasks.json", "actions.json", "diary.md", "tasks.csv",
)

fun exportPreparationError(error: Throwable): String = when (error) {
    is HttpException -> "No se pudieron preparar los datos de exportación"
    is SerializationException -> "No se pudieron interpretar los datos de exportación"
    is IOException -> "No se pudo conectar al backend"
    else -> "No se pudieron preparar los datos de exportación"
}

@Serializable data class ExportTask(
    val id: String,
    val text: String,
    val completed: Boolean,
    val group: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("all_day") val allDay: Boolean,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable data class ExportInteraction(
    val id: String,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("capture_mode") val captureMode: String,
    @SerialName("capture_session_id") val captureSessionId: String? = null,
    @SerialName("chunk_index") val chunkIndex: Int? = null,
    val transcription: String,
    val language: String? = null,
    val analysis: Analysis,
)

@Serializable data class DiaryExport(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    val notes: List<DiaryNote>,
    val interactions: List<ExportInteraction>,
    @SerialName("continuous_sessions") val continuousSessions: List<ExportContinuousSession>,
    @SerialName("daily_summaries") val dailySummaries: List<ExportDailySummary>,
)

@Serializable data class TasksExport(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val tasks: List<ExportTask>,
)

@Serializable data class ActionsExport(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val actions: List<ExportProposedAction>,
)

@Serializable data class ExportCounts(
    val interactions: Int,
    @SerialName("continuous_sessions") val continuousSessions: Int,
    @SerialName("daily_summaries") val dailySummaries: Int,
    val tasks: Int,
    val actions: Int,
    val notes: Int,
)

@Serializable data class ExportManifest(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    @SerialName("generated_at") val generatedAt: String,
    val timezone: String,
    @SerialName("app_version") val appVersion: String,
    val contents: ExportCounts,
    val files: List<String> = DATA_EXPORT_FILES,
)

data class PreparedDataExport(
    val backend: ExportBackendData,
    val tasks: List<ExportTask>,
    val generatedAt: Instant,
    val appVersion: String,
    val notes: List<DiaryNote> = emptyList(),
)

fun TaskItem.toExportTask() = ExportTask(
    id = id,
    text = text,
    completed = completed,
    group = groupName,
    parentId = parentId,
    dueAt = dueAt,
    allDay = allDay,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun InteractionResponse.toExportInteraction() = ExportInteraction(
    id = id,
    recordedAt = recordedAt,
    createdAt = createdAt,
    captureMode = captureMode,
    captureSessionId = captureSessionId,
    chunkIndex = chunkIndex,
    transcription = transcription.text,
    language = transcription.language,
    analysis = analysis,
)

class DataExportWriter(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun write(output: OutputStream, data: PreparedDataExport) {
        val visibleInteractions = data.backend.interactions.filterNot {
            it.captureMode == "continuous" && it.transcription.text.isBlank()
        }
        val diary = DiaryExport(
            notes = data.notes,
            interactions = visibleInteractions.map { it.toExportInteraction() },
            continuousSessions = data.backend.continuousSessions,
            dailySummaries = data.backend.dailySummaries,
        )
        val files = linkedMapOf(
            "manifest.json" to json.encodeToString(ExportManifest(
                generatedAt = data.generatedAt.toString(),
                timezone = data.backend.timezone,
                appVersion = data.appVersion,
                contents = ExportCounts(
                    interactions = visibleInteractions.size,
                    continuousSessions = data.backend.continuousSessions.size,
                    dailySummaries = data.backend.dailySummaries.size,
                    tasks = data.tasks.size,
                    actions = data.backend.actions.size,
                    notes = data.notes.size,
                ),
            )),
            "diary.json" to json.encodeToString(diary),
            "tasks.json" to json.encodeToString(TasksExport(tasks = data.tasks)),
            "actions.json" to json.encodeToString(ActionsExport(actions = data.backend.actions)),
            "diary.md" to diaryMarkdown(data.backend, data.notes),
            "tasks.csv" to tasksCsv(data.tasks),
        )
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}

fun diaryMarkdown(data: ExportBackendData, notes: List<DiaryNote> = emptyList()): String {
    val zone = ZoneId.of(data.timezone)
    val entries = data.interactions
        .filterNot { it.captureMode == "continuous" && it.transcription.text.isBlank() }
        .sortedBy { parseInstant(it.recordedAt) }
    val summaries = data.dailySummaries.associateBy { LocalDate.parse(it.day) }
    val notesByDay = notes.associateBy { LocalDate.parse(it.day) }
    val days = (entries.map { parseInstant(it.recordedAt).atZone(zone).toLocalDate() } + summaries.keys + notesByDay.keys).distinct().sorted()
    val dateFormat = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' uuuu", Locale("es", "ES"))
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    return buildString {
        appendLine("# Diario exportado")
        days.forEach { day ->
            appendLine()
            appendLine("## ${day.format(dateFormat)}")
            notesByDay[day]?.let { note -> appendLine(); appendLine("### Nota del día"); appendLine(); appendLine(note.markdown) }
            summaries[day]?.let { summary ->
                appendLine()
                appendLine("### Resumen del día")
                appendLine()
                appendLine(summary.summary)
                if (summary.highlights.isNotEmpty()) {
                    appendLine()
                    appendLine("### Destacados")
                    summary.highlights.forEach { appendLine("- $it") }
                }
            }
            entries.filter { parseInstant(it.recordedAt).atZone(zone).toLocalDate() == day }.forEach { entry ->
                appendLine()
                appendLine("### ${parseInstant(entry.recordedAt).atZone(zone).format(timeFormat)}")
                appendLine()
                appendLine(entry.transcription.text)
            }
        }
        appendLine()
    }
}

fun tasksCsv(tasks: List<ExportTask>): String = buildString {
    appendLine(listOf("id", "text", "completed", "group", "parent_id", "due_at", "all_day", "sort_order", "created_at", "updated_at").joinToString(","))
    tasks.forEach { task ->
        appendLine(listOf(
            task.id, task.text, task.completed.toString(), task.group.orEmpty(), task.parentId.orEmpty(),
            task.dueAt.orEmpty(), task.allDay.toString(), task.sortOrder.toString(), task.createdAt.orEmpty(), task.updatedAt.orEmpty(),
        ).joinToString(",", transform = ::csvCell))
    }
}

private fun csvCell(value: String) = "\"${value.replace("\"", "\"\"")}\""

private fun parseInstant(value: String): Instant = runCatching { Instant.parse(value) }
    .getOrElse { OffsetDateTime.parse(value).toInstant() }
