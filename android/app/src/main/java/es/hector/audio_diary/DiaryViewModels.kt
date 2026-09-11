package es.hector.audio_diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed interface HistoryState { data object Idle : HistoryState; data object Loading : HistoryState; data class Ready(val items: List<InteractionResponse>, val detail: InteractionResponse? = null, val highlightedId: String? = null) : HistoryState; data class Error(val message: String) : HistoryState }
private fun recordedInstant(value: String): Instant = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrDefault(Instant.MIN)
fun flatChronology(items: List<InteractionResponse>, newestFirst: Boolean = true): List<InteractionResponse> =
    items.sortedWith(compareBy<InteractionResponse> { recordedInstant(it.recordedAt) }.thenBy { it.id }).let { if (newestFirst) it.reversed() else it }
fun transcriptionSearchQuery(raw: String): String? = raw.trim().takeIf(String::isNotEmpty)
fun interactionLocalDate(recordedAt: String, zoneId: ZoneId = ZoneId.of("Europe/Madrid")): LocalDate? =
    runCatching { OffsetDateTime.parse(recordedAt).atZoneSameInstant(zoneId).toLocalDate() }.getOrNull()
class HistoryViewModel(private val backend: BackendRepository) : ViewModel() {
    private val mutable = MutableStateFlow<HistoryState>(HistoryState.Idle); val state: StateFlow<HistoryState> = mutable.asStateFlow()
    fun load(url: String) { mutable.value = HistoryState.Loading; viewModelScope.launch { runCatching { backend.interactions(url) }.onSuccess { mutable.value = HistoryState.Ready(it.sortedByDescending { item -> item.recordedAt }) }.onFailure { mutable.value = HistoryState.Error(errorMessage(it)) } } }
    fun detail(url: String, id: String) {
        val current = mutable.value as? HistoryState.Ready ?: return
        viewModelScope.launch {
            runCatching { backend.interaction(url, id) }
                .onSuccess { mutable.value = current.copy(detail = it, highlightedId = id) }
                .onFailure { mutable.value = HistoryState.Error("No se pudo cargar el detalle") }
        }
    }
    fun closeDetail() {
        val current = mutable.value as? HistoryState.Ready ?: return
        mutable.value = current.copy(detail = null)
    }
    fun deleteInteraction(url: String, id: String) { viewModelScope.launch { runCatching { backend.deleteInteraction(id, url) }.onSuccess {
        val current = mutable.value as? HistoryState.Ready ?: return@onSuccess
        mutable.value = HistoryState.Ready(current.items.filterNot { it.id == id }, highlightedId = current.highlightedId?.takeIf { it != id })
    }.onFailure { mutable.value = HistoryState.Error("No se pudo eliminar la interacción") } } }
    fun deleteContinuousSession(url: String, sessionId: String) { viewModelScope.launch { runCatching { backend.deleteContinuousSession(sessionId, url) }.onSuccess {
        val current = mutable.value as? HistoryState.Ready ?: return@onSuccess
        mutable.value = HistoryState.Ready(current.items.filterNot { it.captureMode == "continuous" && it.captureSessionId == sessionId }, highlightedId = current.highlightedId?.takeIf { id -> current.items.none { it.id == id && it.captureSessionId == sessionId } })
    }.onFailure { mutable.value = HistoryState.Error("No se pudo eliminar la sesión Continua") } } }
    private fun errorMessage(error: Throwable) = if (error is java.net.ConnectException) "No se puede conectar con el servidor" else "No se pudo cargar el histórico"
}

sealed interface DayState { data object Idle : DayState; data object Loading : DayState; data class Ready(val value: DayResponse) : DayState; data class Error(val message: String) : DayState }
class DayViewModel(private val backend: BackendRepository) : ViewModel() {
    private val mutable = MutableStateFlow<DayState>(DayState.Idle); val state: StateFlow<DayState> = mutable.asStateFlow(); var selectedDate: LocalDate = LocalDate.now(); private var url = ""
    fun load(url: String, date: LocalDate = selectedDate) { this.url = url; selectedDate = date; mutable.value = DayState.Loading; viewModelScope.launch { runCatching { backend.day(url, date.toString()) }.onSuccess { mutable.value = DayState.Ready(it) }.onFailure { mutable.value = DayState.Error("No se pudo cargar el día") } } }
    fun generate(onSuccess: () -> Unit = {}) { if (url.isBlank()) return; mutable.value = DayState.Loading; viewModelScope.launch { runCatching { backend.generateSummary(url, selectedDate.toString()); backend.day(url, selectedDate.toString()) }.onSuccess { mutable.value = DayState.Ready(it); onSuccess() }.onFailure { mutable.value = DayState.Error("No se pudo generar el resumen y los destacados") } } }
    fun updateSummary(update: DailySummaryUpdate) { if (url.isBlank()) return; viewModelScope.launch { runCatching { backend.updateSummary(url, selectedDate.toString(), update); backend.day(url, selectedDate.toString()) }.onSuccess { mutable.value = DayState.Ready(it) }.onFailure { mutable.value = DayState.Error("No se pudo guardar el resumen") } } }
    fun deleteInteraction(id: String) = viewModelScope.launch { runCatching { backend.deleteInteraction(id, url); backend.day(url, selectedDate.toString()) }.onSuccess { mutable.value = DayState.Ready(it) }.onFailure { mutable.value = DayState.Error("No se pudo eliminar la interacción") } }
    fun deleteSelected(keys: Set<String>, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) = viewModelScope.launch {
        runCatching {
            backend.deleteInteractions(keys.map { it.removePrefix("interaction:") }, url)
            backend.day(url, selectedDate.toString())
        }.onSuccess { mutable.value = DayState.Ready(it); onSuccess() }
            .onFailure { onError("No se pudo eliminar la selección") }
    }
    fun activity(from: LocalDate, to: LocalDate, callback: (Set<LocalDate>) -> Unit) = viewModelScope.launch { runCatching { backend.activityDays(url, from.toString(), to.toString()).days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet() }.onSuccess(callback) }
}

data class TaskUiSection(val groupName: String?, val items: List<TaskItem>)
fun taskUiSections(items: List<TaskItem>): List<TaskUiSection> = items.groupBy { it.groupName }.toList()
    .sortedWith(compareBy<Pair<String?, List<TaskItem>>> { it.first == null }.thenBy { it.first.orEmpty() })
    .map { (group, grouped) -> TaskUiSection(group, tasksInDisplayOrder(grouped)) }
sealed interface TasksState { data object Loading : TasksState; data class Ready(val items: List<TaskItem>, val sections: List<TaskUiSection> = taskUiSections(items)) : TasksState; data class Error(val message: String) : TasksState }
class TasksViewModel(private val local: TaskLocalRepository) : ViewModel() {
    private val mutable = MutableStateFlow<TasksState>(TasksState.Loading); val state = mutable.asStateFlow()
    init { viewModelScope.launch { local.visible.collect { mutable.value = TasksState.Ready(it) } } }
    fun load(url: String) = viewModelScope.launch { local.schedule(url) }
    private fun changed(url: String, block: suspend () -> Unit) = viewModelScope.launch { block(); local.schedule(url) }
    fun toggle(url: String, item: TaskItem) = changed(url) { local.toggle(item) }
    fun delete(url: String, item: TaskItem) = changed(url) { local.delete(item) }
    fun save(url: String, original: TaskItem?, draft: TaskItemCreate) = changed(url) { if (original == null) local.create(draft) else local.edit(original, draft) }
    fun reorder(url: String, reordered: List<TaskItem>, onFailure: () -> Unit = {}) = viewModelScope.launch {
        runCatching { local.reorder(reordered); local.schedule(url) }.onFailure { onFailure() }
    }
}
