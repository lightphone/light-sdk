import com.thelightphone.sdk.server.toolmanager.accessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame

class AuthorizedToolDataViewTest {
    @Test
    fun `cancelling the caller does not return a Result`() = runBlocking {
        var returned = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            accessResult { awaitCancellation() }
            returned = true
        }
        caller.cancelAndJoin()
        assertFalse(returned)
    }

    @Test
    fun `provider failure is returned while the caller remains active`() = runBlocking {
        val cancellation = CancellationException("Provider cancelled its own operation")
        assertSame(cancellation, accessResult { throw cancellation }.exceptionOrNull())
        val failure = IllegalStateException("Provider failed")
        assertSame(failure, accessResult { throw failure }.exceptionOrNull())
    }
}
