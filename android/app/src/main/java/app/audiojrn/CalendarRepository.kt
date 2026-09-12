package app.audiojrn

import android.content.ContentResolver
import android.content.ContentValues
import android.content.SharedPreferences
import android.provider.CalendarContract
import java.util.TimeZone

data class WritableCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String?,
    val accountType: String?,
) {
    val label: String
        get() = listOfNotNull(displayName.ifBlank { null }, accountName).joinToString(" · ")
            .ifBlank { "Calendario $id" }
}

data class CalendarEventDraft(
    val calendarId: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timezone: String,
    val location: String?,
    val description: String,
)

sealed interface CalendarInsertResult {
    data class Inserted(val uri: String) : CalendarInsertResult
    data object Failed : CalendarInsertResult
}

fun calendarEventDraft(
    action: ProposedAction,
    calendarId: Long,
    deviceTimezone: String = TimeZone.getDefault().id,
): CalendarEventDraft? {
    val values = calendarInsertValues(action) ?: return null
    return CalendarEventDraft(
        calendarId = calendarId,
        title = values.title,
        beginMillis = values.beginMillis,
        endMillis = values.endMillis,
        allDay = values.allDay,
        timezone = if (values.allDay) "UTC" else deviceTimezone,
        location = values.location,
        description = values.description,
    )
}

interface CalendarGateway {
    fun writableCalendars(): List<WritableCalendar>
    fun insert(draft: CalendarEventDraft): CalendarInsertResult
}

interface CalendarSelectionStore {
    fun selectedCalendarId(): Long?
    fun save(calendarId: Long)
    fun clear()
    fun mode(): CalendarSelectionMode
    fun saveMode(mode: CalendarSelectionMode)
}

enum class CalendarSelectionMode { REMEMBER, ALWAYS_ASK }

class SharedPreferencesCalendarSelectionStore(private val preferences: SharedPreferences) : CalendarSelectionStore {
    override fun selectedCalendarId(): Long? = preferences.getLong(KEY_CALENDAR_ID, NO_CALENDAR_ID)
        .takeIf { it != NO_CALENDAR_ID }
    override fun save(calendarId: Long) { preferences.edit().putLong(KEY_CALENDAR_ID, calendarId).apply() }
    override fun clear() { preferences.edit().remove(KEY_CALENDAR_ID).apply() }
    override fun mode(): CalendarSelectionMode = when (preferences.getString(KEY_SELECTION_MODE, null)) {
        "always_ask" -> CalendarSelectionMode.ALWAYS_ASK
        else -> CalendarSelectionMode.REMEMBER
    }
    override fun saveMode(mode: CalendarSelectionMode) {
        preferences.edit().putString(KEY_SELECTION_MODE, if (mode == CalendarSelectionMode.ALWAYS_ASK) "always_ask" else "remember").apply()
    }

    private companion object {
        const val KEY_CALENDAR_ID = "selected_calendar_id"
        const val KEY_SELECTION_MODE = "calendar_selection_mode"
        const val NO_CALENDAR_ID = -1L
    }
}

class AndroidCalendarGateway(private val resolver: ContentResolver) : CalendarGateway {
    override fun writableCalendars(): List<WritableCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        return resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(WritableCalendar(
                            id = cursor.getLong(0),
                            displayName = cursor.getString(1).orEmpty(),
                            accountName = cursor.getString(2),
                            accountType = cursor.getString(3),
                        ))
                    }
                }
            }.orEmpty()
    }

    override fun insert(draft: CalendarEventDraft): CalendarInsertResult = try {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, draft.calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DTSTART, draft.beginMillis)
            put(CalendarContract.Events.DTEND, draft.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, draft.timezone)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, draft.timezone)
            draft.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.DESCRIPTION, draft.description)
        }
        resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { CalendarInsertResult.Inserted(it.toString()) }
            ?: CalendarInsertResult.Failed
    } catch (_: SecurityException) {
        CalendarInsertResult.Failed
    } catch (_: IllegalArgumentException) {
        CalendarInsertResult.Failed
    } catch (_: RuntimeException) {
        CalendarInsertResult.Failed
    }
}

fun savedWritableCalendar(
    calendars: List<WritableCalendar>,
    store: CalendarSelectionStore,
): WritableCalendar? {
    val saved = store.selectedCalendarId() ?: return null
    return calendars.firstOrNull { it.id == saved } ?: run { store.clear(); null }
}

fun automaticallySelectedWritableCalendar(
    calendars: List<WritableCalendar>,
    store: CalendarSelectionStore,
): WritableCalendar? {
    if (store.mode() == CalendarSelectionMode.ALWAYS_ASK) return calendars.singleOrNull()
    return savedWritableCalendar(calendars, store) ?: calendars.singleOrNull()
}
