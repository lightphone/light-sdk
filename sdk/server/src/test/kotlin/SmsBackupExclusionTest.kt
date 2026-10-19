import com.thelightphone.sdk.server.backup.SmsBackupExclusion
import com.thelightphone.sdk.server.backup.applyTo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmsBackupExclusionTest {
    private val exclusion = SmsBackupExclusion("body = ? AND date = ?", listOf("placeholder", "9999"))

    @Test
    fun noExclusionLeavesQueryUnchanged() {
        val (selection, args) = (null as SmsBackupExclusion?).applyTo("date > ?", arrayOf("1"))
        assertEquals("date > ?", selection)
        assertContentEquals(arrayOf("1"), args)
    }

    @Test
    fun noExclusionAndNoSelectionStaysNull() {
        val (selection, args) = (null as SmsBackupExclusion?).applyTo(null, emptyArray())
        assertNull(selection)
        assertContentEquals(emptyArray(), args)
    }

    @Test
    fun exclusionIsAndedOntoSelection() {
        val (selection, args) = exclusion.applyTo("date > ? AND date <= ?", arrayOf("1", "2"))
        assertEquals("(date > ? AND date <= ?) AND NOT (body = ? AND date = ?)", selection)
        // the query's own args come first, matching placeholder order
        assertContentEquals(arrayOf("1", "2", "placeholder", "9999"), args)
    }

    @Test
    fun exclusionAloneWhenNoSelection() {
        val (selection, args) = exclusion.applyTo(null, emptyArray())
        assertEquals("NOT (body = ? AND date = ?)", selection)
        assertContentEquals(arrayOf("placeholder", "9999"), args)
    }
}
