package app.audiojrn
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
 private val dispatcher=StandardTestDispatcher(); @Before fun before(){Dispatchers.setMain(dispatcher)} @After fun after(){Dispatchers.resetMain()}
 @Test fun start_stop_auto_stop_and_discard()=runTest { val r=FakeAudioRecorder(); val v=CaptureViewModel(r,FakeBackend(),2); v.startRecording();assertTrue(v.state.value is UiState.Recording);v.stopRecording();assertTrue(v.state.value is UiState.Ready);v.startRecording();advanceTimeBy(2000);advanceUntilIdle();assertTrue(v.state.value is UiState.Ready);v.startRecording();val f=r.current.file;v.discard();assertTrue(v.state.value is UiState.Idle);assertFalse(f.exists()) }
 @Test fun recorder_errors_and_discard()=runTest { val a=CaptureViewModel(FakeAudioRecorder(startFails=true),FakeBackend());a.startRecording();assertTrue(a.state.value is UiState.Error); val r=FakeAudioRecorder(stopFails=true);val b=CaptureViewModel(r,FakeBackend());b.startRecording();b.stopRecording();assertTrue(b.state.value is UiState.Error); val ok=FakeAudioRecorder();val c=CaptureViewModel(ok,FakeBackend());c.startRecording();c.stopRecording();val f=ok.current.file;c.discard();assertTrue(c.state.value is UiState.Idle);assertFalse(f.exists()) }
 @Test fun success_new_recording_starts_immediately_and_processing_guard()=runTest { val r=FakeAudioRecorder();val b=FakeBackend(failures=1);val v=CaptureViewModel(r,b);v.startRecording();v.stopRecording();val f=r.current.file;v.send("http://t");advanceUntilIdle();assertTrue(v.state.value is UiState.Error);assertTrue(f.exists());v.send("http://t");advanceUntilIdle();assertTrue(v.state.value is UiState.Success);assertFalse(f.exists());v.newRecording();assertTrue(v.state.value is UiState.Recording);assertEquals(2,b.calls) }
 @Test fun security_error_explains_network_permission()=runTest { val v=CaptureViewModel(FakeAudioRecorder(),FakeBackend(failure=SecurityException()));v.startRecording();v.stopRecording();v.send("http://t");advanceUntilIdle();assertEquals("No se puede acceder a la red; comprueba el permiso de Internet",(v.state.value as UiState.Error).message) }
 @Test fun capture_preferences_and_elapsed_are_stable() { assertEquals(CaptureMode.MANUAL,persistedCaptureMode(null,false));assertEquals(CaptureMode.CONTINUOUS,persistedCaptureMode("CONTINUOUS",false));assertEquals(CaptureMode.CONTINUOUS,persistedCaptureMode("MANUAL",true));assertEquals(1800,CaptureForegroundService.MANUAL_MAX_SECONDS);assertEquals(40L*1024*1024,MANUAL_MAX_BYTES);assertEquals(42,continuousElapsedSeconds(1_000,43_000)) }
 @Test fun continuous_elapsed_format() { assertEquals("05 s",formatContinuousElapsed(5));assertEquals("59 s",formatContinuousElapsed(59));assertEquals("01:00",formatContinuousElapsed(60));assertEquals("59:59",formatContinuousElapsed(3599));assertEquals("01:00:00",formatContinuousElapsed(3600)) }
 @Test fun manual_capture_rejects_above_hard_cap() { val v=CaptureViewModel(FakeAudioRecorder(),FakeBackend());v.startRecording(1801);assertTrue(v.state.value is UiState.Error) }
 @Test fun capture_state_labels_cover_global_bar_and_pause_excludes_time() {
  assertEquals(CaptureSessionState.RECORDING_MANUAL,recordingCaptureState(CaptureMode.MANUAL));assertEquals(CaptureSessionState.PAUSED_CONTINUOUS,pausedCaptureState(CaptureMode.CONTINUOUS))
  assertEquals("Grabando · Manual",globalRecordingLabel(CaptureSessionState.RECORDING_MANUAL,CaptureMode.MANUAL));assertEquals("Pausado · Continuo",globalRecordingLabel(CaptureSessionState.PAUSED_CONTINUOUS,CaptureMode.CONTINUOUS));assertNull(globalRecordingLabel(CaptureSessionState.IDLE,null))
  assertEquals(8L,activeElapsedSeconds(5_000,10_000,13_000));assertEquals(5L,activeElapsedSeconds(5_000,null,99_000))
 }
 @Test fun pending_discovery_preserves_every_active_capture_snapshot() {
  val cases = listOf(
   CaptureRuntimeSnapshot(CaptureSessionState.RECORDING_MANUAL, CaptureMode.MANUAL, 17, null),
   CaptureRuntimeSnapshot(CaptureSessionState.PAUSED_MANUAL, CaptureMode.MANUAL, 18, null),
   CaptureRuntimeSnapshot(CaptureSessionState.RECORDING_CONTINUOUS, CaptureMode.CONTINUOUS, 19, "continuous-session"),
   CaptureRuntimeSnapshot(CaptureSessionState.PAUSED_CONTINUOUS, CaptureMode.CONTINUOUS, 20, "continuous-session"),
   CaptureRuntimeSnapshot(CaptureSessionState.FINALIZING, CaptureMode.CONTINUOUS, 21, "continuous-session"),
  )
  cases.forEach { snapshot -> assertEquals(snapshot, withDiscoveredPendingManual(snapshot, hasPending = true)) }
 }
 @Test fun capture_start_is_local_first_and_manual_finish_without_url_is_retryable() {
  assertNull(backendUrlIfAvailable(""))
  assertNull(backendUrlIfAvailable("not-a-url"))
  val idle = CaptureRuntimeSnapshot(CaptureSessionState.IDLE, null, 0, null)
  assertEquals(idle, withDiscoveredPendingManual(idle, hasPending = true))
  assertEquals(PendingStartResult(CaptureMode.MANUAL, null), consumePendingStart(CaptureMode.MANUAL, permissionGranted = true))
  assertEquals(PendingStartResult(null, null), consumePendingStart(CaptureMode.CONTINUOUS, permissionGranted = false))
 }
 @Test fun first_capture_permission_sequence_preserves_mode_and_notification_denial_allows_start() {
  val mode = CaptureMode.CONTINUOUS
  assertEquals(CapturePermissionStep.REQUEST_MICROPHONE, nextCapturePermissionStep(mode, false, true, false, false))
  assertEquals(CapturePermissionStep.REQUEST_NOTIFICATIONS, nextCapturePermissionStep(mode, true, true, false, false))
  assertEquals(CapturePermissionStep.START, nextCapturePermissionStep(mode, true, true, false, true))
  assertEquals(CapturePermissionStep.START, nextCapturePermissionStep(mode, true, true, true, false))
  assertEquals(CapturePermissionStep.START, nextCapturePermissionStep(mode, true, false, false, false))
  assertEquals(CapturePermissionStep.NONE, nextCapturePermissionStep(null, true, true, false, false))
 }
 @Test fun pending_retry_and_continuous_finish_messages_cover_all_outcomes() {
  assertEquals("No hay capturas pendientes", pendingCaptureRetryMessage(0, false, 0))
  assertEquals("Configura una URL válida para procesarlas", pendingCaptureRetryMessage(2, false, 2))
  assertEquals("Capturas pendientes procesadas", pendingCaptureRetryMessage(2, true, 0))
  assertEquals("Quedan capturas pendientes para reintentar", pendingCaptureRetryMessage(2, true, 1))
  assertEquals("Captura guardada; pendiente de procesar", continuousFinishMessage(false, true))
  assertEquals("Captura guardada; pendiente de procesar", continuousFinishMessage(true, true))
  assertEquals("Captura enviada para procesar", continuousFinishMessage(true, false))
  assertNull(clearedCaptureError())
 }
 @Test fun continuous_resume_uses_a_new_session_while_remount_does_not() {
  val active = CaptureRuntimeSnapshot(CaptureSessionState.RECORDING_CONTINUOUS, CaptureMode.CONTINUOUS, 42, newContinuousSessionId())
  assertEquals(active, withDiscoveredPendingManual(active, hasPending = false))
  assertNotEquals(active.currentSessionId, newContinuousSessionId(active.currentSessionId))
 }
 @Test fun continuous_controls_replace_discard_with_left_lap() {
  assertEquals(
   CaptureControlLayout(CaptureControlAction.PROCESS_NOW, CaptureControlAction.PAUSE_RESUME, CaptureControlAction.FINISH),
   captureControlLayout(CaptureMode.CONTINUOUS),
  )
  assertEquals(CaptureControlAction.DISCARD, captureControlLayout(CaptureMode.MANUAL).left)
 }
}
class FakeAudioRecorder(private val startFails:Boolean=false,private val stopFails:Boolean=false):AudioRecorder { lateinit var current:CapturedAudio; override fun start():CapturedAudio {if(startFails)error("start");current=CapturedAudio(File.createTempFile("capture-",".m4a").apply{writeBytes(byteArrayOf(1))},Instant.parse("2026-09-05T10:00:00Z"));return current};override fun stop()=if(stopFails)error("stop") else current;override fun discard(){if(::current.isInitialized)current.file.delete()} }
class FakeBackend(private var failures:Int=0,private val failure:Throwable?=null):BackendRepository {var calls=0;override suspend fun process(audio:CapturedAudio,backendUrl:String):ProcessResponse{calls++;failure?.let { throw it };if(failures-->0)throw java.net.ConnectException();return sampleResponse()}}
fun sampleResponse()=ProcessResponse("id","2026-09-05T10:00:00Z","2026-09-05T10:01:00Z",Transcription("hola",model="base"),Analysis("ok",emptyList(),emptyList(),emptyList(),emptyList()))
