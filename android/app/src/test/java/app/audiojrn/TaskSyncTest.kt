package app.audiojrn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSyncTest {
    private fun local(
        text: String = "Pendiente",
        completed: Boolean = false,
        state: TaskSyncState = TaskSyncState.PENDING_CREATE,
    ) = LocalTaskEntity(
        id = "task", text = text, completed = completed, groupName = "Casa", parentId = null,
        sortOrder = 4, dueAt = "2026-09-12T10:00:00Z", allDay = false, sourceActionId = null,
        createdAt = "2026-09-01T10:00:00Z", updatedAt = "2026-09-01T10:00:00Z", syncState = state,
    )

    @Test fun offline_create_preserves_the_current_completed_state_in_its_first_sync() {
        val sent = local(completed = true)

        val create = createRequestFor(sent)

        assertEquals(sent.text, create.text)
        assertEquals(sent.dueAt, create.dueAt)
        assertEquals(sent.sortOrder, create.sortOrder)
        assertEquals(sent.parentId, create.parentId)
        assertEquals(sent.allDay, create.allDay)
        assertEquals(true, create.completed)
    }

    @Test fun response_for_an_old_update_cannot_ack_a_newer_local_edit() {
        val sent = local(state = TaskSyncState.PENDING_UPDATE)
        val edited = sent.copy(text = "Edición reciente", updatedAt = "2026-09-01T10:01:00Z")

        assertFalse(canAcknowledgeTaskSync(sent, edited, TaskSyncState.PENDING_UPDATE))
    }

    @Test fun response_cannot_resurrect_a_task_deleted_while_request_was_in_flight() {
        val sent = local(state = TaskSyncState.PENDING_UPDATE)
        val deleted = tombstoneForDelete(sent, "2026-09-01T10:01:00Z")

        assertFalse(canAcknowledgeTaskSync(sent, deleted, TaskSyncState.PENDING_UPDATE))
        assertFalse(canAcknowledgeTaskSync(sent, null, TaskSyncState.PENDING_UPDATE))
        assertTrue(deleted.syncState == TaskSyncState.PENDING_DELETE)
    }
}
