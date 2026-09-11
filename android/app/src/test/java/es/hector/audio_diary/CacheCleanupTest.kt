package es.hector.audio_diary
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class CacheCleanupTest { @Test fun removes_only_capture_files(){val d=createTempDir();val c=File(d,"capture-old.m4a").apply{writeText("x")};val o=File(d,"keep.txt").apply{writeText("x")};clearAbandonedCaptures(d);assertFalse(c.exists());assertTrue(o.exists());d.deleteRecursively()} @Test fun missing_dir_is_safe(){clearAbandonedCaptures(File("/tmp/no-audio-tfm"))} }
