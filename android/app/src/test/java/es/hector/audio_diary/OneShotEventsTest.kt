package es.hector.audio_diary

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OneShotEventsTest {
    @Test fun widget_events_survive_cold_start_and_repeat_without_reopening() = runTest {
        val events = WidgetNavigationEvents()
        events.emit(WidgetNavigationCommand.OPEN_TASKS)
        assertEquals(WidgetNavigationCommand.OPEN_TASKS, events.flow.first())

        val repeated = async { events.flow.take(2).toList() }
        runCurrent()
        events.emit(WidgetNavigationCommand.OPEN_NEW_TASK)
        events.emit(WidgetNavigationCommand.OPEN_NEW_TASK)
        assertEquals(
            listOf(WidgetNavigationCommand.OPEN_NEW_TASK, WidgetNavigationCommand.OPEN_NEW_TASK),
            repeated.await(),
        )
    }

    @Test fun process_now_feedback_is_consumed_once() = runTest {
        val events = CaptureUiEvents()
        events.emit("enviado")
        assertEquals("enviado", events.flow.first())
        val next = async { events.flow.first() }
        runCurrent()
        events.emit("nuevo")
        assertEquals("nuevo", next.await())
    }
}
