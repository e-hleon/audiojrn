package app.audiojrn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRepositoryTest {
    @Test fun all_day_draft_is_one_exclusive_utc_day() {
        val draft = calendarEventDraft(action(startAt = "2026-09-09", allDay = true), 7L, "Europe/Madrid")!!
        assertTrue(draft.allDay)
        assertEquals("UTC", draft.timezone)
        assertEquals(86_400_000L, draft.endMillis - draft.beginMillis)
    }

    @Test fun timed_draft_uses_one_hour_only_when_end_is_missing_and_preserves_explicit_end() {
        val timed = calendarEventDraft(action(startAt = "2026-09-09T06:00:00+02:00"), 7L, "Europe/Madrid")!!
        assertFalse(timed.allDay)
        assertEquals("Europe/Madrid", timed.timezone)
        assertEquals(3_600_000L, timed.endMillis - timed.beginMillis)

        val explicit = calendarEventDraft(action(
            startAt = "2026-09-09T06:00:00+02:00", endAt = "2026-09-09T08:30:00+02:00"
        ), 7L, "Europe/Madrid")!!
        assertEquals(9_000_000L, explicit.endMillis - explicit.beginMillis)
    }

    @Test fun selected_calendar_is_reused_only_while_it_is_writable() {
        val store = MemoryCalendarSelectionStore(2L)
        val calendars = listOf(calendar(1L), calendar(2L))
        assertEquals(2L, savedWritableCalendar(calendars, store)!!.id)
        assertNull(savedWritableCalendar(listOf(calendar(1L)), store))
        assertNull(store.selectedCalendarId())
    }

    @Test fun selection_mode_defaults_to_remember_and_can_be_changed() {
        val store = MemoryCalendarSelectionStore(9L)
        assertEquals(CalendarSelectionMode.REMEMBER, store.mode())
        store.saveMode(CalendarSelectionMode.ALWAYS_ASK)
        assertEquals(CalendarSelectionMode.ALWAYS_ASK, store.mode())
        assertEquals(9L, store.selectedCalendarId())
    }

    @Test fun always_ask_does_not_reuse_saved_id_when_multiple_calendars_exist() {
        val store = MemoryCalendarSelectionStore(2L)
        store.saveMode(CalendarSelectionMode.ALWAYS_ASK)
        assertNull(automaticallySelectedWritableCalendar(listOf(calendar(1L), calendar(2L)), store))
        assertEquals(1L, automaticallySelectedWritableCalendar(listOf(calendar(1L)), store)!!.id)
    }

    @Test fun gateway_result_distinguishes_success_from_failed_insert() {
        val successful: CalendarGateway = FakeCalendarGateway(CalendarInsertResult.Inserted("content://events/42"))
        val failed: CalendarGateway = FakeCalendarGateway(CalendarInsertResult.Failed)
        val draft = calendarEventDraft(action(startAt = "2026-09-09T06:00:00+02:00"), 7L)!!
        assertTrue(successful.insert(draft) is CalendarInsertResult.Inserted)
        assertTrue(failed.insert(draft) is CalendarInsertResult.Failed)
    }

    private fun action(startAt: String, endAt: String? = null, allDay: Boolean = false) = ProposedAction(
        id = "action", kind = "task", status = "pending", title = "Synthetic", startAt = startAt,
        endAt = endAt, allDay = allDay, evidence = "Synthetic", createdAt = "2026-09-08T10:00:00Z",
    )

    private fun calendar(id: Long) = WritableCalendar(id, "Calendar $id", null, null)
}

private class MemoryCalendarSelectionStore(initial: Long?) : CalendarSelectionStore {
    private var value = initial
    private var selectionMode = CalendarSelectionMode.REMEMBER
    override fun selectedCalendarId(): Long? = value
    override fun save(calendarId: Long) { value = calendarId }
    override fun clear() { value = null }
    override fun mode(): CalendarSelectionMode = selectionMode
    override fun saveMode(mode: CalendarSelectionMode) { selectionMode = mode }
}

private class FakeCalendarGateway(private val result: CalendarInsertResult) : CalendarGateway {
    override fun writableCalendars() = emptyList<WritableCalendar>()
    override fun insert(draft: CalendarEventDraft) = result
}
