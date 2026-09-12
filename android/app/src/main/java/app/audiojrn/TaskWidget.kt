package app.audiojrn

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

const val ACTION_WIDGET_TOGGLE = "app.audiojrn.TOGGLE_TASK"
const val ACTION_OPEN_TASKS = "app.audiojrn.OPEN_TASKS"
const val ACTION_OPEN_NEW_TASK = "app.audiojrn.OPEN_NEW_TASK"
const val EXTRA_WIDGET_TASK = "widget_task"
const val EXTRA_OPEN_NEW_TASK = "open_new_task"
const val EXTRA_OPEN_TASKS = "open_tasks"
const val KEY_WIDGET_HIDE_DONE_PREFIX = "widget_hide_done_"

sealed interface WidgetRow { val stableId: Long
    data class Header(val group: String) : WidgetRow { override val stableId = ("group:$group").hashCode().toLong() }
    data class Task(val item: TaskItem, val child: Boolean) : WidgetRow { override val stableId = item.id.hashCode().toLong() }
}

/** Hiding a completed parent hides its children too, so a child is never orphaned. */
fun widgetRows(tasks: List<TaskItem>, hideCompleted: Boolean): List<WidgetRow> {
    val children = tasks.filter { it.parentId != null }.groupBy { it.parentId }
    val roots = tasks.filter { it.parentId == null }.filter { !hideCompleted || !it.completed }
    return roots.groupBy { it.groupName }.toList()
        .sortedWith(compareBy<Pair<String?, List<TaskItem>>> { it.first == null }.thenBy { it.first.orEmpty() })
        .flatMap { (group, groupRoots) ->
            listOf(WidgetRow.Header(group ?: "Sin grupo")) + groupRoots.flatMap { root ->
                listOf(WidgetRow.Task(root, false)) + children[root.id].orEmpty().filter { !hideCompleted || !it.completed }.map { WidgetRow.Task(it, true) }
            }
        }
}

class AudioJrnApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() { super.onCreate()
        // Room invalidations update widgets; no timer or backend request is involved.
        scope.launch { TaskServices.repository(this@AudioJrnApplication).visible.collectLatest { TaskWidgetProvider.refreshAll(this@AudioJrnApplication) } }
    }
}

class TaskWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { update(context, manager, it) }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            val taskId = intent.getStringExtra(EXTRA_WIDGET_TASK) ?: return
            CoroutineScope(Dispatchers.IO).launch {
                TaskServices.repository(context).let { repo ->
                    val item = repo.visible.firstOrNull()?.firstOrNull { it.id == taskId } ?: return@launch
                    repo.toggle(item); repo.schedule(context.getSharedPreferences("audiojrn", Context.MODE_PRIVATE).getString("backend_url", "").orEmpty())
                }
            }
        }
        super.onReceive(context, intent)
    }
    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(android.content.ComponentName(context, TaskWidgetProvider::class.java)).forEach { update(context, manager, it) }
        }
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.task_widget)
            val adapter = Intent(context, TaskWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply { data = android.net.Uri.parse("taskwidget://$id") }
            views.setRemoteAdapter(R.id.task_widget_list, adapter)
            val toggleTemplate = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, TaskWidgetProvider::class.java).setAction(ACTION_WIDGET_TOGGLE),
                // RemoteViews collection needs a mutable template only to merge
                // the explicit fill-in extra identifying the local task.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.task_widget_list, toggleTemplate)
            fun appIntent() = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openTasks = PendingIntent.getActivity(context, id, appIntent().setAction(ACTION_OPEN_TASKS), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val openNewTask = PendingIntent.getActivity(context, id + 10_000, appIntent().setAction(ACTION_OPEN_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.task_widget_title, openTasks)
            views.setOnClickPendingIntent(R.id.task_widget_add, openNewTask)
            manager.updateAppWidget(id, views); manager.notifyAppWidgetViewDataChanged(id, R.id.task_widget_list)
        }
    }
}

class TaskWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory =
        TaskFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
}
class TaskWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) { super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        setContent {
            var hide by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(getSharedPreferences("audiojrn", Context.MODE_PRIVATE).getBoolean(KEY_WIDGET_HIDE_DONE_PREFIX + id, false)) }
            MaterialTheme { Column(Modifier.padding(24.dp)) {
                Text("Widget de tareas")
                androidx.compose.foundation.layout.Row { Checkbox(hide, { hide = it }); Text("Ocultar tareas completadas") }
                Button(onClick = {
                    getSharedPreferences("audiojrn", Context.MODE_PRIVATE).edit().putBoolean(KEY_WIDGET_HIDE_DONE_PREFIX + id, hide).apply()
                    TaskWidgetProvider.update(this@TaskWidgetConfigActivity, AppWidgetManager.getInstance(this@TaskWidgetConfigActivity), id)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                }) { Text("Guardar") }
            } }
        }
    }
}
private class TaskFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var items = emptyList<WidgetRow>()
    override fun onDataSetChanged() { items = runBlocking(Dispatchers.IO) {
        val hide = context.getSharedPreferences("audiojrn", Context.MODE_PRIVATE).getBoolean(KEY_WIDGET_HIDE_DONE_PREFIX + widgetId, false)
        widgetRows(TaskServices.repository(context).visible.firstOrNull().orEmpty(), hide)
    } }
    override fun getCount() = items.size
    override fun getViewAt(position: Int): RemoteViews? = items.getOrNull(position)?.let { row -> when (row) {
        is WidgetRow.Header -> RemoteViews(context.packageName, R.layout.task_widget_header).apply { setTextViewText(R.id.task_widget_group, row.group) }
        is WidgetRow.Task -> RemoteViews(context.packageName, if (row.child) R.layout.task_widget_child else R.layout.task_widget_row).apply {
            setTextViewText(R.id.task_widget_text, row.item.text)
            setImageViewResource(R.id.task_widget_check, if (row.item.completed) R.drawable.ic_widget_check else R.drawable.ic_widget_circle)
            setContentDescription(R.id.task_widget_check, if (row.item.completed) "Desmarcar tarea" else "Completar tarea")
            setInt(R.id.task_widget_text, "setPaintFlags", if (row.item.completed) Paint.STRIKE_THRU_TEXT_FLAG else 0)
            setFloat(R.id.task_widget_text, "setAlpha", if (row.item.completed) 0.68f else 1f)
            setOnClickFillInIntent(R.id.task_widget_check, Intent().putExtra(EXTRA_WIDGET_TASK, row.item.id))
        }
    } }
    override fun onCreate() = Unit; override fun onDestroy() = Unit; override fun getLoadingView() = null
    override fun getViewTypeCount() = 3
    override fun getItemId(position: Int) = items.getOrNull(position)?.stableId ?: 0L
    override fun hasStableIds() = true
}
