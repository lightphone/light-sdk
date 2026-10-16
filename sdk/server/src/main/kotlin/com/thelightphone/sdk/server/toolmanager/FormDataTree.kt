package com.thelightphone.sdk.server.toolmanager

import com.thelightphone.toolmanager.FormSubmission
import com.thelightphone.toolmanager.datatree.CustomDataTree
import com.thelightphone.toolmanager.datatree.WriteCheck
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

// Pairs with the tool manager's FormSpec.
abstract class FormDataTree : CustomDataTree() {

    abstract suspend fun onSubmit(responses: Map<String, String>): Result<Unit>

    override suspend fun getBytes(filePath: Path): Result<InputStream> =
        Result.failure(SecurityException("Cannot read from form data tree"))

    override suspend fun checkWrite(filePath: Path): WriteCheck = WriteCheck.Safe

    override suspend fun <T> writeBytes(filePath: Path, block: suspend (OutputStream) -> T): Result<T> = runCatching {
        val buffer = ByteArrayOutputStream()
        val blockResult = block(buffer)
        val submission = formJson.decodeFromString(FormSubmission.serializer(), buffer.toString(Charsets.UTF_8))
        onSubmit(submission.responses.associate { it.item.id to it.response }).getOrThrow()
        blockResult
    }

    private companion object {
        val formJson = Json { ignoreUnknownKeys = true }
    }
}
