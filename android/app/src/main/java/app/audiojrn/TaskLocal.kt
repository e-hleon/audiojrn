package app.audiojrn

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The four states are intentionally small: this is one device and one home server. */
enum class TaskSyncState { SYNCED, PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE }

@Entity(tableName = "local_tasks")
data class LocalTaskEntity(
    @PrimaryKey val id: String,
    val text: String,
    val completed: Boolean,
    val groupName: String?,
    val parentId: String?,
    val sortOrder: Int,
    val dueAt: String?,
    val allDay: Boolean,
    val sourceActionId: String?,
    val createdAt: String,
    val updatedAt: String,
    val syncState: TaskSyncState,
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM local_tasks WHERE syncState != 'PENDING_DELETE' ORDER BY groupName IS NULL, groupName, parentId IS NOT NULL, parentId, sortOrder, createdAt, id")
    fun observeVisible(): Flow<List<LocalTaskEntity>>
    @Query("SELECT * FROM local_tasks WHERE syncState != 'PENDING_DELETE' ORDER BY groupName IS NULL, groupName, parentId IS NOT NULL, parentId, sortOrder, createdAt, id")
    suspend fun visibleNow(): List<LocalTaskEntity>
    @Query("SELECT * FROM local_tasks") suspend fun all(): List<LocalTaskEntity>
    @Query("SELECT * FROM local_tasks WHERE id = :id") suspend fun byId(id: String): LocalTaskEntity?
    @Query("SELECT * FROM local_tasks WHERE parentId = :parentId") suspend fun children(parentId: String): List<LocalTaskEntity>
    @androidx.room.Upsert suspend fun upsert(item: LocalTaskEntity)
    @androidx.room.Upsert suspend fun upsertAll(items: List<LocalTaskEntity>)
    @Query("DELETE FROM local_tasks WHERE id = :id") suspend fun remove(id: String)
}

class TaskConverters {
    @TypeConverter fun toState(value: String) = TaskSyncState.valueOf(value)
    @TypeConverter fun fromState(value: TaskSyncState) = value.name
}
@TypeConverters(TaskConverters::class)
@Database(entities = [LocalTaskEntity::class], version = 1, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() { abstract fun taskDao(): TaskDao }

fun LocalTaskEntity.toApi() = TaskItem(id, text, completed, groupName, parentId, sortOrder, dueAt, allDay, sourceActionId, createdAt, updatedAt)
fun TaskItem.toLocal(state: TaskSyncState = TaskSyncState.SYNCED) = LocalTaskEntity(
    id = id, text = text, completed = completed, groupName = groupName, parentId = parentId,
    sortOrder = sortOrder, dueAt = dueAt, allDay = allDay, sourceActionId = sourceActionId,
    createdAt = createdAt ?: Instant.now().toString(), updatedAt = updatedAt ?: Instant.now().toString(), syncState = state,
)

/** A remote response acknowledges only the exact local snapshot that was sent. */
fun canAcknowledgeTaskSync(
    sent: LocalTaskEntity,
    current: LocalTaskEntity?,
    expectedState: TaskSyncState,
): Boolean = current != null && current.syncState == expectedState &&
    current.copy(syncState = sent.syncState) == sent

fun createRequestFor(local: LocalTaskEntity) = TaskItemCreate(
    id = local.id,
    text = local.text,
    completed = local.completed,
    groupName = local.groupName,
    parentId = local.parentId,
    sortOrder = local.sortOrder,
    dueAt = local.dueAt,
    allDay = local.allDay,
)

fun tombstoneForDelete(local: LocalTaskEntity, updatedAt: String) = local.copy(
    syncState = TaskSyncState.PENDING_DELETE,
    updatedAt = updatedAt,
)

fun newTaskSortOrder(peers: List<LocalTaskEntity>): Int =
    (peers.minOfOrNull { it.sortOrder } ?: 0).let { if (it == Int.MIN_VALUE) 0 else it - 1 }

class TaskLocalRepository(private val context: Context, private val database: TaskDatabase, private val backend: BackendRepository) {
    private val dao = database.taskDao()
    val visible: Flow<List<TaskItem>> = dao.observeVisible().map { it.map(LocalTaskEntity::toApi) }
    suspend fun exportVisible(): List<TaskItem> = localTasksForExport(dao.all())
    private fun now() = Instant.now().toString()

    suspend fun create(draft: TaskItemCreate): TaskItem = database.withTransaction {
        val parent = draft.parentId?.let { dao.byId(it) }
        require(parent == null || parent.parentId == null) { "Solo se permite un nivel de subtareas" }
        val group = parent?.groupName ?: draft.groupName
        val sortOrder = newTaskSortOrder(dao.all().filter { it.groupName == group && it.parentId == draft.parentId && it.syncState != TaskSyncState.PENDING_DELETE })
        val item = TaskItem(UUID.randomUUID().toString(), draft.text, groupName = group,
            parentId = draft.parentId, sortOrder = sortOrder, dueAt = draft.dueAt, allDay = draft.allDay,
            createdAt = now(), updatedAt = now())
        dao.upsert(item.toLocal(TaskSyncState.PENDING_CREATE)); item
    }

    suspend fun edit(original: TaskItem, draft: TaskItemCreate) = database.withTransaction {
        val parent = draft.parentId?.let { dao.byId(it) }
        require(parent == null || (parent.parentId == null && parent.id != original.id)) { "Subtarea inválida" }
        require(draft.parentId == null || dao.children(original.id).isEmpty()) {
            "Una tarea con subtareas no puede convertirse en subtarea"
        }
        val changed = original.copy(text = draft.text, groupName = parent?.groupName ?: draft.groupName, parentId = draft.parentId,
            dueAt = draft.dueAt, allDay = draft.allDay, updatedAt = now())
        val state = if ((dao.byId(original.id)?.syncState) == TaskSyncState.PENDING_CREATE) TaskSyncState.PENDING_CREATE else TaskSyncState.PENDING_UPDATE
        dao.upsert(changed.toLocal(state))
        if (changed.parentId == null && changed.groupName != original.groupName) dao.children(changed.id).forEach {
            dao.upsert(it.copy(groupName = changed.groupName, updatedAt = now(), syncState = stateForUpdate(it)))
        }
    }
    private fun stateForUpdate(item: LocalTaskEntity) = if (item.syncState == TaskSyncState.PENDING_CREATE) TaskSyncState.PENDING_CREATE else TaskSyncState.PENDING_UPDATE

    suspend fun toggle(item: TaskItem) = database.withTransaction {
        val local = dao.byId(item.id) ?: return@withTransaction
        dao.upsert(local.copy(completed = !local.completed, updatedAt = now(), syncState = stateForUpdate(local)))
    }
    suspend fun delete(item: TaskItem) = database.withTransaction {
        val local = dao.byId(item.id) ?: return@withTransaction
        (listOf(local) + dao.children(local.id)).forEach { target ->
            // Keep a tombstone even for a local create: the create request may
            // already be in flight and must be reconciled with a DELETE.
            dao.upsert(tombstoneForDelete(target, now()))
        }
    }
    suspend fun reorder(items: List<TaskItem>) = database.withTransaction {
        val byId = items.associateBy { it.id }
        dao.all().forEach { local ->
            val desired = byId[local.id] ?: return@forEach
            val group = desired.groupName
            val changed = local.copy(groupName = group, sortOrder = desired.sortOrder, updatedAt = now(), syncState = stateForUpdate(local))
            dao.upsert(changed)
            if (local.parentId == null && local.groupName != group) dao.children(local.id).forEach { child ->
                dao.upsert(child.copy(groupName = group, updatedAt = now(), syncState = stateForUpdate(child)))
            }
        }
    }
    suspend fun upsertFromAction(item: TaskItem) = database.withTransaction {
        val current = dao.byId(item.id)
        if (current == null || current.syncState == TaskSyncState.SYNCED) dao.upsert(item.toLocal())
    }

    suspend fun sync(url: String): Boolean {
        if (url.isBlank()) return false
        try {
            // Parents precede children, so stable client UUID parent_id is always valid remotely.
            dao.all().filter { it.syncState == TaskSyncState.PENDING_CREATE }.sortedBy { it.parentId != null }.forEach { local ->
                backend.createTask(createRequestFor(local), url)
                acknowledge(local, TaskSyncState.PENDING_CREATE)
            }
            dao.all().filter { it.syncState == TaskSyncState.PENDING_UPDATE }.forEach { local ->
                backend.updateTaskFromEditor(local.id, TaskItemEditorUpdate(local.text, local.groupName, local.parentId, local.dueAt, local.allDay), url)
                backend.updateTask(local.id, TaskItemUpdate(completed = local.completed, sortOrder = local.sortOrder), url)
                acknowledge(local, TaskSyncState.PENDING_UPDATE)
            }
            dao.all().filter { it.syncState == TaskSyncState.PENDING_DELETE }.sortedBy { it.parentId == null }.forEach { local ->
                try { backend.deleteTask(local.id, url) }
                catch (error: retrofit2.HttpException) { if (error.code() != 404) throw error }
                // DELETE is idempotent for reconciliation: a missing remote task is already deleted.
                dao.remove(local.id)
            }
            val remote = backend.tasks(url)
            database.withTransaction {
                val local = dao.all().associateBy { it.id }
                remote.forEach { item -> if (local[item.id]?.syncState in setOf(null, TaskSyncState.SYNCED)) dao.upsert(item.toLocal()) }
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
    }

    private suspend fun acknowledge(sent: LocalTaskEntity, expectedState: TaskSyncState) = database.withTransaction {
        val current = dao.byId(sent.id)
        if (current != null && canAcknowledgeTaskSync(sent, current, expectedState)) {
            dao.upsert(current.copy(syncState = TaskSyncState.SYNCED))
        }
    }
    fun schedule(url: String) {
        context.getSharedPreferences("audiojrn", Context.MODE_PRIVATE).edit().putString("backend_url", url).apply()
        if (backendUrlIfAvailable(url) == null) return
        val request = OneTimeWorkRequestBuilder<TaskSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("task-sync", ExistingWorkPolicy.KEEP, request)
    }
}

fun localTasksForExport(items: List<LocalTaskEntity>): List<TaskItem> = items
    .filter { it.syncState != TaskSyncState.PENDING_DELETE }
    .sortedWith(compareBy<LocalTaskEntity> { it.groupName == null }
        .thenBy { it.groupName }
        .thenBy { it.parentId != null }
        .thenBy { it.parentId }
        .thenBy { it.sortOrder }
        .thenBy { it.createdAt }
        .thenBy { it.id })
    .map(LocalTaskEntity::toApi)

class TaskSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = applicationContext.getSharedPreferences("audiojrn", Context.MODE_PRIVATE).getString("backend_url", "").orEmpty()
        if (backendUrlIfAvailable(url) == null) return Result.success()
        return if (TaskServices.repository(applicationContext).sync(url)) Result.success() else Result.retry()
    }
}

object TaskServices {
    @Volatile private var instance: TaskLocalRepository? = null
    fun repository(context: Context): TaskLocalRepository = instance ?: synchronized(this) {
        instance ?: TaskLocalRepository(context.applicationContext, Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, "tasks.db").build(), RetrofitBackendRepository()).also { instance = it }
    }
}
