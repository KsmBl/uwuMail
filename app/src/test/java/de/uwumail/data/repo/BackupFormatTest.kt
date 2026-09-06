package de.uwumail.data.repo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a file is one of ours at all, and what it claims to hold. This is
 * the same check the import path runs before it writes anything.
 */
class BackupFormatTest {

    @Test
    fun `a file from somewhere else is refused rather than half read`() {
        assertNull(BackupRepository.inspect("""{"some":"other file"}"""))
        assertNull(BackupRepository.inspect("not json at all"))
        assertNull(BackupRepository.inspect(""))
    }

    @Test
    fun `a format from a later version is refused rather than guessed at`() {
        val ahead = JSONObject().put("format", BackupRepository.FORMAT + 1).toString()
        assertNull(BackupRepository.inspect(ahead))
    }

    @Test
    fun `a backup reports what it holds`() {
        val json = JSONObject()
            .put("format", BackupRepository.FORMAT)
            .put("rules", JSONArray().put(JSONObject()).put(JSONObject()))
            .put("settings", JSONObject())
            .toString()
        val contents = BackupRepository.inspect(json)!!
        assertEquals(2, contents.rules)
        assertTrue(contents.hasSettings)
    }

    @Test
    fun `a backup with no settings says so instead of pretending`() {
        val json = JSONObject()
            .put("format", BackupRepository.FORMAT)
            .put("rules", JSONArray())
            .toString()
        val contents = BackupRepository.inspect(json)!!
        assertEquals(0, contents.rules)
        assertEquals(false, contents.hasSettings)
    }
}
