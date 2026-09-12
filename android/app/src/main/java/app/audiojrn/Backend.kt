package app.audiojrn

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Part
import retrofit2.http.Body
import retrofit2.http.DELETE

fun normalizeBackendUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val parsed = runCatching { java.net.URI(trimmed) }.getOrNull()
    require(parsed?.scheme in setOf("http", "https") && !parsed?.host.isNullOrBlank()) { "URL del backend no válida" }
    return "$trimmed/"
}

fun backendUrlIfAvailable(value: String): String? =
    runCatching { normalizeBackendUrl(value) }.getOrNull()

interface ProcessApi {
    @Multipart @POST("process") suspend fun process(
        @Part file: MultipartBody.Part,
        @Part("recorded_at") recordedAt: okhttp3.RequestBody,
        @Part("capture_mode") captureMode: okhttp3.RequestBody,
        @Part("capture_session_id") captureSessionId: okhttp3.RequestBody?,
        @Part("chunk_index") chunkIndex: okhttp3.RequestBody?,
        @Part("capture_chunk_id") captureChunkId: okhttp3.RequestBody?,
    ): ProcessResponse
    @GET("health") suspend fun health(): HealthResponse
    @GET("export-data") suspend fun exportData(): ExportBackendData
    @GET("interactions") suspend fun interactions(@Query("limit") limit: Int = 50, @Query("offset") offset: Int = 0, @Query("q") query: String? = null): List<InteractionResponse>
    @GET("interactions/{id}") suspend fun interaction(@Path("id") id: String): InteractionResponse
    @GET("days/{day}") suspend fun day(@Path("day") day: String): DayResponse
    @POST("days/{day}/summary") suspend fun generateSummary(@Path("day") day: String): DailySummaryState
    @retrofit2.http.PATCH("days/{day}/summary") suspend fun updateSummary(@Path("day") day: String, @Body update: DailySummaryUpdate): DailySummaryState
    @GET("days/activity") suspend fun activityDays(@Query("from") from: String, @Query("to") to: String): ActivityDays
    @POST("continuous-sessions/{sessionId}/finalize") suspend fun finalizeSession(@Path("sessionId") sessionId: String, @Body request: FinalizeSessionRequest): ContinuousSessionResult
    @GET("actions") suspend fun actions(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("include_resolved") includeResolved: Boolean = false,
    ): List<ProposedAction>
    @POST("actions/dismiss-pending") suspend fun dismissPendingActions(): DismissPendingActionsResponse
    @DELETE("actions/pending") suspend fun deletePendingActions(): DeletedActionsResponse
    @DELETE("actions/exported") suspend fun deleteExportedActions(): DeletedActionsResponse
    @DELETE("actions/{actionId}") suspend fun deleteAction(@Path("actionId") actionId: String): DeletedActionsResponse
    @DELETE("interactions/{id}") suspend fun deleteInteraction(@Path("id") id: String): DeletionResponse
    @POST("interactions/delete") suspend fun deleteInteractions(@Body request: InteractionDeleteRequest): DeletionResponse
    @DELETE("continuous-sessions/{sessionId}") suspend fun deleteContinuousSession(@Path("sessionId") sessionId: String): DeletionResponse
    @retrofit2.http.PATCH("actions/{actionId}") suspend fun updateAction(@Path("actionId") actionId: String, @Body update: ActionUpdate): ProposedAction
    @GET("tasks") suspend fun tasks(): List<TaskItem>
    @POST("tasks") suspend fun createTask(@Body task: TaskItemCreate): TaskItem
    @retrofit2.http.PATCH("tasks/{taskId}") suspend fun updateTask(@Path("taskId") taskId: String, @Body update: TaskItemUpdate): TaskItem
    @retrofit2.http.PATCH("tasks/{taskId}") suspend fun updateTaskFromEditor(@Path("taskId") taskId: String, @Body update: TaskItemEditorUpdate): TaskItem
    @DELETE("tasks/{taskId}") suspend fun deleteTask(@Path("taskId") taskId: String)
    @POST("tasks/reorder") suspend fun reorderTasks(@Body request: TaskReorderRequest): List<TaskItem>
    @POST("actions/{actionId}/add-to-tasks") suspend fun addActionToTasks(@Path("actionId") actionId: String): TaskItem
}
@Serializable data class FinalizeSessionRequest(@SerialName("last_chunk_index") val lastChunkIndex: Int)
interface BackendRepository {
    suspend fun process(audio: CapturedAudio, backendUrl: String): ProcessResponse
    suspend fun health(backendUrl: String): HealthResponse = error("Health no implementado")
    suspend fun exportData(backendUrl: String): ExportBackendData = error("Exportación no implementada")
    suspend fun interactions(backendUrl: String, limit: Int = 50, offset: Int = 0, query: String? = null): List<InteractionResponse> = error("Histórico no implementado")
    suspend fun interaction(backendUrl: String, id: String): InteractionResponse = error("Detalle no implementado")
    suspend fun day(backendUrl: String, date: String): DayResponse = error("Día no implementado")
    suspend fun generateSummary(backendUrl: String, date: String): DailySummaryState = error("Resumen no implementado")
    suspend fun updateSummary(backendUrl: String, date: String, update: DailySummaryUpdate): DailySummaryState = error("Resumen no implementado")
    suspend fun activityDays(backendUrl: String, from: String, to: String): ActivityDays = error("Actividad no implementada")
    suspend fun finalizeSession(sessionId: String, lastChunkIndex: Int, backendUrl: String): ContinuousSessionResult = error("Finalización no implementada")
    suspend fun actions(backendUrl: String, limit: Int = 50, offset: Int = 0, includeResolved: Boolean = false): List<ProposedAction> = error("Acciones no implementadas")
    suspend fun dismissPendingActions(backendUrl: String): DismissPendingActionsResponse = error("Acciones no implementadas")
    suspend fun deletePendingActions(backendUrl: String): DeletedActionsResponse = error("Acciones no implementadas")
    suspend fun deleteExportedActions(backendUrl: String): DeletedActionsResponse = error("Acciones no implementadas")
    suspend fun deleteAction(actionId: String, backendUrl: String): DeletedActionsResponse = error("Acciones no implementadas")
    suspend fun deleteInteraction(id: String, backendUrl: String): DeletionResponse = error("Histórico no implementado")
    suspend fun deleteInteractions(ids: List<String>, backendUrl: String): DeletionResponse = error("Histórico no implementado")
    suspend fun deleteContinuousSession(sessionId: String, backendUrl: String): DeletionResponse = error("Histórico no implementado")
    suspend fun updateAction(actionId: String, update: ActionUpdate, backendUrl: String): ProposedAction = error("Acciones no implementadas")
    suspend fun tasks(backendUrl: String): List<TaskItem> = error("Tareas no implementadas")
    suspend fun createTask(task: TaskItemCreate, backendUrl: String): TaskItem = error("Tareas no implementadas")
    suspend fun updateTask(taskId: String, update: TaskItemUpdate, backendUrl: String): TaskItem = error("Tareas no implementadas")
    suspend fun updateTaskFromEditor(taskId: String, update: TaskItemEditorUpdate, backendUrl: String): TaskItem = error("Tareas no implementadas")
    suspend fun deleteTask(taskId: String, backendUrl: String): Unit = error("Tareas no implementadas")
    suspend fun reorderTasks(items: List<TaskItem>, backendUrl: String): List<TaskItem> = error("Tareas no implementadas")
    suspend fun addActionToTasks(actionId: String, backendUrl: String): TaskItem = error("Tareas no implementadas")
}
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class RetrofitBackendRepository(private val json: Json = Json { ignoreUnknownKeys = false }) : BackendRepository {
    private fun api(backendUrl: String): ProcessApi = Retrofit.Builder().baseUrl(normalizeBackendUrl(backendUrl)).client(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(360, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(ProcessApi::class.java)
    override suspend fun process(audio: CapturedAudio, backendUrl: String): ProcessResponse {
        require(audio.file.exists()) { "El audio temporal ya no existe" }
        val api = api(backendUrl)
        val mediaType = if (audio.file.extension.equals("wav", ignoreCase = true)) "audio/wav" else "audio/mp4"
        val file = MultipartBody.Part.createFormData("file", audio.file.name, audio.file.asRequestBody(mediaType.toMediaType()))
        fun String.body() = toRequestBody("text/plain".toMediaType())
        return api.process(file, audio.recordedAt.toString().body(), audio.captureMode.body(),
            audio.captureSessionId?.body(), audio.chunkIndex?.toString()?.body(), audio.captureChunkId?.body())
    }
    override suspend fun health(backendUrl: String) = api(backendUrl).health()
    override suspend fun exportData(backendUrl: String) = api(backendUrl).exportData()
    override suspend fun interactions(backendUrl: String, limit: Int, offset: Int, query: String?) = api(backendUrl).interactions(limit, offset, query)
    override suspend fun interaction(backendUrl: String, id: String) = api(backendUrl).interaction(id)
    override suspend fun day(backendUrl: String, date: String) = api(backendUrl).day(date)
    override suspend fun generateSummary(backendUrl: String, date: String) = api(backendUrl).generateSummary(date)
    override suspend fun updateSummary(backendUrl: String, date: String, update: DailySummaryUpdate) = api(backendUrl).updateSummary(date, update)
    override suspend fun activityDays(backendUrl: String, from: String, to: String) = api(backendUrl).activityDays(from, to)
    override suspend fun finalizeSession(sessionId: String, lastChunkIndex: Int, backendUrl: String) = api(backendUrl).finalizeSession(sessionId, FinalizeSessionRequest(lastChunkIndex))
    override suspend fun actions(backendUrl: String, limit: Int, offset: Int, includeResolved: Boolean) = api(backendUrl).actions(limit, offset, includeResolved)
    override suspend fun dismissPendingActions(backendUrl: String) = api(backendUrl).dismissPendingActions()
    override suspend fun deletePendingActions(backendUrl: String) = api(backendUrl).deletePendingActions()
    override suspend fun deleteExportedActions(backendUrl: String) = api(backendUrl).deleteExportedActions()
    override suspend fun deleteAction(actionId: String, backendUrl: String) = api(backendUrl).deleteAction(actionId)
    override suspend fun deleteInteraction(id: String, backendUrl: String) = api(backendUrl).deleteInteraction(id)
    override suspend fun deleteInteractions(ids: List<String>, backendUrl: String) = api(backendUrl).deleteInteractions(InteractionDeleteRequest(ids))
    override suspend fun deleteContinuousSession(sessionId: String, backendUrl: String) = api(backendUrl).deleteContinuousSession(sessionId)
    override suspend fun updateAction(actionId: String, update: ActionUpdate, backendUrl: String) = api(backendUrl).updateAction(actionId, update)
    override suspend fun tasks(backendUrl: String) = api(backendUrl).tasks()
    override suspend fun createTask(task: TaskItemCreate, backendUrl: String) = api(backendUrl).createTask(task)
    override suspend fun updateTask(taskId: String, update: TaskItemUpdate, backendUrl: String) = api(backendUrl).updateTask(taskId, update)
    override suspend fun updateTaskFromEditor(taskId: String, update: TaskItemEditorUpdate, backendUrl: String) = api(backendUrl).updateTaskFromEditor(taskId, update)
    override suspend fun deleteTask(taskId: String, backendUrl: String) = api(backendUrl).deleteTask(taskId)
    override suspend fun reorderTasks(items: List<TaskItem>, backendUrl: String) = api(backendUrl).reorderTasks(
        TaskReorderRequest(items.map { TaskOrder(it.id, it.groupName, it.sortOrder) })
    )
    override suspend fun addActionToTasks(actionId: String, backendUrl: String) = api(backendUrl).addActionToTasks(actionId)
}
