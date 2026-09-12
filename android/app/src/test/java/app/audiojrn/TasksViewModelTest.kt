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
class TasksViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun two_consecutive_reorders_start_from_the_new_local_order() = runTest {
        val items = ReorderBackend().items
        val afterFirst = moveTaskWithinPeers(items, "b", -1)
        val afterSecond = moveTaskWithinPeers(afterFirst, "b", 1)
        assertEquals(listOf("a", "b", "other"), afterSecond.map { it.id })
    }

}

private class ReorderBackend : BackendRepository {
    var items = listOf(
        TaskItem("a", "A", groupName = "Grupo", sortOrder = 0),
        TaskItem("b", "B", groupName = "Grupo", sortOrder = 1),
        TaskItem("other", "Other", groupName = "Otro", sortOrder = 0),
    )
    val requests = mutableListOf<List<String>>()

    override suspend fun process(audio: CapturedAudio, backendUrl: String): ProcessResponse = error("No usado")
    override suspend fun tasks(backendUrl: String): List<TaskItem> = items
    override suspend fun reorderTasks(items: List<TaskItem>, backendUrl: String): List<TaskItem> {
        requests += items.sortedWith(compareBy<TaskItem> { it.groupName }.thenBy { it.sortOrder }).map { it.id }
        this.items = items
        return items
    }
}
