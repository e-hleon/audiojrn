package app.audiojrn

import java.io.File
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class CapturedAudio(
    val file: File,
    val recordedAt: Instant,
    val captureMode: String = "manual",
    val captureSessionId: String? = null,
    val chunkIndex: Int? = null,
    val captureChunkId: String? = java.util.UUID.randomUUID().toString(),
)

@Serializable data class ProcessResponse(
    @SerialName("interaction_id") val interactionId: String,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("created_at") val createdAt: String,
    val transcription: Transcription, val analysis: Analysis,
    @SerialName("capture_mode") val captureMode: String = "manual",
    @SerialName("capture_session_id") val captureSessionId: String? = null,
    @SerialName("chunk_index") val chunkIndex: Int? = null,
    @SerialName("capture_chunk_id") val captureChunkId: String? = null,
)
@Serializable data class Transcription(val text: String, val language: String? = null, val model: String, val device: String? = null, @SerialName("compute_type") val computeType: String? = null)
@Serializable data class Analysis(val highlights: List<Highlight> = emptyList(), val tasks: List<Task> = emptyList(), val events: List<EventCandidate> = emptyList()) {
    // Source compatibility for JVM tests and old callers; serialization remains new-only.
    constructor(summary: String, topics: List<String>, decisions: List<Highlight>, tasks: List<Task>, reminders: List<Task>) : this(highlights = decisions, tasks = tasks + reminders)
}
@Serializable data class Highlight(val text: String, val evidence: String)
@Serializable data class Task(val text: String, @SerialName("due_at") val dueAt: String? = null, val evidence: String)
@Serializable data class EventCandidate(val title: String, @SerialName("start_at") val startAt: String? = null, @SerialName("end_at") val endAt: String? = null, @SerialName("all_day") val allDay: Boolean = false, val location: String? = null, val evidence: String)

@Serializable data class InteractionResponse(
    val id: String, @SerialName("recorded_at") val recordedAt: String,
    @SerialName("created_at") val createdAt: String, val transcription: Transcription,
    val analysis: Analysis, @SerialName("analysis_model") val analysisModel: String? = null,
    @SerialName("capture_mode") val captureMode: String = "manual",
    @SerialName("capture_session_id") val captureSessionId: String? = null,
    @SerialName("chunk_index") val chunkIndex: Int? = null,
    @SerialName("capture_chunk_id") val captureChunkId: String? = null,
    // Día canónico calculado por el backend con APP_TIMEZONE.
    val day: String = "",
)
@Serializable data class ContinuousSessionResult(
    @SerialName("session_id") val sessionId: String,
    val status: String,
    @SerialName("last_chunk_index") val lastChunkIndex: Int? = null,
    val analysis: Analysis? = null,
    @SerialName("finalized_at") val finalizedAt: String? = null,
)
@Serializable data class ProposedAction(
    val id: String,
    val kind: String,
    val status: String,
    val title: String,
    @SerialName("due_text") val dueText: String? = null,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    val evidence: String,
    @SerialName("source_interaction_id") val sourceInteractionId: String? = null,
    @SerialName("source_session_id") val sourceSessionId: String? = null,
    @SerialName("created_at") val createdAt: String,
)
@Serializable data class ExportProposedAction(
    val id: String,
    val kind: String,
    val status: String,
    val title: String,
    @SerialName("due_text") val dueText: String? = null,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    val evidence: String,
    @SerialName("source_interaction_id") val sourceInteractionId: String? = null,
    @SerialName("source_session_id") val sourceSessionId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)
@Serializable data class ExportDailySummary(
    val day: String,
    val timezone: String,
    val summary: String,
    val highlights: List<String> = emptyList(),
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("manually_edited") val manuallyEdited: Boolean,
)
@Serializable data class ExportContinuousSession(
    val id: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("last_chunk_index") val lastChunkIndex: Int? = null,
    val status: String,
    val analysis: Analysis? = null,
    @SerialName("finalized_at") val finalizedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)
@Serializable data class ExportBackendData(
    val timezone: String,
    val interactions: List<InteractionResponse>,
    @SerialName("continuous_sessions") val continuousSessions: List<ExportContinuousSession>,
    @SerialName("daily_summaries") val dailySummaries: List<ExportDailySummary>,
    val actions: List<ExportProposedAction>,
)
@Serializable data class ActionUpdate(
    val title: String? = null,
    @SerialName("due_text") val dueText: String? = null,
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("all_day") val allDay: Boolean? = null,
    val location: String? = null,
    val notes: String? = null,
    val status: String? = null,
)
@Serializable data class DismissPendingActionsResponse(@SerialName("dismissed_count") val dismissedCount: Int)
@Serializable data class DeletedActionsResponse(@SerialName("deleted_count") val deletedCount: Int)
@Serializable data class DeletionResponse(
    @SerialName("interactions_deleted") val interactionsDeleted: Int,
    @SerialName("actions_deleted") val actionsDeleted: Int,
    @SerialName("daily_summaries_deleted") val dailySummariesDeleted: Int,
)
@Serializable data class DailySummaryState(
    val status: String, val result: DailySummaryResult? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    val model: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("manually_edited") val manuallyEdited: Boolean = false,
)
@Serializable data class DailySummaryResult(val summary: String, val highlights: List<String> = emptyList())
@Serializable data class DayResponse(
    val day: String, val timezone: String, val interactions: List<InteractionResponse>,
    val events: List<EventCandidate> = emptyList(), val highlights: List<Highlight> = emptyList(),
    val summary: DailySummaryState
)
@Serializable data class DailySummaryUpdate(val summary: String? = null, val highlights: List<String>? = null)
@Serializable data class ActivityDays(val days: List<String>)
@Serializable data class TaskItem(
    val id: String = "",
    val text: String,
    val completed: Boolean = false,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("source_action_id") val sourceActionId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
@Serializable data class TaskItemCreate(val id: String? = null, val text: String, val completed: Boolean = false, @SerialName("group_name") val groupName: String? = null, @SerialName("parent_id") val parentId: String? = null, @SerialName("sort_order") val sortOrder: Int = 0, @SerialName("due_at") val dueAt: String? = null, @SerialName("all_day") val allDay: Boolean = false)
@Serializable data class TaskItemUpdate(val text: String? = null, val completed: Boolean? = null, @SerialName("group_name") val groupName: String? = null, @SerialName("parent_id") val parentId: String? = null, @SerialName("sort_order") val sortOrder: Int? = null, @SerialName("due_at") val dueAt: String? = null, @SerialName("all_day") val allDay: Boolean? = null)
@Serializable data class TaskItemEditorUpdate(
    val text: String,
    @SerialName("group_name") val groupName: String?,
    @SerialName("parent_id") val parentId: String?,
    @SerialName("due_at") val dueAt: String?,
    @SerialName("all_day") val allDay: Boolean,
)
@Serializable data class TaskOrder(
    val id: String,
    @SerialName("group_name") val groupName: String?,
    @SerialName("sort_order") val sortOrder: Int,
)
@Serializable data class TaskReorderRequest(val items: List<TaskOrder>)
@Serializable data class HealthResponse(val status: String, @SerialName("analysis_configured") val analysisConfigured: Boolean = false, val model: String? = null, val device: String? = null, @SerialName("compute_type") val computeType: String? = null)
@Serializable data class InteractionDeleteRequest(@SerialName("interaction_ids") val interactionIds: List<String>)
