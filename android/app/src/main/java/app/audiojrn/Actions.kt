package app.audiojrn

import android.content.Intent
import android.provider.CalendarContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

const val KEY_DATED_TASK_DESTINATION = "dated_task_destination"

enum class DatedTaskDestination(val storedValue: String) {
    BOTH("both"),
    TASKS("tasks"),
    CALENDAR("calendar");

    companion object {
        fun fromStored(value: String?): DatedTaskDestination =
            values().firstOrNull { it.storedValue == value } ?: BOTH
    }
}

data class AcceptancePlan(val addToTasks: Boolean, val addToCalendar: Boolean)

fun acceptancePlan(
    action: ProposedAction,
    datedTaskDestination: DatedTaskDestination,
): AcceptancePlan = when {
    action.kind == "event" -> AcceptancePlan(addToTasks = false, addToCalendar = true)
    action.startAt == null && action.dueText == null ->
        AcceptancePlan(addToTasks = true, addToCalendar = false)
    datedTaskDestination == DatedTaskDestination.TASKS ->
        AcceptancePlan(addToTasks = true, addToCalendar = false)
    datedTaskDestination == DatedTaskDestination.CALENDAR ->
        AcceptancePlan(addToTasks = false, addToCalendar = true)
    else -> AcceptancePlan(addToTasks = true, addToCalendar = true)
}

data class CalendarInsertValues(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
    val description: String,
)

fun calendarInsertValues(action: ProposedAction): CalendarInsertValues? {
    if (action.kind !in setOf("event", "reminder", "task")) return null
    val value = action.startAt ?: if (action.kind == "reminder" || action.kind == "task") action.dueText else null
    if (value == null) return null
    fun parse(value: String): Long = if (action.allDay) {
        LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    } else OffsetDateTime.parse(value).toInstant().toEpochMilli()
    val start = runCatching { parse(value) }.getOrNull() ?: return null
    val end = action.endAt?.let { runCatching { parse(it) }.getOrNull() ?: return null }
        ?: start + if (action.allDay) 86_400_000L else 3_600_000L
    if (end <= start) return null
    return CalendarInsertValues(action.title, start, end, action.allDay, action.location,
        listOfNotNull(action.notes, "Evidencia: ${action.evidence}").joinToString("\n"))
}

fun calendarInsertIntent(action: ProposedAction): Intent? {
    val values = calendarInsertValues(action) ?: return null
    return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, values.title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, values.beginMillis)
        .apply {
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, values.endMillis)
            putExtra(CalendarContract.Events.ALL_DAY, values.allDay)
            values.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            putExtra(CalendarContract.Events.DESCRIPTION, values.description)
        }
}

sealed interface ActionsState {
    data object Idle : ActionsState
    data object Loading : ActionsState
    data class Ready(
        val items: List<ProposedAction>,
        val includeResolved: Boolean,
        val nextOffset: Int,
        val canLoadMore: Boolean,
    ) : ActionsState
    data class Error(val message: String) : ActionsState
}

class ActionsViewModel(private val backend: BackendRepository) : ViewModel() {
    companion object { const val PAGE_SIZE = 50 }
    private val mutable = MutableStateFlow<ActionsState>(ActionsState.Idle)
    val state: StateFlow<ActionsState> = mutable.asStateFlow()

    fun load(url: String, includeResolved: Boolean = false) {
        if (url.isBlank()) {
            mutable.value = ActionsState.Error("Configura la URL del backend")
            return
        }
        mutable.value = ActionsState.Loading
        viewModelScope.launch {
            runCatching { backend.actions(url, PAGE_SIZE, 0, includeResolved) }
                .onSuccess { items ->
                    mutable.value = ActionsState.Ready(items.distinctBy { it.id }, includeResolved, items.size, items.size == PAGE_SIZE)
                }
                .onFailure { mutable.value = ActionsState.Error("No se pudieron cargar las acciones") }
        }
    }

    fun loadMore(url: String) {
        val current = mutable.value as? ActionsState.Ready ?: return
        if (!current.canLoadMore || url.isBlank()) return
        viewModelScope.launch {
            runCatching { backend.actions(url, PAGE_SIZE, current.nextOffset, current.includeResolved) }
                .onSuccess { page ->
                    mutable.value = current.copy(
                        items = (current.items + page).distinctBy { it.id },
                        nextOffset = current.nextOffset + page.size,
                        canLoadMore = page.size == PAGE_SIZE,
                    )
                }
                .onFailure { mutable.value = ActionsState.Error("No se pudieron cargar más acciones") }
        }
    }

    fun update(url: String, action: ProposedAction, update: ActionUpdate) {
        viewModelScope.launch {
            runCatching { backend.updateAction(action.id, update, url) }
                .onSuccess { updated ->
                    applyUpdatedAction(updated)
                }
                .onFailure { mutable.value = ActionsState.Error("No se pudo actualizar la acción") }
        }
    }

    fun markCalendarExported(
        url: String,
        action: ProposedAction,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { backend.updateAction(action.id, ActionUpdate(status = "exported"), url) }
                .onSuccess { updated -> applyUpdatedAction(updated); onSuccess() }
                .onFailure { onFailure() }
        }
    }

    private fun applyUpdatedAction(updated: ProposedAction) {
        val current = mutable.value as? ActionsState.Ready ?: return
        mutable.value = current.copy(
            items = current.items.map { if (it.id == updated.id) updated else it }
                .filter { current.includeResolved || it.status == "pending" }
        )
    }

    fun deleteAllPending(url: String, onSuccess: () -> Unit = {}) {
        val current = mutable.value as? ActionsState.Ready ?: return
        if (url.isBlank()) return
        mutable.value = ActionsState.Loading
        viewModelScope.launch {
            runCatching { backend.deletePendingActions(url) }
                .onSuccess { load(url, current.includeResolved); onSuccess() }
                .onFailure { mutable.value = ActionsState.Error("No se pudieron eliminar las acciones pendientes") }
        }
    }

    fun deleteAllExported(url: String) {
        val current = mutable.value as? ActionsState.Ready ?: return
        if (url.isBlank()) return
        mutable.value = ActionsState.Loading
        viewModelScope.launch {
            runCatching { backend.deleteExportedActions(url) }
                .onSuccess { load(url, current.includeResolved) }
                .onFailure { mutable.value = ActionsState.Error("No se pudieron limpiar las acciones gestionadas") }
        }
    }

    fun delete(url: String, action: ProposedAction, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { backend.deleteAction(action.id, url) }
                .onSuccess {
                    val current = mutable.value as? ActionsState.Ready ?: return@onSuccess
                    mutable.value = current.copy(items = current.items.filterNot { it.id == action.id })
                    onSuccess()
                }
                .onFailure { mutable.value = ActionsState.Error("No se pudo descartar la acción") }
        }
    }

    fun addToTasks(
        url: String,
        action: ProposedAction,
        markResolved: Boolean = true,
        onSuccess: (TaskItem) -> Unit = {},
        onFailure: () -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { backend.addActionToTasks(action.id, url) }
                .onSuccess { task ->
                    if (markResolved) applyUpdatedAction(action.copy(status = "exported"))
                    onSuccess(task)
                }
                .onFailure {
                    onFailure()
                }
        }
    }

    fun restorePendingAfterPartialFailure(url: String, action: ProposedAction, onFailure: () -> Unit) {
        viewModelScope.launch {
            runCatching { backend.updateAction(action.id, ActionUpdate(status = "pending"), url) }
                .onSuccess { applyUpdatedAction(it) }
                .onFailure { onFailure() }
        }
    }

    fun markAcceptedLocally(action: ProposedAction) {
        applyUpdatedAction(action.copy(status = "exported"))
    }
}
