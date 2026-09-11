package es.hector.audio_diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskWidgetRowsTest {
    private fun task(id: String, group: String? = null, parent: String? = null, completed: Boolean = false) =
        TaskItem(id, id, completed = completed, groupName = group, parentId = parent)

    @Test fun rows_keep_group_capitalization_and_place_children_after_parent() {
        val rows = widgetRows(listOf(task("parent", "Trabajo"), task("child", "Trabajo", "parent"), task("home", "Casa")), false)
        assertEquals(listOf("Casa", "Trabajo"), rows.filterIsInstance<WidgetRow.Header>().map { it.group })
        assertEquals(listOf("parent", "child"), rows.filterIsInstance<WidgetRow.Task>().filter { it.item.groupName == "Trabajo" }.map { it.item.id })
        assertTrue((rows.filterIsInstance<WidgetRow.Task>().last { it.item.id == "child" }).child)
    }

    @Test fun hiding_completed_parent_hides_its_family_and_empty_header() {
        val rows = widgetRows(listOf(task("parent", "Uno", completed = true), task("child", "Uno", "parent"), task("visible", "Dos")), true)
        assertEquals(listOf("Dos"), rows.filterIsInstance<WidgetRow.Header>().map { it.group })
        assertEquals(listOf("visible"), rows.filterIsInstance<WidgetRow.Task>().map { it.item.id })
    }

    @Test fun new_sort_order_precedes_existing_peers() {
        assertEquals(2, newTaskSortOrder(listOf(local(sortOrder = 3), local(sortOrder = 7))))
    }

    @Test fun order_signature_ignores_emission_order_but_detects_unconfirmed_move() {
        val moved = listOf(task("a", "Casa"), task("b", "Trabajo"))
        assertEquals(taskOrderSignature(moved), taskOrderSignature(moved.reversed()))
        assertNotEquals(taskOrderSignature(moved), taskOrderSignature(listOf(task("a", "Trabajo"), task("b", "Casa"))))
    }
    private fun local(sortOrder: Int) = LocalTaskEntity("$sortOrder", "x", false, null, null, sortOrder, null, false, null, "now", "now", TaskSyncState.SYNCED)
}
