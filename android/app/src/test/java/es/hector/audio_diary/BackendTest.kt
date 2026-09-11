package es.hector.audio_diary

import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BackendTest {
    private val strictJson = Json { ignoreUnknownKeys = false }

    @Test fun daily_summary_state_deserializes_complete_backend_contract_strictly() {
        val payload = """{
            "day":"2026-09-09",
            "timezone":"Europe/Madrid",
            "interactions":[],
            "events":[],
            "highlights":[],
            "summary":{
                "status":"ready",
                "result":{"summary":"Día productivo","highlights":["Memoria revisada"]},
                "generated_at":"2026-09-09T17:42:00Z",
                "model":"test-model",
                "updated_at":"2026-09-09T17:45:00Z",
                "manually_edited":true
            }
        }""".trimIndent()

        val day = strictJson.decodeFromString<DayResponse>(payload)

        assertEquals("ready", day.summary.status)
        assertEquals("Día productivo", day.summary.result?.summary)
        assertEquals("test-model", day.summary.model)
        assertEquals("2026-09-09T17:45:00Z", day.summary.updatedAt)
        assertTrue(day.summary.manuallyEdited)
    }

    @Test fun task_item_deserializes_complete_backend_contract_strictly() {
        val payload = """{
            "id":"11111111-1111-1111-1111-111111111111",
            "text":"Preparar memoria",
            "completed":false,
            "group_name":"TFM",
            "parent_id":null,
            "sort_order":3,
            "due_at":"2026-09-12T10:00:00Z",
            "all_day":false,
            "source_action_id":"22222222-2222-2222-2222-222222222222",
            "created_at":"2026-09-09T08:00:00Z",
            "updated_at":"2026-09-09T09:00:00Z"
        }""".trimIndent()

        val task = strictJson.decodeFromString<TaskItem>(payload)

        assertEquals("TFM", task.groupName)
        assertEquals(3, task.sortOrder)
        assertEquals("22222222-2222-2222-2222-222222222222", task.sourceActionId)
        assertEquals("2026-09-09T08:00:00Z", task.createdAt)
        assertEquals("2026-09-09T09:00:00Z", task.updatedAt)
    }

    @Test fun calendar_candidate_maps_to_reviewable_insert_values_without_write_permission() {
        val action = ProposedAction(
            id = "1", kind = "event", status = "pending", title = "Tutoría",
            startAt = "2026-09-07T17:00:00+02:00", evidence = "tutoría el lunes",
            createdAt = "2026-09-07T10:00:00Z",
        )
        val values = calendarInsertValues(action)
        assertNotNull(values)
        assertEquals("Tutoría", values!!.title)
        assertTrue(values.beginMillis > 0)
        assertEquals(values.beginMillis + 3_600_000L, values.endMillis)
        assertFalse(values.allDay)
    }

    @Test fun calendar_candidate_without_concrete_start_is_not_exported() {
        val action = ProposedAction(
            id = "1", kind = "event", status = "pending", title = "Tutoría",
            evidence = "algún día", createdAt = "2026-09-07T10:00:00Z",
        )
        assertNull(calendarInsertValues(action))
    }

    @Test fun dated_task_and_reminder_use_calendar_only_with_ui_default_end() {
        val task = ProposedAction(
            id = "task", kind = "task", status = "pending", title = "Compra",
            dueText = "2026-09-10", startAt = "2026-09-10", allDay = true,
            evidence = "Para mañana tengo como tarea hacer la compra", createdAt = "2026-09-08T10:00:00Z",
        )
        val allDay = calendarInsertValues(task)!!
        assertTrue(allDay.allDay)
        assertEquals(86_400_000L, allDay.endMillis - allDay.beginMillis)

        val reminder = ProposedAction(
            id = "reminder", kind = "reminder", status = "pending", title = "Ordenador",
            dueText = "2026-09-09T06:00:00+02:00", startAt = "2026-09-09T06:00:00+02:00",
            evidence = "Recuérdame mañana", createdAt = "2026-09-08T10:00:00Z",
        )
        val timed = calendarInsertValues(reminder)!!
        assertFalse(timed.allDay)
        assertEquals(3_600_000L, timed.endMillis - timed.beginMillis)
    }

    @Test fun chronology_keeps_every_chunk_as_an_individual_row() {
        val analysis = Analysis("summary", emptyList(), emptyList(), emptyList(), emptyList())
        fun item(id: String, mode: String, session: String?, index: Int?) = InteractionResponse(
            id, "2026-09-05T10:00:00Z", "2026-09-05T10:00:00Z",
            Transcription("text", "es", "base"), analysis, captureMode = mode,
            captureSessionId = session, chunkIndex = index,
        )
        val rows = flatChronology(listOf(
            item("b", "continuous", "s1", 1), item("a", "continuous", "s1", 0),
            item("c", "continuous", "s2", 0), item("legacy", "manual", null, null),
        ), newestFirst = false)
        assertEquals(listOf("a", "b", "c", "legacy"), rows.map(InteractionResponse::id))
    }
    @Test fun normalizes_valid_url_and_rejects_invalid_one() {
        assertEquals("http://example.test:8000/", normalizeBackendUrl(" http://example.test:8000/ "))
        assertFails { normalizeBackendUrl("example.test") }
    }
    @Test fun process_sends_multipart_with_audio_and_utc_timestamp() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        val file = File.createTempFile("audio", ".m4a").apply { writeBytes(byteArrayOf(1, 2)) }
        val result = RetrofitBackendRepository().process(CapturedAudio(file, Instant.parse("2026-09-05T10:00:00Z"), "continuous", "11111111-1111-1111-1111-111111111111", 2, "22222222-2222-2222-2222-222222222222"), server.url("/").toString())
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/process", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(body.contains("name=\"file\"")); assertTrue(body.contains(file.name)); assertTrue(body.contains("audio/mp4"))
        assertTrue(body.contains("name=\"recorded_at\"")); assertTrue(body.contains("2026-09-05T10:00:00Z"))
        assertTrue(body.contains("name=\"capture_mode\"")); assertTrue(body.contains("continuous"))
        assertTrue(body.contains("name=\"capture_session_id\"")); assertTrue(body.contains("11111111-1111-1111-1111-111111111111"))
        assertTrue(body.contains("name=\"chunk_index\"")); assertTrue(body.contains("name=\"capture_chunk_id\""))
        assertEquals("id-1", result.interactionId); assertEquals("hola", result.transcription.text)
        assertEquals("d", result.analysis.highlights.single().text); assertEquals("t", result.analysis.tasks.single().text); assertTrue(result.analysis.events.isEmpty())
        file.delete(); server.shutdown()
    }
    @Test fun parses_http_errors() = runBlocking {
        val server = MockWebServer(); server.start(); server.enqueue(MockResponse().setResponseCode(500))
        val file = File.createTempFile("audio", ".m4a")
        try { RetrofitBackendRepository().process(CapturedAudio(file, Instant.now()), server.url("/").toString()); fail("Expected failure") } catch (_: Exception) {} finally { file.delete(); server.shutdown() }
    }
    @Test fun deletes_interactions_and_continuous_sessions_through_explicit_endpoints() = runBlocking {
        val server = MockWebServer(); server.start()
        val body = "{\"interactions_deleted\":2,\"actions_deleted\":3,\"daily_summaries_deleted\":1}"
        server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(body))
        val repo = RetrofitBackendRepository(); val url = server.url("/").toString()
        assertEquals(2, repo.deleteInteraction("interaction-id", url).interactionsDeleted)
        assertEquals(3, repo.deleteContinuousSession("session-id", url).actionsDeleted)
        val interactionRequest = server.takeRequest()
        val sessionRequest = server.takeRequest()
        assertEquals("DELETE", interactionRequest.method); assertEquals("/interactions/interaction-id", interactionRequest.path)
        assertEquals("DELETE", sessionRequest.method); assertEquals("/continuous-sessions/session-id", sessionRequest.path)
        server.shutdown()
    }
    @Test fun deletes_one_or_all_pending_actions_through_delete_contracts() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setBody("{\"deleted_count\":1}"))
        server.enqueue(MockResponse().setBody("{\"deleted_count\":4}"))
        val repo = RetrofitBackendRepository(); val url = server.url("/").toString()
        assertEquals(1, repo.deleteAction("action-id", url).deletedCount)
        assertEquals(4, repo.deletePendingActions(url).deletedCount)
        val one = server.takeRequest(); val all = server.takeRequest()
        assertEquals("DELETE", one.method); assertEquals("/actions/action-id", one.path)
        assertEquals("DELETE", all.method); assertEquals("/actions/pending", all.path)
        server.shutdown()
    }
    @Test fun cleans_exported_actions_through_dedicated_delete_contract() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setBody("{\"deleted_count\":3}"))
        val response = RetrofitBackendRepository().deleteExportedActions(server.url("/").toString())
        val request = server.takeRequest()
        assertEquals(3, response.deletedCount)
        assertEquals("DELETE", request.method)
        assertEquals("/actions/exported", request.path)
        server.shutdown()
    }
    @Test fun task_editor_patch_sends_explicit_nulls_to_clear_group_parent_and_due_date() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setBody("{\"text\":\"Editada\"}"))
        val repo = RetrofitBackendRepository(); val url = server.url("/").toString()
        repo.updateTaskFromEditor("task-id", TaskItemEditorUpdate("Editada", null, null, null, false), url)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method); assertEquals("/tasks/task-id", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"group_name\":null"))
        assertTrue(body.contains("\"parent_id\":null"))
        assertTrue(body.contains("\"due_at\":null"))
        server.shutdown()
    }
    @Test fun task_reorder_sends_one_atomic_order_request_without_parent_changes() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setBody("[]"))
        val tasks = listOf(
            TaskItem("a", "A", groupName = "Casa", sortOrder = 0),
            TaskItem("child", "Child", groupName = "Casa", parentId = "a", sortOrder = 0),
        )
        RetrofitBackendRepository().reorderTasks(tasks, server.url("/").toString())
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/tasks/reorder", request.path)
        assertTrue(body.contains("\"group_name\":\"Casa\""))
        assertFalse(body.contains("parent_id"))
        server.shutdown()
    }
    @Test fun reads_health_history_day_and_explicit_summary_endpoints() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(MockResponse().setBody("{\"status\":\"ready\",\"analysis_configured\":false}"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("{\"day\":\"2026-09-05\",\"timezone\":\"UTC\",\"interactions\":[],\"events\":[],\"highlights\":[],\"summary\":{\"status\":\"missing\",\"result\":null,\"generated_at\":null,\"model\":null}}"))
        server.enqueue(MockResponse().setBody("{\"status\":\"ready\",\"result\":{\"summary\":\"ok\",\"highlights\":[]},\"generated_at\":null,\"model\":\"test\"}"))
        val repo = RetrofitBackendRepository(); val url = server.url("/").toString()
        assertEquals("ready", repo.health(url).status); assertTrue(repo.interactions(url).isEmpty()); assertEquals("missing", repo.day(url, "2026-09-05").summary.status); assertEquals("ready", repo.generateSummary(url, "2026-09-05").status)
        assertEquals("/health", server.takeRequest().path); assertEquals("/interactions?limit=50&offset=0", server.takeRequest().path); assertEquals("/days/2026-09-05", server.takeRequest().path); assertEquals("POST", server.takeRequest().method); server.shutdown()
    }
    private fun assertFails(block: () -> Unit) { try { block(); fail("Expected failure") } catch (_: Exception) {} }
    private val responseJson = """{"interaction_id":"id-1","recorded_at":"2026-09-05T10:00:00Z","created_at":"2026-09-05T10:01:00Z","transcription":{"text":"hola","language":"es","model":"base","device":"cuda","compute_type":"int8_float16"},"analysis":{"highlights":[{"text":"d","evidence":"e"}],"tasks":[{"text":"t","due_at":null,"evidence":"e"}],"events":[]}}"""
}
