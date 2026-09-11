package es.hector.audio_diary

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TaskInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun reorder_gesture_is_attached_to_handle_not_task_row() {
        var starts = 0
        var steps = 0
        var toggles = 0
        var edits = 0
        var deletes = 0
        rule.setContent {
            MaterialTheme {
                TaskRow(
                    item = TaskItem("task", "Tarea"),
                    onToggle = { toggles++ }, onEdit = { edits++ }, onDelete = { deletes++ }, dragging = false,
                    onDragStart = { starts++ }, onDragStep = { steps++ }, onDragEnd = {},
                )
            }
        }

        rule.onNodeWithText("Tarea").performTouchInput { swipe(Offset(8f, height - 4f), Offset(8f, 4f), 700) }
        rule.runOnIdle { assertEquals(0, starts) }
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithContentDescription("Editar tarea").performClick()
        rule.onNodeWithContentDescription("Eliminar tarea").performClick()
        rule.runOnIdle { assertEquals(1, toggles); assertEquals(1, edits); assertEquals(1, deletes) }
        rule.onNodeWithContentDescription("Reordenar tarea").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center + Offset(0f, -300f))
            up()
        }
        rule.runOnIdle { assertEquals(1, starts); assertEquals(1, steps) }
    }
}
