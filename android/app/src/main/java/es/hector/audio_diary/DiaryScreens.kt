package es.hector.audio_diary

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.ClipData
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.FormatIndentDecrease
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

enum class AppScreen { CAPTURE, DAY, ACTIONS, TASKS, SETTINGS }
enum class CaptureMode { MANUAL, CONTINUOUS }
enum class CaptureControlAction { DISCARD, PROCESS_NOW, PAUSE_RESUME, FINISH }
data class CaptureControlLayout(
    val left: CaptureControlAction,
    val center: CaptureControlAction = CaptureControlAction.PAUSE_RESUME,
    val right: CaptureControlAction = CaptureControlAction.FINISH,
)
fun captureControlLayout(mode: CaptureMode) = CaptureControlLayout(
    left = if (mode == CaptureMode.CONTINUOUS) CaptureControlAction.PROCESS_NOW else CaptureControlAction.DISCARD,
)
enum class AppTheme(val storedValue: String) { SYSTEM("system"), LIGHT("light"), DARK("dark");
    companion object { fun fromStored(value: String?) = values().firstOrNull { it.storedValue == value } ?: SYSTEM }
}

private const val KEY_DIARY_NEWEST_FIRST = "diary_newest_first"
private const val KEY_APP_THEME = "app_theme"
private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
const val KEY_CAPTURE_MODE = "capture_mode"

fun persistedCaptureMode(stored: String?, continuousRunning: Boolean): CaptureMode =
    if (continuousRunning) CaptureMode.CONTINUOUS
    else CaptureMode.values().firstOrNull { it.name == stored } ?: CaptureMode.MANUAL

fun formatContinuousElapsed(seconds: Long): String = when {
    seconds < 60 -> "%02d s".format(seconds)
    seconds < 3600 -> "%02d:%02d".format(seconds / 60, seconds % 60)
    else -> "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}
fun formatCaptureElapsed(seconds: Long) = formatContinuousElapsed(seconds)
fun continuousElapsedSeconds(startedAtElapsedRealtime: Long, nowElapsedRealtime: Long): Long =
    ((nowElapsedRealtime - startedAtElapsedRealtime).coerceAtLeast(0L)) / 1000

fun toggleSelectAll(selected: Set<String>, visible: Set<String>): Set<String> =
    if (visible.isNotEmpty() && selected.containsAll(visible)) emptySet() else visible

fun protectedSelectedSessions(
    interactions: List<InteractionResponse>,
    selectedKeys: Set<String>,
    protectedSessionIds: Set<String>,
): Set<String> = interactions.asSequence()
    .filter { "interaction:${it.id}" in selectedKeys }
    .mapNotNull { it.captureSessionId }
    .filter { it in protectedSessionIds }
    .toSet()

enum class ChronologyBackAction { CLOSE_SEARCH, LEAVE_CHRONOLOGY }
fun chronologyBackAction(searchOpen: Boolean): ChronologyBackAction =
    if (searchOpen) ChronologyBackAction.CLOSE_SEARCH else ChronologyBackAction.LEAVE_CHRONOLOGY

private fun editorDate(value: String?): LocalDate? = value?.let { rawValue ->
    runCatching { LocalDate.parse(rawValue) }.getOrElse {
        runCatching { OffsetDateTime.parse(rawValue).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
    }
}

private fun editorTime(value: String?): String = value?.let {
    runCatching { OffsetDateTime.parse(it).toLocalTime().withSecond(0).withNano(0).toString() }.getOrDefault("")
}.orEmpty()

private fun displayActionTemporal(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM uuuu"))
}.getOrElse {
    runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM uuuu · HH:mm")) }
        .getOrDefault(value)
}

private fun displayClock(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault(value)

fun formatTaskDue(
    value: String,
    allDay: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = runCatching {
    val temporal = runCatching { LocalDate.parse(value).atStartOfDay(zoneId) }
        .getOrElse { OffsetDateTime.parse(value).atZoneSameInstant(zoneId) }
    temporal.format(DateTimeFormatter.ofPattern(if (allDay) "d MMM uuuu" else "d MMM uuuu · HH:mm", locale))
}.getOrDefault(value)

private fun editorDateTime(date: LocalDate?, time: String, allDay: Boolean): String? {
    date ?: return null
    if (allDay) return date.toString()
    val localTime = runCatching { LocalTime.parse(time) }.getOrNull() ?: return null
    return date.atTime(localTime).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
}

fun taskDueDateTime(date: LocalDate?, time: String, allDay: Boolean): String? {
    date ?: return null
    return if (allDay) {
        date.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime().toString()
    } else editorDateTime(date, time, allDay = false)
}

private fun visibleActionKind(kind: String): String = if (kind == "event") "Evento" else "Tarea"
private fun visibleActionStatus(status: String): String = when (status) {
    "exported" -> "Gestionada"
    "dismissed" -> "Descartada"
    else -> "Pendiente"
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable fun DiaryApp(capture: CaptureViewModel, tasks: TasksViewModel, backend: BackendRepository, preferences: SharedPreferences, navigation: kotlinx.coroutines.flow.Flow<WidgetNavigationCommand>) {
    var screen by remember { mutableStateOf(AppScreen.CAPTURE) }
    var dayChronologyOpen by remember { mutableStateOf(false) }
    var openNewTaskEvent by remember { mutableStateOf(false) }
    LaunchedEffect(navigation) {
        navigation.collect { command ->
            when (command) {
                WidgetNavigationCommand.OPEN_TASKS -> { dayChronologyOpen = false; screen = AppScreen.TASKS }
                WidgetNavigationCommand.OPEN_NEW_TASK -> { dayChronologyOpen = false; screen = AppScreen.TASKS; openNewTaskEvent = true }
                WidgetNavigationCommand.OPEN_CAPTURE -> { dayChronologyOpen = false; screen = AppScreen.CAPTURE }
            }
        }
    }
    var url by remember { mutableStateOf(preferences.getString("backend_url", "") ?: "") }
    LaunchedEffect(url) { if (url.isNotBlank()) tasks.load(url) }
    var appTheme by remember { mutableStateOf(AppTheme.fromStored(preferences.getString(KEY_APP_THEME, null))) }
    val darkTheme = when (appTheme) {
        AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val colors = if (darkTheme) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
    var topActions by remember { mutableStateOf<@Composable RowScope.() -> Unit>({}) }
    var topTitleOverride by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var topNavigationOverride by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val captureState by CaptureForegroundService.state.collectAsStateWithLifecycle()
    val captureMode by CaptureForegroundService.mode.collectAsStateWithLifecycle()
    val captureElapsed by CaptureForegroundService.elapsedSeconds.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        CaptureForegroundService.events.collect { snackbar.showSnackbar(it) }
    }
    MaterialTheme(colorScheme = colors) {
    Scaffold(
        topBar = { TopAppBar(title = { topTitleOverride?.invoke() ?: Text(when (screen) {
            AppScreen.CAPTURE -> "Captura"; AppScreen.DAY -> if (dayChronologyOpen) "Cronología" else "Diario"; AppScreen.ACTIONS -> "Acciones"
            AppScreen.TASKS -> "Tareas"; AppScreen.SETTINGS -> "Configuración"
        }) }, navigationIcon = {
            if (topNavigationOverride != null) topNavigationOverride?.invoke()
            else if (screen == AppScreen.DAY && dayChronologyOpen) IconButton({ dayChronologyOpen = false }) {
                Icon(Icons.Default.ArrowBack, "Volver a Diario")
            }
        }, actions = topActions) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
            if (captureState.isActiveSession()) Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().clickable { screen = AppScreen.CAPTURE; dayChronologyOpen = false },
            ) { Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (captureState.isPaused()) Icons.Default.Pause else Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(globalRecordingLabel(captureState, captureMode).orEmpty(), Modifier.weight(1f))
                Text(formatCaptureElapsed(captureElapsed), style = MaterialTheme.typography.labelLarge)
            } }
            NavigationBar {
                AppScreen.values().forEach { item ->
                    val label = when (item) {
                        AppScreen.CAPTURE -> "Captura"; AppScreen.DAY -> "Diario"; AppScreen.ACTIONS -> "Acciones"
                        AppScreen.TASKS -> "Tareas"; AppScreen.SETTINGS -> "Configuración"
                    }
                    val icon = when (item) {
                        AppScreen.CAPTURE -> Icons.Default.Mic; AppScreen.DAY -> Icons.Default.Today
                        AppScreen.ACTIONS -> Icons.Default.AutoAwesome; AppScreen.TASKS -> Icons.Default.Checklist
                        AppScreen.SETTINGS -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = screen == item, onClick = { if (BuildConfig.DEBUG && item == AppScreen.TASKS) Log.d("TasksTiming", "navigation_tasks_tap ${SystemClock.elapsedRealtime()}"); if (item != AppScreen.DAY) dayChronologyOpen = false; screen = item },
                        icon = { Icon(icon, contentDescription = label) },
                    )
                }
            }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (screen) {
                AppScreen.CAPTURE -> CaptureHome(url, preferences) { topActions = it }
                AppScreen.DAY -> DayScreen(url, backend, preferences, snackbar, dayChronologyOpen, { dayChronologyOpen = it }, { topTitleOverride = it }, { topNavigationOverride = it }) { topActions = it }
                AppScreen.ACTIONS -> ActionsScreen(url, backend, preferences, snackbar) { topActions = it }
                AppScreen.TASKS -> TasksScreen(url, tasks, openNewTaskEvent, { openNewTaskEvent = false }) { topActions = it }
                AppScreen.SETTINGS -> CalendarSettingsScreen(preferences, url, appTheme, backend, snackbar, { selected ->
                    appTheme = selected
                    preferences.edit().putString(KEY_APP_THEME, selected.storedValue).apply()
                }, { topActions = {} }) {
                    url = it
                    preferences.edit().putString("backend_url", it).apply()
                }
            }
        }
    } }
}

@Composable private fun CaptureHome(
    url: String,
    preferences: SharedPreferences,
    setTopActions: (@Composable RowScope.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionState by CaptureForegroundService.state.collectAsStateWithLifecycle()
    val serviceMode by CaptureForegroundService.mode.collectAsStateWithLifecycle()
    val elapsed by CaptureForegroundService.elapsedSeconds.collectAsStateWithLifecycle()
    val waveform by CaptureForegroundService.waveform.collectAsStateWithLifecycle()
    val canProcess by CaptureForegroundService.canProcess.collectAsStateWithLifecycle()
    val captureError by CaptureForegroundService.error.collectAsStateWithLifecycle()
    var selectedMode by remember { mutableStateOf(persistedCaptureMode(preferences.getString(KEY_CAPTURE_MODE, null), false)) }
    val displayedMode = serviceMode ?: selectedMode
    var confirmDiscard by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<CaptureMode?>(null) }
    var microphoneGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var notificationGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    var notificationDecisionRequested by remember { mutableStateOf(preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) }
    fun consumeAndStartPending() {
        val consumed = consumePendingStart(pendingStart, permissionGranted = true)
        pendingStart = consumed.remaining
        if (consumed.modeToStart != null) runCatching { startCaptureService(context, consumed.modeToStart, url) }
            .onFailure { CaptureForegroundService.showMessage("No se pudo iniciar el micrófono") }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
        consumeAndStartPending()
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        if (!granted) {
            pendingStart = null
        } else when (nextCapturePermissionStep(pendingStart, true, Build.VERSION.SDK_INT >= 33, notificationGranted, notificationDecisionRequested)) {
            CapturePermissionStep.REQUEST_NOTIFICATIONS -> {
                notificationDecisionRequested = true
                preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
                runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    .onFailure { consumeAndStartPending() }
            }
            CapturePermissionStep.START -> consumeAndStartPending()
            else -> Unit
        }
    }
    fun start(mode: CaptureMode) {
        if (pendingStart != null) return
        pendingStart = mode
        when (nextCapturePermissionStep(mode, microphoneGranted, Build.VERSION.SDK_INT >= 33, notificationGranted, notificationDecisionRequested)) {
            CapturePermissionStep.REQUEST_MICROPHONE -> runCatching { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }
                .onFailure { pendingStart = null; CaptureForegroundService.showMessage("No se pudo solicitar acceso al micrófono") }
            CapturePermissionStep.REQUEST_NOTIFICATIONS -> {
                notificationDecisionRequested = true
                preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
                runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    .onFailure { consumeAndStartPending() }
            }
            CapturePermissionStep.START -> consumeAndStartPending()
            CapturePermissionStep.NONE -> Unit
        }
    }
    fun command(action: String) { context.startService(Intent(context, CaptureForegroundService::class.java).setAction(action).putExtra(CaptureForegroundService.EXTRA_BACKEND, url)) }
    DisposableEffect(url, sessionState, retrying) {
        setTopActions {
            IconButton(enabled = sessionState == CaptureSessionState.IDLE && !retrying, onClick = {
                retrying = true
                scope.launch {
                    val message = withContext(Dispatchers.IO) {
                        runCatching {
                            val manualPending = recoverManualCaptures(File(context.filesDir, "pending-manual"))
                            val pendingQueue = SegmentQueue(pendingSegmentDirectory(context.filesDir, context.cacheDir))
                            val pendingFinalizations = PendingFinalizations(File(context.filesDir, "pending-finalizations"))
                            val pendingBefore = manualPending.size + pendingQueue.size + pendingFinalizations.pending().size
                            val normalized = backendUrlIfAvailable(url)
                            if (pendingBefore == 0 || normalized == null) {
                                pendingCaptureRetryMessage(pendingBefore, normalized != null, pendingBefore)
                            } else {
                                val repository = RetrofitBackendRepository()
                                retryPendingManualCaptures(manualPending) { repository.process(it, normalized) }
                                SegmentUploadCoordinator(pendingQueue) { item -> repository.process(CapturedAudio(item.file, item.recordedAt, item.captureMode, item.captureSessionId, item.chunkIndex, item.captureChunkId), normalized) }.drain()
                                pendingFinalizations.retry(repository, normalized)
                                val pendingAfter = manualPending.count { it.file.exists() } + pendingQueue.size + pendingFinalizations.pending().size
                                pendingCaptureRetryMessage(pendingBefore, backendAvailable = true, pendingAfter)
                            }
                        }.getOrDefault("Quedan capturas pendientes para reintentar")
                    }
                    retrying = false
                    CaptureForegroundService.showMessage(message)
                }
            }) { Icon(Icons.Default.Refresh, "Reintentar capturas pendientes") }
        }
        onDispose { setTopActions({}) }
    }
    if (confirmDiscard && displayedMode == CaptureMode.MANUAL) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Descartar grabación") },
        text = { Text("Se eliminará el audio de esta grabación.") },
        confirmButton = { TextButton({ confirmDiscard = false; command(CaptureForegroundService.ACTION_DISCARD) }) { Text("Descartar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Cancelar") } },
    )
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        captureError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(14.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureMode.values().forEach { item ->
                val label = if (item == CaptureMode.CONTINUOUS) "CONTINUO" else "MANUAL"
                val choose = { selectedMode = item; preferences.edit().putString(KEY_CAPTURE_MODE, item.name).apply() }
                if (displayedMode == item) Button(choose, Modifier.weight(1f), enabled = !sessionState.isActiveSession()) { Text(label) }
                else OutlinedButton(choose, Modifier.weight(1f), enabled = !sessionState.isActiveSession()) { Text(label) }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (sessionState == CaptureSessionState.IDLE) {
            Column(
                Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    if (displayedMode == CaptureMode.MANUAL) Text("Máximo: 30 min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(104.dp).clickable(enabled = !retrying) { start(displayedMode) }) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, "Iniciar grabación ${if (displayedMode == CaptureMode.CONTINUOUS) "Continuo" else "Manual"}", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp)) }
                }
                Spacer(Modifier.height(18.dp))
            }
        } else if (sessionState == CaptureSessionState.FINALIZING) {
            LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Finalizando…")
        } else {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Waveform(waveform, paused = sessionState.isPaused())
                }
            }
            CaptureTimer(elapsed)
            Spacer(Modifier.height(12.dp))
            val controls = captureControlLayout(displayedMode)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (controls.left == CaptureControlAction.PROCESS_NOW) {
                        IconButton(
                            enabled = sessionState == CaptureSessionState.RECORDING_CONTINUOUS && canProcess,
                            onClick = { command(CaptureForegroundService.ACTION_PROCESS_NOW) },
                            modifier = Modifier.size(64.dp),
                        ) { Icon(Icons.Default.Timer, "Procesar hasta ahora", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) }
                    } else {
                        IconButton({ confirmDiscard = true }, Modifier.size(64.dp)) { Icon(Icons.Default.Close, "Descartar", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp)) }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(76.dp).clickable { command(if (sessionState.isPaused()) CaptureForegroundService.ACTION_RESUME else CaptureForegroundService.ACTION_PAUSE) }) {
                        Box(contentAlignment = Alignment.Center) { Icon(if (sessionState.isPaused()) Icons.Default.PlayArrow else Icons.Default.Pause, if (sessionState.isPaused()) "Reanudar" else "Pausar", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp)) }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton({ command(CaptureForegroundService.ACTION_FINISH) }, Modifier.size(64.dp)) { Icon(Icons.Default.Check, "Finalizar", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun startCaptureService(context: Context, mode: CaptureMode, url: String) {
    val action = if (mode == CaptureMode.MANUAL) CaptureForegroundService.ACTION_START_MANUAL else CaptureForegroundService.ACTION_START_CONTINUOUS
    ContextCompat.startForegroundService(context, Intent(context, CaptureForegroundService::class.java).setAction(action).putExtra(CaptureForegroundService.EXTRA_BACKEND, url))
}

@Composable private fun CaptureTimer(seconds: Long) { Text(formatCaptureElapsed(seconds), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }

@Composable private fun Waveform(values: List<Float>, paused: Boolean) {
    val color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 8.dp)) {
        if (values.isEmpty()) drawLine(color, center.copy(x = 0f), center.copy(x = size.width), strokeWidth = 2.dp.toPx())
        else {
            val step = size.width / values.size
            values.forEachIndexed { index, amplitude ->
                val half = (size.height * .44f * amplitude.coerceAtLeast(.025f))
                val x = step * (index + .5f)
                drawLine(color, androidx.compose.ui.geometry.Offset(x, center.y - half), androidx.compose.ui.geometry.Offset(x, center.y + half), strokeWidth = maxOf(2f, step * .45f), cap = StrokeCap.Round)
            }
        }
    }
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable private fun ColumnScope.DayScreen(
    url: String,
    backend: BackendRepository,
    preferences: SharedPreferences,
    snackbar: SnackbarHostState,
    chronologyOpen: Boolean,
    onChronologyOpenChange: (Boolean) -> Unit,
    setTopTitle: ((@Composable () -> Unit)?) -> Unit,
    setTopNavigation: ((@Composable () -> Unit)?) -> Unit,
    setTopActions: (@Composable RowScope.() -> Unit) -> Unit,
) {
    val vm = remember { DayViewModel(backend) }
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var calendarOpen by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(preferences.getBoolean(KEY_DIARY_NEWEST_FIRST, true)) }
    var activity by remember { mutableStateOf(emptySet<LocalDate>()) }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<InteractionResponse>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var highlightedInteractionId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }; var noteDraft by remember { mutableStateOf(TextFieldValue()) }; var editingNote by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingSummary by remember { mutableStateOf(false) }; var summaryText by remember { mutableStateOf("") }
    var editingHighlights by remember { mutableStateOf(false) }; var highlightsText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notes = remember { DiaryNoteRepository(context.filesDir) }
    val dayListState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val currentNoteDraft = rememberUpdatedState(if (editingNote) noteDraft.text else note)
    DisposableEffect(selectedDate) { val day = selectedDate; onDispose { notes.save(day, currentNoteDraft.value) } }
    fun closeSearch() { searchOpen = false; searchQuery = ""; searchResults = emptyList(); searchLoading = false }
    DisposableEffect(chronologyOpen, selectedKeys, searchOpen, selectedDate, url) {
        setTopTitle(if (chronologyOpen && searchOpen && selectedKeys.isEmpty()) {{
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar transcripciones…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                trailingIcon = { IconButton(::closeSearch) { Icon(Icons.Default.Close, "Cerrar búsqueda") } },
            )
            LaunchedEffect(Unit) {
                searchFocusRequester.requestFocus()
                keyboard?.show()
            }
        }} else null)
        setTopNavigation(if (chronologyOpen && searchOpen) {{
            IconButton(::closeSearch) { Icon(Icons.Default.ArrowBack, "Cerrar búsqueda") }
        }} else null)
        setTopActions {
            if (chronologyOpen && selectedKeys.isEmpty() && !searchOpen) IconButton({ searchOpen = true }) { Icon(Icons.Default.Search, "Buscar transcripciones") }
            if (!searchOpen) IconButton({ vm.load(url, selectedDate) }) { Icon(Icons.Default.Refresh, "Recargar día") }
        }
        onDispose { setTopTitle(null); setTopNavigation(null); setTopActions({}) }
    }
    fun leaveChronology() { selectedKeys = emptySet(); onChronologyOpenChange(false) }
    BackHandler(chronologyOpen) {
        when (chronologyBackAction(searchOpen)) {
            ChronologyBackAction.CLOSE_SEARCH -> closeSearch()
            ChronologyBackAction.LEAVE_CHRONOLOGY -> leaveChronology()
        }
    }
    LaunchedEffect(chronologyOpen) { if (!chronologyOpen) selectedKeys = emptySet() }
    if (calendarOpen) ActivityCalendar(selectedDate, activity, { selectedDate = it; calendarOpen = false }, { calendarOpen = false }, vm)
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Eliminar ${selectedKeys.size} elementos") },
        text = { Text("Se eliminarán las transcripciones seleccionadas y sus acciones derivadas. Esta acción no se puede deshacer.") },
        confirmButton = { TextButton({
            confirmDelete = false
            val interactions = (state as? DayState.Ready)?.value?.interactions.orEmpty()
            val protected = buildSet {
                CaptureForegroundService.currentSessionId.value?.let { add(it) }
                addAll(PendingFinalizations(File(context.filesDir, "pending-finalizations")).pendingSessionIds())
            }
            if (protectedSelectedSessions(interactions, selectedKeys, protected).isNotEmpty()) {
                scope.launch { snackbar.showSnackbar("Procesa primero el bloque actual antes de eliminar este fragmento") }
            } else vm.deleteSelected(selectedKeys, { selectedKeys = emptySet() }) { message ->
                scope.launch { snackbar.showSnackbar(message) }
            }
        }) { Text("Eliminar") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } },
    )
    LaunchedEffect(selectedDate, url) {
        selectedKeys = emptySet()
        note = withContext(Dispatchers.IO) { notes.load(selectedDate) }
        if (url.isNotBlank()) {
            vm.load(url, selectedDate)
            vm.activity(selectedDate.withDayOfMonth(1), selectedDate.withDayOfMonth(1).plusMonths(1).minusDays(1)) { activity = it }
        }
    }
    LaunchedEffect(searchQuery, searchOpen, url) {
        val query = transcriptionSearchQuery(searchQuery)
        if (!searchOpen || query == null || url.isBlank()) { searchResults = emptyList(); searchLoading = false; return@LaunchedEffect }
        delay(300)
        searchLoading = true
        searchResults = runCatching { backend.interactions(url, limit = 50, offset = 0, query = query) }.getOrDefault(emptyList())
        searchLoading = false
    }
    LaunchedEffect(highlightedInteractionId) { if (highlightedInteractionId != null) { delay(2500); highlightedInteractionId = null } }
    LaunchedEffect(state, chronologyOpen, highlightedInteractionId) {
        val id = highlightedInteractionId ?: return@LaunchedEffect
        val ready = state as? DayState.Ready ?: return@LaunchedEffect
        if (chronologyOpen) flatChronology(ready.value.interactions, newestFirst).indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
            dayListState.animateScrollToItem(it + 1)
        }
    }
    if (selectedKeys.isNotEmpty()) {
        Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ selectedKeys = emptySet() }) { Icon(Icons.Default.Close, "Salir de selección") }
                Text(if (selectedKeys.size == 1) "1 seleccionada" else "${selectedKeys.size} seleccionadas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val allKeys = (state as? DayState.Ready)?.value?.interactions?.let { flatChronology(it, newestFirst).map { item -> "interaction:${item.id}" }.toSet() }.orEmpty()
                val allSelected = allKeys.isNotEmpty() && selectedKeys.containsAll(allKeys)
                IconButton({ selectedKeys = toggleSelectAll(selectedKeys, allKeys) }) { Icon(Icons.Default.SelectAll, if (allSelected) "Deseleccionar todo" else "Seleccionar todo") }
                IconButton({
                    val text = (state as? DayState.Ready)?.value?.interactions?.let { flatChronology(it, newestFirst) }.orEmpty()
                        .filter { "interaction:${it.id}" in selectedKeys }
                        .joinToString("\n") { it.transcription.text }
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Transcripciones", text))
                    scope.launch { snackbar.showSnackbar("Texto copiado") }
                }) { Icon(Icons.Default.ContentCopy, "Copiar texto") }
                IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Eliminar selección", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (searchOpen) LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (searchLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (searchQuery.isNotBlank() && !searchLoading && searchResults.isEmpty()) item {
            Text("Sin resultados", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        }
        items(searchResults, key = { "search:${it.id}" }) { result ->
            val local = runCatching { OffsetDateTime.parse(result.recordedAt).atZoneSameInstant(ZoneId.of("Europe/Madrid")) }.getOrNull()
            Surface(Modifier.fillMaxWidth().clickable {
                interactionLocalDate(result.recordedAt)?.let { selectedDate = it }
                highlightedInteractionId = result.id
                closeSearch()
                onChronologyOpenChange(true)
            }) { Column(Modifier.padding(vertical = 9.dp)) {
                Text(local?.format(DateTimeFormatter.ofPattern("d MMM uuuu · HH:mm:ss")) ?: result.recordedAt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(result.transcription.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } }
            HorizontalDivider()
        }
    } else LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        state = dayListState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton({ selectedDate = selectedDate.minusDays(1) }) { Icon(Icons.Default.ArrowBack, "Día anterior") }
            TextButton({ calendarOpen = true }, Modifier.weight(1f)) {
                Text(
                    selectedDate.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton({ selectedDate = selectedDate.plusDays(1) }) { Icon(Icons.Default.ArrowForward, "Día siguiente") }
            if (chronologyOpen) IconButton({
                newestFirst = !newestFirst
                preferences.edit().putBoolean(KEY_DIARY_NEWEST_FIRST, newestFirst).apply()
            }) { Icon(if (newestFirst) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, if (newestFirst) "Más recientes primero" else "Más antiguos primero") }
        }
        }
        when (val current = state) {
            DayState.Idle -> item { Text("Sin datos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            DayState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            is DayState.Error -> item { Text(current.message, color = MaterialTheme.colorScheme.error) }
            is DayState.Ready -> {
                val summary = current.value.summary
                if (!chronologyOpen) item {
                    TextButton({ onChronologyOpenChange(true) }, Modifier.fillMaxWidth()) { Text("Cronología", Modifier.weight(1f), textAlign = TextAlign.Start); Text(">") }
                    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Nota del día", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (note.isNotBlank()) IconButton({
                                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Nota del día", note))
                                scope.launch { snackbar.showSnackbar("Nota copiada") }
                            }) { Icon(Icons.Default.ContentCopy, "Copiar nota") }
                            IconButton({ noteDraft = TextFieldValue(note); editingNote = true }) { Icon(Icons.Default.Edit, "Editar nota") }
                        }
                        if (note.isBlank()) Text("Sin nota", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else MarkdownNoteView(note) { line -> note = toggleMarkdownCheckboxLine(note, line); notes.save(selectedDate, note) }
                    } }
                }
                if (!chronologyOpen) item { Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Síntesis del día", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (summary.manuallyEdited) Icon(
                                Icons.Default.Edit,
                                "Síntesis editada manualmente",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp),
                            )
                            IconButton({ vm.generate { scope.launch { snackbar.showSnackbar("Resumen y destacados actualizados") } } }) {
                                Icon(Icons.Default.AutoAwesome, "Generar resumen y destacados con IA")
                            }
                        }
                        if (summary.result != null) {
                            Text(
                                summaryStatusText(summary.status, summary.generatedAt),
                                color = if (summary.status == "ready") androidx.compose.ui.res.colorResource(R.color.summary_success) else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (summary.status == "stale") Text(
                                "Historial modificado desde esta síntesis",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Resumen", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            if (!summary.result?.summary.isNullOrEmpty()) IconButton({
                                context.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("Resumen", summary.result!!.summary))
                                scope.launch { snackbar.showSnackbar("Resumen copiado") }
                            }) { Icon(Icons.Default.ContentCopy, "Copiar resumen") }
                            if (summary.result != null) IconButton({ summaryText = summary.result.summary; editingSummary = true }) { Icon(Icons.Default.Edit, "Editar resumen") }
                        }
                        if (summary.result != null) {
                            Text(summary.result.summary, style = MaterialTheme.typography.bodyMedium)
                        } else Text("Sin generar", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Destacados", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(top = 8.dp))
                            val savedHighlights = summary.result?.highlights.orEmpty()
                            if (savedHighlights.isNotEmpty()) IconButton({
                                context.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("Destacados", savedHighlights.joinToString("\n")))
                                scope.launch { snackbar.showSnackbar("Destacados copiados") }
                            }) { Icon(Icons.Default.ContentCopy, "Copiar destacados") }
                            if (summary.result != null) IconButton({
                                highlightsText = summary.result?.highlights?.joinToString("\n") ?: ""
                                editingHighlights = true
                            }) { Icon(Icons.Default.Edit, "Editar destacados") }
                        }
                        val highlights = summary.result?.highlights.orEmpty()
                        if (highlights.isEmpty()) Text("Sin destacados", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else highlights.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                } }
                val flat = flatChronology(current.value.interactions, newestFirst)
                if (chronologyOpen && flat.isEmpty()) item { Text("No hay transcripciones", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else if (chronologyOpen) items(flat, key = { it.id }) { interaction ->
                    val group = listOf(interaction); val key = "interaction:${interaction.id}"
                    CompactTimeline(group, key in selectedKeys || interaction.id == highlightedInteractionId, selectedKeys.isNotEmpty(), {
                        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                    }) { selectedKeys = selectedKeys + key }
                }
            }
        }
    }
    if (editingSummary) AlertDialog(onDismissRequest = { editingSummary = false }, title = { Text("Editar resumen") }, text = { OutlinedTextField(summaryText, { summaryText = it }, modifier = Modifier.fillMaxWidth(), minLines = 4) }, confirmButton = { TextButton({ vm.updateSummary(DailySummaryUpdate(summary = summaryText)); editingSummary = false }) { Text("Guardar") } }, dismissButton = { TextButton({ editingSummary = false }) { Text("Cancelar") } })
    if (editingHighlights) AlertDialog(onDismissRequest = { editingHighlights = false }, title = { Text("Editar destacados") }, text = { OutlinedTextField(highlightsText, { highlightsText = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("Un destacado por línea") }) }, confirmButton = { TextButton({ vm.updateSummary(DailySummaryUpdate(highlights = highlightsText.lines().map(String::trim).filter(String::isNotEmpty))); editingHighlights = false }) { Text("Guardar") } }, dismissButton = { TextButton({ editingHighlights = false }) { Text("Cancelar") } })
    if (editingNote) {
        val noteFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var headingMenu by remember { mutableStateOf(false) }
        fun keepEditing(updated: TextFieldValue) {
            noteDraft = updated
            noteFocus.requestFocus()
            keyboard?.show()
        }
        AlertDialog(
            onDismissRequest = { note = noteDraft.text; notes.save(selectedDate, note); editingNote = false },
            title = { Text("Nota del día") },
            text = { Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton({ keepEditing(applyMarkdownMarkup(noteDraft, "**")) }, Modifier.size(36.dp)) { Text("B", fontWeight = FontWeight.Bold) }
                    IconButton({ keepEditing(applyMarkdownMarkup(noteDraft, "*")) }, Modifier.size(36.dp)) { Text("I") }
                    IconButton({ keepEditing(applyMarkdownMarkup(noteDraft, "~~")) }, Modifier.size(36.dp)) { Text("S") }
                    IconButton({ keepEditing(toggleMarkdownBullet(noteDraft)) }, Modifier.size(36.dp)) { Text("•") }
                    IconButton({ keepEditing(toggleMarkdownChecklist(noteDraft)) }, Modifier.size(36.dp)) { Icon(Icons.Default.Checklist, "Lista de verificación") }
                    IconButton({ keepEditing(outdentMarkdownList(noteDraft)) }, Modifier.size(36.dp)) { Icon(Icons.Default.FormatIndentDecrease, "Reducir sangría") }
                    IconButton({ keepEditing(indentMarkdownList(noteDraft)) }, Modifier.size(36.dp)) { Icon(Icons.Default.FormatIndentIncrease, "Aumentar sangría") }
                    Box {
                        IconButton({ headingMenu = true }, Modifier.size(36.dp)) { Text("H▾") }
                        DropdownMenu(headingMenu, { headingMenu = false }) {
                            (1..3).forEach { level -> DropdownMenuItem(
                                text = { Text("H$level") },
                                onClick = { headingMenu = false; keepEditing(applyMarkdownHeading(noteDraft, level)) },
                            ) }
                        }
                    }
                }
                OutlinedTextField(
                    noteDraft,
                    { proposed -> noteDraft = applyMarkdownEnter(noteDraft, proposed) },
                    Modifier.fillMaxWidth().focusRequester(noteFocus),
                    minLines = 8,
                )
            } },
            confirmButton = { TextButton({ note = noteDraft.text; notes.save(selectedDate, note); editingNote = false }) { Text("Guardar") } },
        )
        LaunchedEffect(Unit) { noteFocus.requestFocus() }
    }
}

@Composable private fun MarkdownNoteView(markdown: String, toggleLine: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        markdown.lines().forEachIndexed { index, source ->
            val spaces = source.takeWhile { it == ' ' }.length
            val body = source.drop(spaces)
            val checkbox = Regex("^- \\[([ xX])\\] (.*)$").matchEntire(body)
            Row(Modifier.padding(start = (spaces / 2 * 20).dp), verticalAlignment = Alignment.Top) {
                if (checkbox != null) {
                    Checkbox(checkbox.groupValues[1].equals("x", true), { toggleLine(index) }, modifier = Modifier.size(40.dp))
                    Text(renderMarkdown(checkbox.groupValues[2]), modifier = Modifier.padding(top = 9.dp).weight(1f))
                } else Text(renderMarkdown(body), modifier = Modifier.weight(1f))
            }
        }
    }
}

fun formatGeneratedAt(
    value: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = value?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zoneId).format(DateTimeFormatter.ofPattern("d MMM uuuu · HH:mm", locale)) }.getOrNull()
} ?: "fecha desconocida"

fun summaryStatusText(
    status: String,
    generatedAt: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = "${if (status == "ready") "Actualizado" else "Desactualizado"} · ${formatGeneratedAt(generatedAt, zoneId, locale)}"

private fun displayChunkClock(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
}.getOrDefault(value)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun CompactTimeline(
    group: List<InteractionResponse>,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val first = group.first()
    val container = Modifier.fillMaxWidth()
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small)
        .padding(horizontal = 6.dp, vertical = 5.dp)
        .combinedClickable(onClick = { if (selectionMode) onClick() }, onLongClick = onLongClick)
    Row(container, verticalAlignment = Alignment.Top) {
        Text(
            displayChunkClock(first.recordedAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 3.dp, end = 10.dp),
        )
        Text(first.transcription.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
    HorizontalDivider()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ActivityCalendar(
    selected: LocalDate,
    activity: Set<LocalDate>,
    select: (LocalDate) -> Unit,
    dismiss: () -> Unit,
    vm: DayViewModel,
) {
    var month by remember { mutableStateOf(selected.withDayOfMonth(1)) }
    var dots by remember { mutableStateOf(activity) }
    var jumpOpen by remember { mutableStateOf(false) }
    LaunchedEffect(month) { vm.activity(month, month.plusMonths(1).minusDays(1)) { dots = it } }
    if (jumpOpen) {
        var yearMenuOpen by remember { mutableStateOf(false) }
        var chosenYear by remember(month.year) { mutableStateOf(month.year) }
        AlertDialog(
            onDismissRequest = { jumpOpen = false },
            title = { Text("Selecciona mes y año") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton({ yearMenuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("$chosenYear ▼") }
                        DropdownMenu(expanded = yearMenuOpen, onDismissRequest = { yearMenuOpen = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                            (LocalDate.now().year downTo (LocalDate.now().year - 100)).forEach { year ->
                                DropdownMenuItem(text = { Text(if (year == LocalDate.now().year) "• $year" else year.toString(), fontWeight = if (year == LocalDate.now().year) FontWeight.Bold else FontWeight.Normal) }, onClick = { chosenYear = year; yearMenuOpen = false })
                            }
                        }
                    }
                    val names = java.time.Month.values().map {
                        it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                    }
                    names.chunked(3).forEachIndexed { rowIndex, rowNames ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowNames.forEachIndexed { columnIndex, name ->
                                val monthNumber = rowIndex * 3 + columnIndex + 1
                                OutlinedButton(
                                    onClick = {
                                        month = LocalDate.of(chosenYear, monthNumber, 1)
                                        jumpOpen = false
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (chosenYear == LocalDate.now().year && monthNumber == LocalDate.now().monthValue) "• $name" else name) }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ jumpOpen = false }) { Text("Cancelar") } },
        )
    }
    if (!jumpOpen) AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ month = month.minusMonths(1) }) { Icon(Icons.Default.ArrowBack, "Mes anterior") }
                TextButton({ jumpOpen = true }, modifier = Modifier.weight(1f)) {
                    Text(month.format(DateTimeFormatter.ofPattern("MMMM uuuu")), maxLines = 1)
                }
                IconButton({ month = month.plusMonths(1) }) { Icon(Icons.Default.ArrowForward, "Mes siguiente") }
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    listOf("D", "L", "M", "X", "J", "V", "S").forEach { label ->
                        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                }
                val firstOffset = month.dayOfWeek.value % 7
                repeat(6) { week ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { column ->
                            val day = week * 7 + column - firstOffset + 1
                            if (day in 1..month.lengthOfMonth()) {
                                val date = month.withDayOfMonth(day)
                                TextButton(
                                    { select(date) },
                                    Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(day.toString(), maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                                            color = when { date == selected -> MaterialTheme.colorScheme.primary; date == LocalDate.now() -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurface },
                                            fontWeight = if (date == selected || date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal,
                                            textDecoration = if (date == LocalDate.now()) TextDecoration.Underline else null)
                                        Text(if (date in dots) "•" else " ", maxLines = 1, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Cerrar") } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable private fun ColumnScope.ActionsScreen(
    url: String,
    backend: BackendRepository,
    preferences: SharedPreferences,
    snackbar: SnackbarHostState,
    setTopActions: (@Composable RowScope.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val vm = remember { ActionsViewModel(backend) }
    val state by vm.state.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf<String?>(null) }
    var editedTitle by remember { mutableStateOf("") }
    var editedEnd by remember { mutableStateOf("") }
    var editedDate by remember { mutableStateOf<LocalDate?>(null) }
    var editedTime by remember { mutableStateOf("") }
    var editDatePickerOpen by remember { mutableStateOf(false) }
    var editTimePickerOpen by remember { mutableStateOf(false) }
    var editedLocation by remember { mutableStateOf("") }
    var editedNotes by remember { mutableStateOf("") }
    var editedAllDay by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var includeResolved by remember { mutableStateOf(false) }
    var confirmDismissAll by remember { mutableStateOf(false) }
    var confirmCleanExported by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var pendingCalendarAction by remember { mutableStateOf<ProposedAction?>(null) }
    var fallbackCalendarActionId by remember { mutableStateOf<String?>(null) }
    var calendarsForSelection by remember { mutableStateOf<List<WritableCalendar>>(emptyList()) }
    var selectingCalendarFor by remember { mutableStateOf<ProposedAction?>(null) }
    var taskCreatedBeforeCalendar by remember { mutableStateOf(emptySet<String>()) }
    var acceptingActionIds by remember { mutableStateOf(emptySet<String>()) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val calendarGateway = remember(context.applicationContext) {
        AndroidCalendarGateway(context.applicationContext.contentResolver)
    }
    val calendarSelection = remember(preferences) { SharedPreferencesCalendarSelectionStore(preferences) }
    fun hideActionKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    DisposableEffect(Unit) {
        setTopActions {
            IconButton({ vm.load(url, includeResolved) }) {
                Icon(Icons.Default.Refresh, "Actualizar acciones")
            }
            Box {
                IconButton({ overflowOpen = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (includeResolved) "Ocultar resueltas" else "Mostrar resueltas") },
                        onClick = {
                            overflowOpen = false
                            includeResolved = !includeResolved
                            vm.load(url, includeResolved)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Limpiar acciones gestionadas") },
                        onClick = { overflowOpen = false; confirmCleanExported = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Descartar todas las pendientes", color = MaterialTheme.colorScheme.error) },
                        onClick = { overflowOpen = false; confirmDismissAll = true },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
        onDispose { setTopActions({}) }
    }

    if (editDatePickerOpen) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = editedDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { editDatePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { editedDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    editDatePickerOpen = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { editDatePickerOpen = false }) { Text("Cancelar") } },
        ) { DatePicker(state = pickerState) }
    }
    if (editTimePickerOpen) {
        val initialTime = runCatching { LocalTime.parse(editedTime) }.getOrElse { LocalTime.now() }
        val pickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { editTimePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    editedTime = "%02d:%02d".format(pickerState.hour, pickerState.minute)
                    editTimePickerOpen = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { editTimePickerOpen = false }) { Text("Cancelar") } },
            title = { Text("Seleccionar hora") },
            text = { TimePicker(state = pickerState) },
        )
    }

    fun openCalendarFallback(action: ProposedAction) {
        message = null
        val intent = calendarInsertIntent(action)
        if (intent == null) message = "Falta una fecha concreta para abrir el calendario"
        else runCatching { context.startActivity(intent) }
            .onFailure { message = "No hay una aplicación de calendario disponible" }
    }

    fun calendarFailed(action: ProposedAction, detail: String) {
        acceptingActionIds = acceptingActionIds - action.id
        if (action.id in taskCreatedBeforeCalendar) {
            taskCreatedBeforeCalendar = taskCreatedBeforeCalendar - action.id
            vm.restorePendingAfterPartialFailure(url, action) {
                message = "La tarea está guardada, pero no se pudo restaurar la propuesta pendiente. Evita aceptar de nuevo hasta revisar el calendario."
            }
            message = "La tarea ya está en Tareas; falta Calendario. Puedes reintentar sin duplicarla."
        } else {
            message = detail
        }
        fallbackCalendarActionId = action.id
    }

    fun insertInCalendar(action: ProposedAction, calendar: WritableCalendar) {
        val draft = calendarEventDraft(action, calendar.id)
        if (draft == null) {
            calendarFailed(action, "Falta una fecha concreta para añadir al calendario")
            return
        }
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { calendarGateway.insert(draft) } }
                .getOrElse { CalendarInsertResult.Failed }
            if (result is CalendarInsertResult.Inserted) {
                if (action.id in taskCreatedBeforeCalendar) {
                    taskCreatedBeforeCalendar = taskCreatedBeforeCalendar - action.id
                    acceptingActionIds = acceptingActionIds - action.id
                    vm.markAcceptedLocally(action)
                    scope.launch { snackbar.showSnackbar("Añadido a Tareas y calendario") }
                } else {
                    vm.markCalendarExported(
                        url,
                        action,
                        onSuccess = {
                            acceptingActionIds = acceptingActionIds - action.id
                            scope.launch { snackbar.showSnackbar("Añadido al calendario") }
                        },
                        onFailure = { message = "El evento se creó, pero no se pudo actualizar la propuesta; no lo añadas de nuevo." },
                    )
                }
            } else {
                calendarFailed(action, "No se pudo añadir al calendario; la propuesta sigue pendiente")
            }
        }
    }

    fun prepareCalendarInsertion(action: ProposedAction) {
        scope.launch {
            val calendars = runCatching { withContext(Dispatchers.IO) { calendarGateway.writableCalendars() } }
                .getOrElse {
                    calendarFailed(action, "No se pudo acceder a los calendarios; puedes abrir la app de calendario")
                    return@launch
                }
            val automatic = automaticallySelectedWritableCalendar(calendars, calendarSelection)
            when {
                automatic != null -> {
                    if (calendarSelection.mode() == CalendarSelectionMode.REMEMBER) calendarSelection.save(automatic.id)
                    insertInCalendar(action, automatic)
                }
                calendars.isEmpty() -> {
                    calendarFailed(action, "No hay calendarios escribibles; puedes abrir la app de calendario")
                }
                else -> {
                    calendarsForSelection = calendars
                    selectingCalendarFor = action
                }
            }
        }
    }

    val requestCalendarPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val action = pendingCalendarAction
        pendingCalendarAction = null
        if (granted[Manifest.permission.READ_CALENDAR] == true && granted[Manifest.permission.WRITE_CALENDAR] == true && action != null) {
            prepareCalendarInsertion(action)
        } else if (action != null) {
            calendarFailed(action, "Permiso de calendario denegado; puedes abrir la app de calendario")
        }
    }

    fun addToCalendar(action: ProposedAction) {
        fallbackCalendarActionId = null
        message = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (granted) prepareCalendarInsertion(action)
        else {
            pendingCalendarAction = action
            requestCalendarPermissions.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
    }

    fun accept(action: ProposedAction) {
        if (action.id in acceptingActionIds) return
        acceptingActionIds = acceptingActionIds + action.id
        message = null
        val preference = DatedTaskDestination.fromStored(
            preferences.getString(KEY_DATED_TASK_DESTINATION, null)
        )
        val plan = acceptancePlan(action, preference)
        when {
            plan.addToTasks && plan.addToCalendar -> vm.addToTasks(
                url = url,
                action = action,
                markResolved = false,
                onSuccess = {
                    scope.launch { TaskServices.repository(context.applicationContext).upsertFromAction(it) }
                    taskCreatedBeforeCalendar = taskCreatedBeforeCalendar + action.id
                    addToCalendar(action)
                },
                onFailure = {
                    acceptingActionIds = acceptingActionIds - action.id
                    message = "No se pudo guardar la tarea; la propuesta sigue pendiente"
                },
            )
            plan.addToTasks -> vm.addToTasks(
                url,
                action,
                onSuccess = {
                    scope.launch { TaskServices.repository(context.applicationContext).upsertFromAction(it) }
                    acceptingActionIds = acceptingActionIds - action.id
                    scope.launch { snackbar.showSnackbar("Añadido a Tareas") }
                },
                onFailure = {
                    acceptingActionIds = acceptingActionIds - action.id
                    message = "No se pudo guardar la tarea; la propuesta sigue pendiente"
                },
            )
            else -> addToCalendar(action)
        }
    }
    LaunchedEffect(Unit) { if (url.isNotBlank()) vm.load(url, includeResolved) }
    if (confirmDismissAll) {
        AlertDialog(
            onDismissRequest = { confirmDismissAll = false },
            title = { Text("Descartar todas las pendientes") },
            text = { Text("Se eliminarán definitivamente todas las propuestas pendientes.") },
            confirmButton = {
                TextButton({ confirmDismissAll = false; vm.deleteAllPending(url) { scope.launch { snackbar.showSnackbar("Acciones pendientes descartadas") } } }) { Text("Eliminar todas", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { OutlinedButton({ confirmDismissAll = false }) { Text("Cancelar") } },
        )
    }
    if (confirmCleanExported) AlertDialog(
        onDismissRequest = { confirmCleanExported = false },
        title = { Text("Limpiar acciones gestionadas") },
        text = { Text("Se eliminarán las propuestas ya gestionadas del historial de Acciones. Las tareas y eventos que ya se hayan creado no se eliminarán.") },
        confirmButton = { TextButton({ confirmCleanExported = false; vm.deleteAllExported(url) }) { Text("Limpiar") } },
        dismissButton = { TextButton({ confirmCleanExported = false }) { Text("Cancelar") } },
    )

    selectingCalendarFor?.let { action ->
        fun cancelSelection() {
            selectingCalendarFor = null
            calendarFailed(action, "No se eligió calendario; la propuesta sigue pendiente")
        }
        AlertDialog(
            onDismissRequest = ::cancelSelection,
            title = { Text("Selecciona calendario") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    calendarsForSelection.forEach { calendar ->
                        OutlinedButton({
                            if (calendarSelection.mode() == CalendarSelectionMode.REMEMBER) calendarSelection.save(calendar.id)
                            selectingCalendarFor = null
                            insertInCalendar(action, calendar)
                        }) { Text(calendar.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { OutlinedButton(::cancelSelection) { Text("Cancelar") } },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = ::hideActionKeyboard,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
        }
        when (val current = state) {
            ActionsState.Idle -> item { Text("Sin propuestas") }
            ActionsState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            is ActionsState.Error -> item {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton({ vm.load(url, includeResolved) }) { Text("Reintentar") }
            }
            is ActionsState.Ready -> {
                if (current.items.isEmpty()) item {
                    Text(
                        "No hay propuestas pendientes",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                items(current.items, key = { it.id }) { action ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (action.status == "exported") MaterialTheme.colorScheme.primaryContainer else Color.Transparent, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        if (editingId == action.id) {
                            OutlinedTextField(editedTitle, { editedTitle = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton({ hideActionKeyboard(); editDatePickerOpen = true }) { Text(editedDate?.let { "Fecha: $it" } ?: "Seleccionar fecha") }
                            Row(
                                Modifier.clickable { hideActionKeyboard(); editedAllDay = !editedAllDay },
                                verticalAlignment = Alignment.CenterVertically,
                            ) { Checkbox(editedAllDay, null, modifier = Modifier.size(32.dp)); Text("Todo el día") }
                            if (!editedAllDay) {
                                OutlinedButton({ hideActionKeyboard(); editTimePickerOpen = true }) { Text(if (editedTime.isBlank()) "Seleccionar hora" else "Hora: $editedTime") }
                            }
                            if (action.kind != "task") {
                                OutlinedTextField(editedLocation, { editedLocation = it }, label = { Text("Lugar") })
                            }
                            OutlinedTextField(editedNotes, { editedNotes = it }, label = { Text("Notas") })
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(enabled = editedTitle.isNotBlank(), onClick = {
                                    val temporal = editorDateTime(editedDate, editedTime, editedAllDay)
                                    val update = if (action.kind == "task" || action.kind == "reminder") {
                                        ActionUpdate(title = editedTitle, dueText = temporal, allDay = editedAllDay, location = editedLocation, notes = editedNotes)
                                    } else {
                                        ActionUpdate(title = editedTitle, startAt = temporal, endAt = editedEnd.takeIf { it.isNotBlank() }, allDay = editedAllDay, location = editedLocation, notes = editedNotes)
                                    }
                                    hideActionKeyboard(); vm.update(url, action, update); editingId = null
                                }) { Text("Guardar") }
                                OutlinedButton({ hideActionKeyboard(); editingId = null }) { Text("Cancelar") }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (current.includeResolved) "${visibleActionKind(action.kind)} · ${visibleActionStatus(action.status)}" else visibleActionKind(action.kind),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(action.title, style = MaterialTheme.typography.titleMedium)
                                    val temporal = if (action.kind == "event") action.startAt else action.dueText ?: action.startAt
                                    temporal?.let {
                                        Text(
                                            displayActionTemporal(it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    action.location?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (action.status == "pending") Row {
                                    IconButton({
                                        val temporal = action.startAt ?: action.dueText
                                        editedTitle = action.title
                                        editedEnd = action.endAt.orEmpty()
                                        editedDate = editorDate(temporal)
                                        editedTime = editorTime(temporal)
                                        editedLocation = action.location.orEmpty()
                                        editedNotes = action.notes.orEmpty()
                                        editedAllDay = action.allDay
                                        editingId = action.id
                                    }) {
                                        Icon(Icons.Default.Edit, "Editar propuesta", tint = MaterialTheme.colorScheme.tertiary)
                                    }
                                    IconButton(
                                        onClick = { accept(action) },
                                        enabled = action.id !in acceptingActionIds,
                                    ) {
                                        Icon(Icons.Default.Check, "Aceptar propuesta", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton({ vm.delete(url, action) { scope.launch { snackbar.showSnackbar("Acción descartada") } } }) {
                                        Icon(Icons.Default.Close, "Descartar propuesta", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            if (action.status == "pending" && fallbackCalendarActionId == action.id) {
                                OutlinedButton({ openCalendarFallback(action) }) { Text("Abrir en app de calendario") }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                if (current.canLoadMore) item {
                    OutlinedButton({ vm.loadMore(url) }) { Text("Cargar más") }
                }
            }
        }
    }
}

fun tasksInDisplayOrder(items: List<TaskItem>): List<TaskItem> {
    val stableOrder = compareBy<TaskItem> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.id }
    val roots = items.filter { it.parentId == null }.sortedWith(stableOrder)
    val ordered = roots.flatMap { parent ->
        listOf(parent) + items.filter { it.parentId == parent.id }.sortedWith(stableOrder)
    }
    val included = ordered.mapTo(mutableSetOf()) { it.id }
    return ordered + items.filterNot { it.id in included }.sortedWith(stableOrder)
}

fun moveTaskWithinPeers(items: List<TaskItem>, taskId: String, direction: Int): List<TaskItem> {
    val task = items.firstOrNull { it.id == taskId } ?: return items
    val peers = items.filter { it.groupName == task.groupName && it.parentId == task.parentId }
        .sortedWith(compareBy<TaskItem> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.id })
    val from = peers.indexOfFirst { it.id == taskId }
    val to = (from + direction.coerceIn(-1, 1)).coerceIn(0, peers.lastIndex)
    if (from < 0 || from == to) return items
    val moved = peers.toMutableList().apply { add(to, removeAt(from)) }
        .mapIndexed { index, item -> item.copy(sortOrder = index) }
        .associateBy { it.id }
    return items.map { moved[it.id] ?: it }
}

fun moveTaskForDrag(items: List<TaskItem>, taskId: String, direction: Int): List<TaskItem> {
    val task = items.firstOrNull { it.id == taskId } ?: return items
    if (task.parentId != null) return moveTaskWithinPeers(items, taskId, direction)
    val groupOrder = items.map { it.groupName }.distinct()
        .sortedWith(compareBy<String?> { it == null }.thenBy { it.orEmpty() })
    val roots = groupOrder.flatMap { group ->
        items.filter { it.parentId == null && it.groupName == group }
            .sortedWith(compareBy<TaskItem> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.id })
    }
    val from = roots.indexOfFirst { it.id == taskId }
    val to = (from + direction.coerceIn(-1, 1)).coerceIn(0, roots.lastIndex)
    if (from < 0 || from == to) return items
    val neighbor = roots[to]
    if (neighbor.groupName == task.groupName) return moveTaskWithinPeers(items, taskId, direction)

    val targetGroup = neighbor.groupName
    val movedFamilyIds = items.filter { it.id == taskId || it.parentId == taskId }.mapTo(mutableSetOf()) { it.id }
    val targetRoots = items.filter { it.parentId == null && it.groupName == targetGroup && it.id != taskId }
        .sortedWith(compareBy<TaskItem> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.id })
        .toMutableList()
    val insertAt = if (direction > 0) 0 else targetRoots.size
    targetRoots.add(insertAt, task.copy(groupName = targetGroup))
    val targetById = targetRoots.mapIndexed { index, item -> item.id to item.copy(sortOrder = index) }.toMap()
    val sourceRoots = items.filter { it.parentId == null && it.groupName == task.groupName && it.id != taskId }
        .sortedWith(compareBy<TaskItem> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.id })
        .mapIndexed { index, item -> item.id to item.copy(sortOrder = index) }.toMap()
    return items.map { item ->
        when {
            item.id in targetById -> targetById.getValue(item.id)
            item.id in sourceRoots -> sourceRoots.getValue(item.id)
            item.id in movedFamilyIds -> item.copy(groupName = targetGroup)
            else -> item
        }
    }
}

fun taskOrderSignature(items: List<TaskItem>): List<String> =
    items.map { "${it.id}|${it.groupName}|${it.parentId}|${it.sortOrder}" }.sorted()

@Composable
internal fun TaskEditorSurface(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().testTag("task-editor-surface"),
    ) { content() }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable private fun ColumnScope.TasksScreen(
    url: String,
    vm: TasksViewModel,
    openNewTask: Boolean = false,
    onOpenNewTaskConsumed: () -> Unit = {},
    setTopActions: (@Composable RowScope.() -> Unit) -> Unit,
) {
    val firstTaskFrame = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    SideEffect { if (BuildConfig.DEBUG && firstTaskFrame.compareAndSet(true, false)) Log.d("TasksTiming", "tasks_first_composition ${SystemClock.elapsedRealtime()}") }
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var dueTime by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var parent by remember { mutableStateOf<String?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var parentMenuOpen by remember { mutableStateOf(false) }
    var groupMenuOpen by remember { mutableStateOf(false) }
    var dragDraft by remember { mutableStateOf<List<TaskItem>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val roomItems = (state as? TasksState.Ready)?.items
    LaunchedEffect(roomItems, dragDraft) {
        if (dragDraft != null && roomItems != null && taskOrderSignature(dragDraft!!) == taskOrderSignature(roomItems)) dragDraft = null
    }
    fun hideEditorKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    fun closeEditor() {
        creating = false
        editing = null
        hideEditorKeyboard()
    }
    fun begin(item: TaskItem?) {
        editing = item
        creating = item == null
        text = item?.text.orEmpty()
        group = item?.groupName.orEmpty()
        dueDate = editorDate(item?.dueAt)
        dueTime = editorTime(item?.dueAt)
        allDay = item?.allDay ?: false
        parent = item?.parentId
    }
    LaunchedEffect(openNewTask) { if (openNewTask) { begin(null); onOpenNewTaskConsumed() } }
    val currentBegin by rememberUpdatedState<(TaskItem?) -> Unit>(::begin)
    DisposableEffect(Unit) {
        setTopActions {
            IconButton({ currentBegin(null) }) { Icon(Icons.Default.Add, "Crear tarea") }
        }
        onDispose { setTopActions({}) }
    }
    if (datePickerOpen) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton({
                    pickerState.selectedDateMillis?.let {
                        dueDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (dueTime.isBlank()) allDay = true
                    }
                    datePickerOpen = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton({ datePickerOpen = false }) { Text("Cancelar") } },
        ) { DatePicker(state = pickerState) }
    }
    if (timePickerOpen) {
        val initialTime = runCatching { LocalTime.parse(dueTime) }.getOrElse { LocalTime.now() }
        val pickerState = rememberTimePickerState(initialTime.hour, initialTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePickerOpen = false },
            title = { Text("Seleccionar hora") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton({
                    dueTime = "%02d:%02d".format(pickerState.hour, pickerState.minute)
                    timePickerOpen = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton({ timePickerOpen = false }) { Text("Cancelar") } },
        )
    }
    if (creating || editing != null) {
        val allTasks = (state as? TasksState.Ready)?.items.orEmpty()
        val editingHasChildren = editing?.let { current -> allTasks.any { it.parentId == current.id } } == true
        val possibleParents = if (editingHasChildren) emptyList() else allTasks
            .filter { it.parentId == null && it.id != editing?.id }
        val selectedParent = possibleParents.firstOrNull { it.id == parent }
        val existingGroups = allTasks.mapNotNull { it.groupName }.distinct()
        val validTime = dueDate == null || allDay || runCatching { LocalTime.parse(dueTime) }.isSuccess
        BasicAlertDialog(
            onDismissRequest = ::closeEditor,
        ) {
            TaskEditorSurface {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        if (editing == null) "Nueva tarea" else "Editar tarea",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(text, { text = it }, label = { Text("Tarea") }, modifier = Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                selectedParent?.groupName.orEmpty().ifEmpty { group }, { group = it }, label = { Text("Grupo opcional") }, enabled = parent == null, modifier = Modifier.fillMaxWidth(),
                                trailingIcon = { IconButton({ hideEditorKeyboard(); groupMenuOpen = true }) { Icon(Icons.Default.ArrowDropDown, "Elegir grupo existente") } },
                            )
                            DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                                existingGroups.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { group = name; groupMenuOpen = false }) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton({ hideEditorKeyboard(); datePickerOpen = true }) {
                                Text(dueDate?.format(DateTimeFormatter.ofPattern("d MMM uuuu")) ?: "Añadir fecha")
                            }
                            if (dueDate != null) IconButton({ hideEditorKeyboard(); dueDate = null; dueTime = "" }) {
                                Icon(Icons.Default.Close, "Quitar fecha")
                            }
                        }
                        if (dueDate != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    Modifier.weight(1f).clickable { hideEditorKeyboard(); allDay = !allDay },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(allDay, null)
                                    Text("Todo el día")
                                }
                                if (!allDay) TextButton({ hideEditorKeyboard(); timePickerOpen = true }) {
                                    Text(if (dueTime.isBlank()) "Añadir hora" else dueTime)
                                }
                            }
                            if (!validTime) Text(
                                "Selecciona una hora para esta fecha",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("Subtarea de", style = MaterialTheme.typography.labelLarge)
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton({ hideEditorKeyboard(); parentMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(possibleParents.firstOrNull { it.id == parent }?.text ?: "Ninguna", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = parentMenuOpen, onDismissRequest = { parentMenuOpen = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                                DropdownMenuItem(text = { Text("Ninguna") }, onClick = { parent = null; parentMenuOpen = false })
                                possibleParents.forEach { candidate ->
                                    DropdownMenuItem(text = { Text(candidate.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { parent = candidate.id; group = candidate.groupName.orEmpty(); parentMenuOpen = false })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(::closeEditor) { Text("Cancelar") }
                        TextButton(enabled = text.isNotBlank() && validTime, onClick = {
                            val due = taskDueDateTime(dueDate, dueTime, allDay)
                            vm.save(
                                url,
                                editing,
                                TaskItemCreate(text = text.trim(), groupName = group.trim().ifBlank { null }, parentId = parent, dueAt = due, allDay = allDay),
                            )
                            closeEditor()
                        }) { Text("Guardar") }
                    }
                }
            }
        }
    }
    when (val current = state) {
        TasksState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is TasksState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(current.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton({ vm.load(url) }) { Text("Reintentar") }
        }
        is TasksState.Ready -> {
            if (current.items.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Checklist, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No hay tareas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text("Pulsa + para crear la primera", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val shownItems = dragDraft ?: current.items
                val groups = if (dragDraft == null) current.sections else taskUiSections(shownItems)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
                ) {
                    groups.forEach { section ->
                        val groupName = section.groupName
                        val groupItems = section.items
                        item(key = "group:${groupName ?: "none"}") {
                            Text(
                                groupName ?: "Sin grupo",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(groupItems, key = { it.id }) { item ->
                            TaskRow(
                                item = item,
                                onToggle = { vm.toggle(url, item) },
                                onEdit = { begin(item) },
                                onDelete = { vm.delete(url, item) },
                                dragging = draggingId == item.id,
                                onDragStart = { draggingId = item.id; dragDraft = shownItems },
                                onDragStep = { direction -> dragDraft = moveTaskForDrag(dragDraft ?: shownItems, item.id, direction) },
                                onDragEnd = {
                                    dragDraft?.let { draft -> vm.reorder(url, draft) { dragDraft = null } }
                                    draggingId = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TaskRow(
    item: TaskItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDragStep: (Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDragStep by rememberUpdatedState(onDragStep)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (dragging) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small)
            .padding(start = if (item.parentId != null) 24.dp else 0.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(item.completed, { onToggle() })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                color = if (item.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            item.dueAt?.let {
                Text(formatTaskDue(it, item.allDay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row {
            IconButton(onEdit) { Icon(Icons.Default.Edit, "Editar tarea", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onDelete) { Icon(Icons.Default.Delete, "Eliminar tarea", tint = MaterialTheme.colorScheme.error) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Reordenar tarea" }
                    .pointerInput(item.id) {
                        var accumulatedDrag = 0f
                        detectDragGesturesAfterLongPress(
                            onDragStart = { accumulatedDrag = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress); currentDragStart() },
                            onDragEnd = currentDragEnd,
                            onDragCancel = currentDragEnd,
                        ) { change, amount ->
                            change.consume()
                            accumulatedDrag += amount.y
                            if (kotlin.math.abs(accumulatedDrag) >= 48.dp.toPx()) {
                                currentDragStep(if (accumulatedDrag > 0) 1 else -1)
                                accumulatedDrag = 0f
                            }
                        }
                    },
            ) { Icon(Icons.Default.DragHandle, null) }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = if (item.parentId != null) 72.dp else 48.dp))
}

@Composable
private fun ColumnScope.CalendarSettingsScreen(
    preferences: SharedPreferences,
    backendUrl: String,
    appTheme: AppTheme,
    backend: BackendRepository,
    snackbar: SnackbarHostState,
    onTheme: (AppTheme) -> Unit,
    setTopActions: (@Composable RowScope.() -> Unit) -> Unit,
    onBackendUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val gateway = remember(context.applicationContext) {
        AndroidCalendarGateway(context.applicationContext.contentResolver)
    }
    val selection = remember(preferences) { SharedPreferencesCalendarSelectionStore(preferences) }
    var mode by remember { mutableStateOf(selection.mode()) }
    var calendars by remember { mutableStateOf<List<WritableCalendar>>(emptyList()) }
    var choosing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var taskDestination by remember {
        mutableStateOf(DatedTaskDestination.fromStored(preferences.getString(KEY_DATED_TASK_DESTINATION, null)))
    }
    val scope = rememberCoroutineScope()
    var preparedExport by remember { mutableStateOf<PreparedDataExport?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val createExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val prepared = preparedExport
        if (uri == null || prepared == null) {
            exporting = false
            preparedExport = null
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            DataExportWriter().write(output, prepared)
                        } ?: error("No se pudo abrir el archivo elegido")
                    }
                }
                exporting = false
                preparedExport = null
                snackbar.showSnackbar(if (result.isSuccess) "Datos exportados" else "No se pudieron guardar los datos exportados")
            }
        }
    }
    DisposableEffect(Unit) {
        setTopActions({})
        onDispose { setTopActions({}) }
    }

    fun loadCalendars(openPicker: Boolean) {
        scope.launch {
            feedback = null
            runCatching { withContext(Dispatchers.IO) { gateway.writableCalendars() } }
                .onSuccess { found ->
                    calendars = found
                    if (openPicker) {
                        if (found.isEmpty()) feedback = "No hay calendarios escribibles disponibles"
                        else choosing = true
                    }
                }
                .onFailure { feedback = "No se pudo acceder a los calendarios" }
        }
    }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.READ_CALENDAR] == true && granted[Manifest.permission.WRITE_CALENDAR] == true) loadCalendars(openPicker = true)
        else feedback = "Permiso de calendario denegado; el resto de la aplicación sigue disponible"
    }

    fun requestOrChoose() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (granted) loadCalendars(openPicker = true)
        else requestPermissions.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (granted) loadCalendars(openPicker = false)
    }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus(); keyboard?.hide() } },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionTitle("Backend")
        OutlinedTextField(
            backendUrl,
            onBackendUrl,
            label = { Text("URL del backend") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        SettingsSectionTitle("Apariencia")
        listOf(
            Triple(AppTheme.SYSTEM, "Sistema", "Sigue el modo claro u oscuro configurado en Android."),
            Triple(AppTheme.LIGHT, "Claro", null),
            Triple(AppTheme.DARK, "Oscuro", null),
        ).forEach { (theme, title, supporting) ->
            SettingOption(selected = appTheme == theme, title = title, supporting = supporting) { onTheme(theme) }
        }

        HorizontalDivider()
        SettingsSectionTitle("Calendario")
        val selectedCalendarId = selection.selectedCalendarId()
        val selected = selectedCalendarId?.let { id -> calendars.firstOrNull { it.id == id }?.label }
        Text("Calendario predeterminado", style = MaterialTheme.typography.labelLarge)
        Text(
            selected ?: if (selectedCalendarId == null) "Ninguno" else "No disponible",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Modo de calendario", style = MaterialTheme.typography.labelLarge)
        SettingOption(
            selected = mode == CalendarSelectionMode.REMEMBER,
            title = "Recordar calendario",
            onClick = { mode = CalendarSelectionMode.REMEMBER; selection.saveMode(mode) },
        )
        SettingOption(
            selected = mode == CalendarSelectionMode.ALWAYS_ASK,
            title = "Preguntar qué calendario usar",
            onClick = { mode = CalendarSelectionMode.ALWAYS_ASK; selection.saveMode(mode) },
        )
        OutlinedButton(::requestOrChoose) { Text("Cambiar calendario") }
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        SettingsSectionTitle("Tareas")
        Text("Al aceptar una tarea con fecha", style = MaterialTheme.typography.labelLarge)
        listOf(
            Triple(DatedTaskDestination.BOTH, "Tareas y calendario", "Guarda la tarea en Tareas y añade también su fecha al calendario."),
            Triple(DatedTaskDestination.TASKS, "Solo Tareas", "La tarea queda pendiente en Tareas, sin crear evento de calendario."),
            Triple(DatedTaskDestination.CALENDAR, "Solo calendario", "La guarda únicamente en el calendario."),
        ).forEach { (destination, title, supporting) ->
            SettingOption(
                selected = taskDestination == destination,
                title = title,
                supporting = supporting,
                onClick = {
                    taskDestination = destination
                    preferences.edit().putString(KEY_DATED_TASK_DESTINATION, destination.storedValue).apply()
                },
            )
        }

        HorizontalDivider()
        SettingsSectionTitle("Datos")
        Text(
            "Genera una copia portable del diario, las tareas y las acciones. No incluye audio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = !exporting,
            onClick = {
                exporting = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val backendData = backend.exportData(backendUrl)
                            val tasks = TaskServices.repository(context).exportVisible().map { it.toExportTask() }
                            PreparedDataExport(backendData, tasks, Instant.now(), BuildConfig.VERSION_NAME, DiaryNoteRepository(context.filesDir).snapshot())
                        }
                    }
                    result.onSuccess { prepared ->
                        preparedExport = prepared
                        createExport.launch("audio-diary-export-${LocalDate.now()}.zip")
                    }.onFailure { error ->
                        exporting = false
                        snackbar.showSnackbar(exportPreparationError(error))
                    }
                }
            },
        ) { Text(if (exporting) "Preparando…" else "Exportar datos") }
        if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Selecciona calendario") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    calendars.forEach { calendar ->
                        OutlinedButton({
                            selection.save(calendar.id)
                            choosing = false
                            feedback = null
                        }) { Text(calendar.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { OutlinedButton({ choosing = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingOption(
    selected: Boolean,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            RadioButton(selected = selected, onClick = null)
        }
        Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
