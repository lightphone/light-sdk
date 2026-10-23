import com.thelightphone.sdk.server.toolmanager.FormDataTree
import com.thelightphone.toolmanager.FormItem
import com.thelightphone.toolmanager.FormResponse
import com.thelightphone.toolmanager.FormSubmission
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingFormDataTree(
    private val result: Result<Unit> = Result.success(Unit),
) : FormDataTree() {
    val submissions = mutableListOf<Map<String, String>>()

    override suspend fun onSubmit(responses: Map<String, String>): Result<Unit> {
        submissions += responses
        return result
    }
}

private fun submissionBytes(vararg responses: Pair<String, String>): ByteArray {
    val submission = FormSubmission(responses.map { (id, value) -> FormResponse(FormItem(id, id), value) })
    return Json.encodeToString(FormSubmission.serializer(), submission).encodeToByteArray()
}

class FormDataTreeTest {
    private val uploadPath = Path.of("form_1.json")

    @Test
    fun `submission is decoded into responses keyed by item id`() = runBlocking {
        val tree = RecordingFormDataTree()
        val bytes = submissionBytes("username" to "jane", "password" to "secret")

        val result = tree.writeBytes(uploadPath) { it.write(bytes) }

        assertTrue(result.isSuccess)
        assertEquals(listOf(mapOf("username" to "jane", "password" to "secret")), tree.submissions)
    }

    @Test
    fun `failed onSubmit fails the upload`() = runBlocking {
        val tree = RecordingFormDataTree(result = Result.failure(IllegalArgumentException("bad creds")))

        val result = tree.writeBytes(uploadPath) { it.write(submissionBytes("username" to "jane")) }

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed upload fails without calling onSubmit`() = runBlocking {
        val tree = RecordingFormDataTree()

        val result = tree.writeBytes(uploadPath) { it.write("not json".encodeToByteArray()) }

        assertTrue(result.isFailure)
        assertTrue(tree.submissions.isEmpty())
    }
}
