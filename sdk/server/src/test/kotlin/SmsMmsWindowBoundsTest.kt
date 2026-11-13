import com.thelightphone.sdk.server.backup.parseWindowBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class SmsMmsWindowBoundsTest {
    @Test
    fun parsesPositiveBounds() {
        val (lower, upper) = parseWindowBounds("1000-2000")
        assertEquals(Instant.fromEpochMilliseconds(1000), lower)
        assertEquals(Instant.fromEpochMilliseconds(2000), upper)
    }

    // a message dated 0 puts the first window before the epoch
    @Test
    fun parsesNegativeLowerBound() {
        val (lower, upper) = parseWindowBounds("-86400000-0")
        assertEquals(Instant.fromEpochMilliseconds(-86400000), lower)
        assertEquals(Instant.fromEpochMilliseconds(0), upper)
    }

    @Test
    fun parsesNegativeBothBounds() {
        val (lower, upper) = parseWindowBounds("-172800000--86400000")
        assertEquals(Instant.fromEpochMilliseconds(-172800000), lower)
        assertEquals(Instant.fromEpochMilliseconds(-86400000), upper)
    }

    @Test
    fun rejectsMalformedBounds() {
        assertFailsWith<IllegalArgumentException> { parseWindowBounds("") }
        assertFailsWith<IllegalArgumentException> { parseWindowBounds("1000") }
        assertFailsWith<IllegalArgumentException> { parseWindowBounds("a-b") }
    }
}
