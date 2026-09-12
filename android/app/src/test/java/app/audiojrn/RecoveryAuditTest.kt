package app.audiojrn

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecoveryAuditTest {
    @Test fun stop_offline_survives_recreation_and_preserves_multiple_sessions() = runTest {
        val directory = createTempDir("finalize-audit")
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        val first = PendingFinalizations(directory)
        first.remember(a, 2); first.remember(b, 0)
        val repository = object : BackendRepository {
            var fail = true
            val calls = mutableListOf<String>()
            override suspend fun process(audio: CapturedAudio, backendUrl: String): ProcessResponse = error("unused")
            override suspend fun finalizeSession(sessionId: String, lastChunkIndex: Int, backendUrl: String): ContinuousSessionResult {
                calls += sessionId
                if (fail) error("offline or incomplete")
                return ContinuousSessionResult(sessionId, "complete", lastChunkIndex)
            }
        }
        first.retry(repository, "url")
        assertEquals(2, PendingFinalizations(directory).pending().size)
        repository.fail = false
        val restored = PendingFinalizations(directory)
        restored.retry(repository, "url")
        assertTrue(PendingFinalizations(directory).pending().isEmpty())
        assertEquals(4, repository.calls.size)
        restored.retry(repository, "url")
        assertEquals(4, repository.calls.size)
        directory.deleteRecursively()
    }

    @Test fun failed_inflight_upload_requeues_even_when_producer_filled_capacity() = runTest {
        val directory = createTempDir("race-audit")
        val queue = SegmentQueue(directory, 1)
        val first = PendingSegment(File(directory, "a.wav").apply { writeText("a") }, Instant.EPOCH)
        val second = PendingSegment(File(directory, "b.wav").apply { writeText("b") }, Instant.EPOCH)
        queue.offer(first)
        val coordinator = SegmentUploadCoordinator(queue) { queue.offer(second); error("offline") }
        assertFalse(coordinator.drain())
        assertEquals(2, queue.size)
        assertEquals(first, queue.poll()); assertEquals(second, queue.poll())
        assertTrue(first.file.exists()); assertTrue(second.file.exists())
        directory.deleteRecursively()
    }

    @Test fun continuous_chunks_and_lap_pause_finish_markers_are_durable_without_backend() {
        val root = createTempDir("continuous-offline")
        val segments = File(root, "segments")
        val markerDirectory = File(root, "finalizations")
        val sessionIds = List(3) { UUID.randomUUID().toString() }
        sessionIds.forEachIndexed { index, sessionId ->
            File(segments.apply { mkdirs() }, "segment-${index + 1}-continuous-$sessionId-0-${UUID.randomUUID()}.wav").writeText("pcm")
            PendingFinalizations(markerDirectory).apply {
                recordChunk(sessionId, 0)
                remember(sessionId, 0)
            }
        }

        assertEquals(3, SegmentQueue(segments).size)
        assertEquals(sessionIds.toSet(), PendingFinalizations(markerDirectory).pendingSessionIds())
        root.deleteRecursively()
    }

    @Test fun manual_recovery_preserves_timestamp_and_idempotency_key() {
        val directory = createTempDir("manual-audit")
        val file = File(directory, "capture-1234567890000-note.m4a").apply { writeText("synthetic") }
        val a = recoverManualCapture(directory)!!
        val b = recoverManualCapture(directory)!!
        assertEquals(Instant.ofEpochMilli(1234567890000), a.recordedAt)
        assertEquals(a.captureChunkId, b.captureChunkId)
        assertEquals(file, b.file)
        directory.deleteRecursively()
    }

    @Test fun all_pending_manual_captures_are_retried_independently_and_only_successes_are_deleted() = runTest {
        val directory = createTempDir("manual-pending-many")
        val captures = (1L..3L).map { timestamp ->
            File(directory, "capture-$timestamp-note.m4a").apply { writeText("audio-$timestamp") }
        }
        var failingName: String? = captures[1].name
        val first = retryPendingManualCaptures(directory) { audio ->
            if (audio.file.name == failingName) error("offline")
        }
        assertEquals(ManualPendingRetryResult(attempted = 3, processed = 2, remaining = 1), first)
        assertEquals(listOf(captures[1].name), recoverManualCaptures(directory).map { it.file.name })

        failingName = null
        val second = retryPendingManualCaptures(directory) { }
        assertEquals(ManualPendingRetryResult(attempted = 1, processed = 1, remaining = 0), second)
        assertTrue(recoverManualCaptures(directory).isEmpty())
        assertEquals(ManualPendingRetryResult(0, 0, 0), retryPendingManualCaptures(captures.map {
            CapturedAudio(it, java.time.Instant.EPOCH)
        }) { error("A stale retry snapshot must not upload deleted files") })
        assertEquals(ManualPendingRetryResult(0, 0, 0), retryPendingManualCaptures(directory) { })
        directory.deleteRecursively()
    }

    @Test fun calendar_all_day_offset_dst_and_reversed_interval() {
        val base = ProposedAction("1", "event", "pending", "Synthetic", startAt = "2026-03-29T03:30:00+02:00", evidence = "Synthetic", createdAt = "2026-09-07T00:00:00Z")
        assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(), calendarInsertValues(base)!!.beginMillis)
        val allDay = base.copy(allDay = true, startAt = "2026-03-29", endAt = "2026-03-30", notes = "Reviewed")
        val values = calendarInsertValues(allDay)!!
        assertEquals(86_400_000L, values.endMillis - values.beginMillis)
        assertTrue(values.description.contains("Reviewed"))
        assertNull(calendarInsertValues(base.copy(startAt = "2026-03-29")))
        assertNull(calendarInsertValues(base.copy(endAt = "2026-03-29T01:00:00Z")))
        assertNotNull(calendarInsertValues(base.copy(kind = "reminder", startAt = null, dueText = "2026-03-29T03:30:00+02:00")))
    }
}
