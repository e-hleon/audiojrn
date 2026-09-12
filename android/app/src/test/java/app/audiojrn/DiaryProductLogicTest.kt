package app.audiojrn

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryProductLogicTest {
    private val analysis = Analysis(highlights = emptyList(), tasks = emptyList(), events = emptyList())

    private fun interaction(id: String, recordedAt: String, session: String? = null, chunk: Int? = null) = InteractionResponse(
        id = id,
        recordedAt = recordedAt,
        createdAt = recordedAt,
        transcription = Transcription(id, "es", "base"),
        analysis = analysis,
        captureMode = if (session == null) "manual" else "continuous",
        captureSessionId = session,
        chunkIndex = chunk,
    )

    @Test fun diary_order_is_flat_for_manual_and_continuous_chunks() {
        val items = listOf(
            interaction("new", "2026-09-09T12:00:00Z"),
            interaction("chunk-2", "2026-09-09T11:01:00Z", "session", 1),
            interaction("old", "2026-09-09T10:00:00Z"),
            interaction("chunk-1", "2026-09-09T11:00:00Z", "session", 0),
        )
        assertEquals(listOf("old", "chunk-1", "chunk-2", "new"), flatChronology(items, false).map { it.id })
        assertEquals(listOf("new", "chunk-2", "chunk-1", "old"), flatChronology(items, true).map { it.id })
    }

    @Test fun diary_order_compares_instants_instead_of_iso_offset_text() {
        val earlier = interaction("earlier", "2026-09-09T10:30:00+02:00")
        val later = interaction("later", "2026-09-09T09:00:00Z")
        assertEquals(listOf("earlier", "later"), flatChronology(listOf(later, earlier), false).map { it.id })
    }

    @Test fun select_all_toggles_and_protected_preflight_is_atomic() {
        val all = setOf("interaction:a", "interaction:b")
        assertEquals(all, toggleSelectAll(emptySet(), all))
        assertEquals(emptySet<String>(), toggleSelectAll(all, all))
        val interactions = listOf(
            interaction("a", "2026-09-09T10:00:00Z", "old-open", 0),
            interaction("b", "2026-09-09T10:01:00Z", "active", 0),
            interaction("c", "2026-09-09T10:02:00Z", "pending", 0),
        )
        assertEquals(emptySet<String>(), protectedSelectedSessions(interactions, setOf("interaction:a"), setOf("active", "pending")))
        assertEquals(setOf("active"), protectedSelectedSessions(interactions, setOf("interaction:a", "interaction:b"), setOf("active", "pending")))
        assertTrue(protectedSelectedSessions(interactions, setOf("interaction:a", "interaction:c"), setOf("pending")).isNotEmpty())
    }

    @Test fun chronology_back_closes_search_before_leaving_chronology() {
        assertEquals(ChronologyBackAction.CLOSE_SEARCH, chronologyBackAction(searchOpen = true))
        assertEquals(ChronologyBackAction.LEAVE_CHRONOLOGY, chronologyBackAction(searchOpen = false))
    }

    @Test fun task_due_format_hides_midnight_for_all_day_and_keeps_time_for_timed() {
        val madrid = ZoneId.of("Europe/Madrid")
        val spanish = Locale.forLanguageTag("es-ES")
        assertEquals("9 sept 2026", formatTaskDue("2026-09-08T22:00:00Z", true, madrid, spanish))
        assertEquals("9 sept 2026 · 10:30", formatTaskDue("2026-09-09T08:30:00Z", false, madrid, spanish))
    }

    @Test fun generated_summary_timestamp_contains_local_date_and_time() {
        assertEquals(
            "9 sept 2026 · 10:32",
            formatGeneratedAt("2026-09-09T08:32:00Z", ZoneId.of("Europe/Madrid"), Locale.forLanguageTag("es-ES")),
        )
        assertEquals(
            "Desactualizado · 9 sept 2026 · 10:32",
            summaryStatusText(
                "stale", "2026-09-09T08:32:00Z",
                ZoneId.of("Europe/Madrid"), Locale.forLanguageTag("es-ES"),
            ),
        )
    }

    @Test fun drag_moves_root_between_groups_and_keeps_children_with_parent() {
        val items = listOf(
            TaskItem("a", "A", groupName = "Trabajo", sortOrder = 0),
            TaskItem("b", "B", groupName = "Trabajo", sortOrder = 1),
            TaskItem("child", "B child", groupName = "Trabajo", parentId = "b", sortOrder = 0),
            TaskItem("c", "C", groupName = "Casa", sortOrder = 0),
            TaskItem("none", "None", groupName = null, sortOrder = 0),
        )

        val withinWork = moveTaskForDrag(items, "b", -1)
        val moved = moveTaskForDrag(withinWork, "b", -1)

        assertEquals("Casa", moved.single { it.id == "b" }.groupName)
        assertEquals("Casa", moved.single { it.id == "child" }.groupName)
        assertEquals("b", moved.single { it.id == "child" }.parentId)
        assertEquals(1, moved.single { it.id == "b" }.sortOrder)
        assertEquals(0, moved.single { it.id == "c" }.sortOrder)

        val childMove = moveTaskForDrag(moved, "child", 1)
        assertEquals("Casa", childMove.single { it.id == "child" }.groupName)
        assertEquals("b", childMove.single { it.id == "child" }.parentId)

        val movedToNoGroup = moveTaskForDrag(items, "b", 1)
        assertEquals(null, movedToNoGroup.single { it.id == "b" }.groupName)
        assertEquals(null, movedToNoGroup.single { it.id == "child" }.groupName)
    }

    @Test fun reorder_only_moves_tasks_with_exact_same_group_and_level() {
        val items = listOf(
            TaskItem("a", "A", groupName = "Uno", sortOrder = 0),
            TaskItem("b", "B", groupName = "Uno", sortOrder = 1),
            TaskItem("case", "Case", groupName = "uno", sortOrder = 0),
            TaskItem("child", "Child", groupName = "Uno", parentId = "a", sortOrder = 0),
        )
        val moved = moveTaskWithinPeers(items, "b", -1)
        assertEquals(0, moved.single { it.id == "b" }.sortOrder)
        assertEquals(1, moved.single { it.id == "a" }.sortOrder)
        assertEquals(0, moved.single { it.id == "case" }.sortOrder)
        assertEquals(0, moved.single { it.id == "child" }.sortOrder)
    }
}
