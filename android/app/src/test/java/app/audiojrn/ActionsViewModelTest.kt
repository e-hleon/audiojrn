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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun dated_task_destination_defaults_to_both_and_has_no_ask_mode() {
        assertEquals(DatedTaskDestination.BOTH, DatedTaskDestination.fromStored(null))
        assertEquals(DatedTaskDestination.BOTH, DatedTaskDestination.fromStored("ask"))
        assertEquals(listOf("both", "tasks", "calendar"), DatedTaskDestination.values().map { it.storedValue })
    }

    @Test fun acceptance_plan_routes_events_undated_tasks_and_dated_preferences() {
        val event = proposedAction(kind = "event", startAt = "2026-09-10T10:00:00+02:00")
        val undatedTask = proposedAction(kind = "task")
        val datedTask = proposedAction(kind = "task", dueText = "2026-09-10T10:00:00+02:00")

        assertEquals(AcceptancePlan(false, true), acceptancePlan(event, DatedTaskDestination.TASKS))
        assertEquals(AcceptancePlan(true, false), acceptancePlan(undatedTask, DatedTaskDestination.CALENDAR))
        assertEquals(AcceptancePlan(true, true), acceptancePlan(datedTask, DatedTaskDestination.BOTH))
        assertEquals(AcceptancePlan(true, false), acceptancePlan(datedTask, DatedTaskDestination.TASKS))
        assertEquals(AcceptancePlan(false, true), acceptancePlan(datedTask, DatedTaskDestination.CALENDAR))
    }

    @Test fun paginates_without_duplicates_and_resets_when_showing_resolved() = runTest {
        val backend = PagedActionsBackend()
        val viewModel = ActionsViewModel(backend)

        viewModel.load("http://example.test")
        advanceUntilIdle()
        val first = viewModel.state.value as ActionsState.Ready
        assertEquals(50, first.items.size)
        assertTrue(first.canLoadMore)

        viewModel.loadMore("http://example.test")
        advanceUntilIdle()
        val second = viewModel.state.value as ActionsState.Ready
        assertEquals(51, second.items.size)
        assertEquals(51, second.items.map { it.id }.toSet().size)
        assertFalse(second.canLoadMore)

        viewModel.load("http://example.test", includeResolved = true)
        advanceUntilIdle()
        val resolved = viewModel.state.value as ActionsState.Ready
        assertTrue(resolved.includeResolved)
        assertEquals(1, resolved.items.size)
        assertEquals(listOf(Triple(0, false, 50), Triple(50, false, 50), Triple(0, true, 50)), backend.requests)
    }

    @Test fun delete_all_uses_one_bulk_request_then_refreshes_current_filter() = runTest {
        val backend = PagedActionsBackend()
        val viewModel = ActionsViewModel(backend)
        viewModel.load("http://example.test")
        advanceUntilIdle()

        viewModel.deleteAllPending("http://example.test")
        advanceUntilIdle()

        assertEquals(1, backend.deleteCalls)
        val state = viewModel.state.value as ActionsState.Ready
        assertTrue(state.items.isEmpty())
        assertEquals(listOf(Triple(0, false, 50), Triple(0, false, 50)), backend.requests)
    }

    @Test fun calendar_export_removes_pending_action_only_after_backend_update_succeeds() = runTest {
        val backend = PagedActionsBackend()
        val viewModel = ActionsViewModel(backend)
        viewModel.load("http://example.test")
        advanceUntilIdle()
        val action = (viewModel.state.value as ActionsState.Ready).items.first()
        var exported = false
        viewModel.markCalendarExported("http://example.test", action, { exported = true }, { error("unexpected") })
        advanceUntilIdle()
        assertTrue(exported)
        assertFalse((viewModel.state.value as ActionsState.Ready).items.any { it.id == action.id })
    }

    @Test fun retrying_partial_both_flow_reuses_the_same_idempotent_action_id() = runTest {
        val backend = PagedActionsBackend()
        val viewModel = ActionsViewModel(backend)
        viewModel.load("http://example.test")
        advanceUntilIdle()
        val action = (viewModel.state.value as ActionsState.Ready).items.first()

        viewModel.addToTasks("http://example.test", action, markResolved = false)
        advanceUntilIdle()
        viewModel.addToTasks("http://example.test", action, markResolved = false)
        advanceUntilIdle()

        assertEquals(listOf(action.id, action.id), backend.addToTasksRequests)
        assertTrue((viewModel.state.value as ActionsState.Ready).items.any { it.id == action.id })
    }

    private fun proposedAction(
        kind: String,
        dueText: String? = null,
        startAt: String? = null,
    ) = ProposedAction(
        id = "action",
        kind = kind,
        status = "pending",
        title = "Propuesta",
        dueText = dueText,
        startAt = startAt,
        evidence = "Propuesta",
        createdAt = "2026-09-09T10:00:00Z",
    )
}

private class PagedActionsBackend : BackendRepository {
    val requests = mutableListOf<Triple<Int, Boolean, Int>>()
    var deleteCalls = 0
    val addToTasksRequests = mutableListOf<String>()
    private var pending = (0 until 51).map { action("pending-$it") }.toMutableList()

    override suspend fun process(audio: CapturedAudio, backendUrl: String): ProcessResponse =
        error("No se usa en este test")

    override suspend fun actions(backendUrl: String, limit: Int, offset: Int, includeResolved: Boolean): List<ProposedAction> {
        requests += Triple(offset, includeResolved, limit)
        if (includeResolved) return listOf(action("exported", "exported"))
        return pending.drop(offset).take(limit)
    }

    override suspend fun deletePendingActions(backendUrl: String): DeletedActionsResponse {
        deleteCalls += 1
        val count = pending.size
        pending.clear()
        return DeletedActionsResponse(count)
    }

    override suspend fun deleteAction(actionId: String, backendUrl: String): DeletedActionsResponse {
        pending.removeAll { it.id == actionId }
        return DeletedActionsResponse(1)
    }

    override suspend fun updateAction(actionId: String, update: ActionUpdate, backendUrl: String): ProposedAction {
        return action(actionId, update.status ?: "pending")
    }

    override suspend fun addActionToTasks(actionId: String, backendUrl: String): TaskItem {
        addToTasksRequests += actionId
        return TaskItem(id = "task-for-$actionId", text = actionId, sourceActionId = actionId)
    }

    private fun action(id: String, status: String = "pending") = ProposedAction(
        id = id, kind = "task", status = status, title = id, evidence = id, createdAt = "2026-09-08T10:00:00Z",
    )
}
