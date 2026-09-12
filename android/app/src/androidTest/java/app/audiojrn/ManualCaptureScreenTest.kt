package app.audiojrn

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test

/** Smoke instrumentado de la pantalla; no usa micrófono ni backend. */
class ManualCaptureScreenTest {
    @Test fun calendar_intent_has_android_contract_and_milliseconds() {
        val action = ProposedAction("1", "event", "pending", "Synthetic", startAt = "2026-09-07T17:00:00+02:00", evidence = "Synthetic", createdAt = "2026-09-07T00:00:00Z")
        val intent = calendarInsertIntent(action)!!
        org.junit.Assert.assertEquals(android.content.Intent.ACTION_INSERT, intent.action)
        org.junit.Assert.assertEquals(android.provider.CalendarContract.Events.CONTENT_URI, intent.data)
        org.junit.Assert.assertEquals(java.time.Instant.parse("2026-09-07T15:00:00Z").toEpochMilli(), intent.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        org.junit.Assert.assertFalse(intent.hasExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME))
    }
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun initial_screen_exposes_manual_capture_controls() {
        rule.setContent { AudioJrnScreen(CaptureViewModel(NoopRecorder(), NoopBackend()), rule.activity.getSharedPreferences("test", 0)) }
        rule.onNodeWithText("AudioJrn").assertIsDisplayed()
        rule.onNodeWithText("Grabar").assertIsDisplayed()
    }
    private class NoopRecorder : AudioRecorder { override fun start(): CapturedAudio = error("not used"); override fun stop(): CapturedAudio = error("not used"); override fun discard() = Unit }
    private class NoopBackend : BackendRepository { override suspend fun process(audio: CapturedAudio, backendUrl: String): ProcessResponse = error("not used") }
}
