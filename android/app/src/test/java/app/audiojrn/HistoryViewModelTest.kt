package app.audiojrn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun deleting_a_continuous_session_removes_all_its_chunks_without_touching_manual() = runTest {
        val backend = HistoryDeleteBackend()
        val viewModel = HistoryViewModel(backend)
        viewModel.load("http://example.test")
        advanceUntilIdle()

        viewModel.deleteContinuousSession("http://example.test", "session-a")
        advanceUntilIdle()

        val items = (viewModel.state.value as HistoryState.Ready).items
        assertEquals(listOf("manual"), items.map { it.id })
        assertEquals(listOf("session-a"), backend.deletedSessions)
        assertEquals(emptyList<String>(), backend.deletedInteractions)
    }

    @Test fun deleting_manual_entry_uses_interaction_endpoint() = runTest {
        val backend = HistoryDeleteBackend()
        val viewModel = HistoryViewModel(backend)
        viewModel.load("http://example.test")
        advanceUntilIdle()

        viewModel.deleteInteraction("http://example.test", "manual")
        advanceUntilIdle()

        assertEquals(listOf("chunk-0", "chunk-1"), (viewModel.state.value as HistoryState.Ready).items.map { it.id })
        assertEquals(listOf("manual"), backend.deletedInteractions)
    }

    @Test fun opening_and_closing_detail_keeps_loaded_items() = runTest {
        val backend = HistoryDeleteBackend()
        val viewModel = HistoryViewModel(backend)
        viewModel.load("http://example.test")
        advanceUntilIdle()

        viewModel.detail("http://example.test", "manual")
        advanceUntilIdle()
        val withDetail = viewModel.state.value as HistoryState.Ready
        assertEquals(listOf("chunk-0", "chunk-1", "manual"), withDetail.items.map { it.id })
        assertEquals("manual", withDetail.detail?.id)

        viewModel.closeDetail()
        val afterClose = viewModel.state.value as HistoryState.Ready
        assertEquals(withDetail.items, afterClose.items)
        assertEquals(null, afterClose.detail)
        assertEquals("manual", afterClose.highlightedId)
        assertEquals(1, backend.interactionCalls)
    }

    @Test fun search_normalization_and_result_day_use_the_backend_canonical_day() {
        assertEquals("pizarra", transcriptionSearchQuery("  pizarra "))
        assertEquals(null, transcriptionSearchQuery("   "))
        assertEquals(java.time.LocalDate.parse("2026-09-08"), interactionDayForNavigation(historyItem("utc", "manual", null, null).copy(day = "2026-09-08")))
        assertEquals(java.time.LocalDate.parse("2026-03-29"), interactionDayForNavigation(
            historyItem("dst", "manual", null, null).copy(
                recordedAt = "2026-03-29T22:30:00Z", day = "2026-03-29",
            )
        ))
    }
}

private class HistoryDeleteBackend : BackendRepository {
    private val items = listOf(
        historyItem("chunk-0", "continuous", "session-a", 0),
        historyItem("chunk-1", "continuous", "session-a", 1),
        historyItem("manual", "manual", null, null),
    )
    val deletedInteractions = mutableListOf<String>()
    val deletedSessions = mutableListOf<String>()
    var interactionCalls = 0
    override suspend fun process(audio: CapturedAudio, backendUrl: String): ProcessResponse =
        error("No se usa en este test")
    override suspend fun interactions(backendUrl: String, limit: Int, offset: Int, query: String?) = items
    override suspend fun interaction(backendUrl: String, id: String): InteractionResponse {
        interactionCalls += 1
        return items.first { it.id == id }
    }
    override suspend fun deleteInteraction(id: String, backendUrl: String): DeletionResponse {
        deletedInteractions += id; return DeletionResponse(1, 0, 0)
    }
    override suspend fun deleteContinuousSession(sessionId: String, backendUrl: String): DeletionResponse {
        deletedSessions += sessionId; return DeletionResponse(2, 0, 0)
    }
}

private fun historyItem(id: String, mode: String, sessionId: String?, chunkIndex: Int?) = InteractionResponse(
    id, "2026-09-08T10:00:00Z", "2026-09-08T10:00:00Z",
    Transcription("Synthetic", "es", "base"), Analysis("Synthetic", emptyList(), emptyList(), emptyList(), emptyList()),
    captureMode = mode, captureSessionId = sessionId, chunkIndex = chunkIndex,
)
