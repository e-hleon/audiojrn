package es.hector.audio_diary
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
class ContinuousAudioTest {
 @Test fun pause_is_exactly_two_seconds_after_minimum() { val c=ContinuousChunker(minimumSeconds=1,naturalStartSeconds=1,pauseFrames=100,hardCapSeconds=4,vad=EnergyVad(calibrationFrames=0)); val voice=ShortArray(AUDIO_FRAME_SAMPLES){10000}; val silence=ShortArray(AUDIO_FRAME_SAMPLES); repeat(50){c.add(voice)}; repeat(99){assertTrue(c.add(silence).isEmpty())}; assertEquals(3*AUDIO_SAMPLE_RATE,c.add(silence).single().size) }
 @Test fun hard_cap_is_55_seconds_without_loss() { val c=ContinuousChunker(); val frame=ShortArray(AUDIO_FRAME_SAMPLES){10000}; val chunks=mutableListOf<ShortArray>(); repeat(2800){chunks+=c.add(frame)}; c.flush()?.let{chunks+=it}; assertEquals(55*AUDIO_SAMPLE_RATE,chunks.first().size); assertEquals(56*AUDIO_SAMPLE_RATE,chunks.sumOf{it.size}) }
 @Test fun forced_flush_keeps_short_audio() { val c=ContinuousChunker(); c.add(ShortArray(AUDIO_FRAME_SAMPLES){1}); assertEquals(AUDIO_FRAME_SAMPLES,c.flush()!!.size) }
 @Test fun timestamp_uses_pcm_offset() { assertEquals(Instant.ofEpochSecond(55),chunkStartForSampleOffset(Instant.EPOCH,55L*AUDIO_SAMPLE_RATE)) }
 @Test fun semantic_close_requires_target_and_five_second_pause_independent_of_chunk_cut() {
  val voice=ShortArray(AUDIO_FRAME_SAMPLES){10000}; val silence=ShortArray(AUDIO_FRAME_SAMPLES)
  val below=SemanticBlockAutoClose(targetSeconds=10,silenceSeconds=5, vad=EnergyVad(calibrationFrames=0)); repeat(4*50){assertFalse(below.add(voice))};repeat(5*50){assertFalse(below.add(silence))}
  val armed=SemanticBlockAutoClose(targetSeconds=1,silenceSeconds=5, vad=EnergyVad(calibrationFrames=0));repeat(50){assertFalse(armed.add(voice))};repeat(2*50){assertFalse(armed.add(silence))};repeat(3*50-1){assertFalse(armed.add(silence))};assertTrue(armed.add(silence))
  val hardCut=SemanticBlockAutoClose(targetSeconds=60,silenceSeconds=5, vad=EnergyVad(calibrationFrames=0));repeat(55*50){assertFalse(hardCut.add(voice))}
 }
 @Test fun waveform_values_are_normalized_and_ring_is_bounded() { assertEquals(0f,normalizedPcmAmplitude(shortArrayOf()),0f);assertEquals(1f,normalizedPcmAmplitude(shortArrayOf(Short.MIN_VALUE)),0f);var values=emptyList<Float>();repeat(120){values=appendAmplitude(values,2f,96)};assertEquals(96,values.size);assertTrue(values.all{it in 0f..1f}) }
 @Test fun wav_header_is_valid() { val f=File.createTempFile("wav",".wav"); WavWriter.write(f,shortArrayOf(1,2)); assertEquals("RIFF",String(f.readBytes(),0,4)); f.delete() }
}
