package com.thelightphone.sdk.emulator.backup

import com.thelightphone.backup.RemoteAccessTokenProvider
import com.thelightphone.backup.RemoteBackup
import com.thelightphone.sdk.emulator.http.EmulatorHttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.Instant

// Backs onto the file storage endpoints EmulatorHttpServer exposes under /files, instead of a
// real Google Drive/Dropbox/OneDrive account. Every call goes through the same Bearer-token gate
// as any other client of that server, tokenProvider is expected to be backed by
// EmulatorOAuthTunnelClient.
class EmulatorRemoteBackup(
    private val tokenProvider: RemoteAccessTokenProvider,
    override val rootFolderPath: String,
    private val baseUrl: String = "http://127.0.0.1:${EmulatorHttpServer.PORT}",
    private val httpClient: HttpClient = HttpClient(OkHttp)
) : RemoteBackup {

    private data class RemoteEntry(val name: String, val isDirectory: Boolean, val lastModified: Long)

    override suspend fun getMostRecentBackupDates(): Result<Map<String, Instant>> = runCatching {
        val labels = listSubdirectories(Path(rootFolderPath)).getOrThrow()

        val mostRecentByLabel = mutableMapOf<String, Instant>()
        // one label for tool::path
        for (label in labels) {
            val metaPath = Path(Path(rootFolderPath, label), META_FOLDER_NAME)
            // files in meta dir are named with timestamp, just sort to find newest
            val latestName = listFiles(metaPath).getOrThrow().maxOrNull() ?: continue
            val completedAt = parseMetaFileName(latestName)
                ?: throw IllegalStateException("unparsable _meta file name for $label: $latestName")
            mostRecentByLabel[label] = completedAt
        }
        mostRecentByLabel
    }

    override suspend fun createDirectory(path: Path): Result<Unit> = runCatching {
        val token = accessToken()
        val response = httpClient.post(fileUrl(path.toString())) {
            header(HttpHeaders.Authorization, token)
        }
        check(response.status.isSuccess()) { "createDirectory failed: ${response.status}: ${response.bodyAsText()}" }
    }

    override suspend fun uploadFile(remoteDirectory: Path, fileName: String, data: InputStream): Result<Unit> =
        runCatching {
            val token = accessToken()
            // stream it
            val response = httpClient.put(fileUrl(joinPath(remoteDirectory.toString(), fileName))) {
                header(HttpHeaders.Authorization, token)
                setBody(data.toByteReadChannel())
            }
            check(response.status.isSuccess()) { "uploadFile failed: ${response.status}: ${response.bodyAsText()}" }
        }

    override suspend fun listFiles(remoteDirectory: Path): Result<List<String>> = runCatching {
        listEntries(remoteDirectory).getOrThrow().filter { !it.isDirectory }.map { it.name }
    }

    override suspend fun listSubdirectories(remoteDirectory: Path): Result<List<String>> = runCatching {
        listEntries(remoteDirectory).getOrThrow().filter { it.isDirectory }.map { it.name }
    }

    override suspend fun downloadFile(remoteDirectory: Path, fileName: String): Result<InputStream> = runCatching {
        val token = accessToken()
        val response = httpClient.get(fileUrl(joinPath(remoteDirectory.toString(), fileName))) {
            header(HttpHeaders.Authorization, token)
        }
        check(response.status.isSuccess()) { "downloadFile failed: ${response.status}: ${response.bodyAsText()}" }
        ByteArrayInputStream(response.bodyAsBytes())
    }

    private suspend fun listEntries(path: Path): Result<List<RemoteEntry>> = runCatching {
        val token = accessToken()
        val response = httpClient.get(fileUrl(path.toString())) {
            header(HttpHeaders.Authorization, token)
        }
        // only downloadFile needs to treat a missing path as a real failure.
        if (response.status == HttpStatusCode.NotFound) {
            return@runCatching emptyList()
        }
        val bodyText = response.bodyAsText()
        check(response.status.isSuccess()) { "list failed: ${response.status}: $bodyText" }
        Json.parseToJsonElement(bodyText).jsonArray.map { element ->
            val entry = element.jsonObject
            RemoteEntry(
                name = entry["name"]!!.jsonPrimitive.content,
                isDirectory = entry["isDirectory"]!!.jsonPrimitive.boolean,
                lastModified = entry["lastModified"]!!.jsonPrimitive.long
            )
        }
    }

    private suspend fun accessToken(): String = "Bearer ${tokenProvider.getAccessToken().getOrThrow()}"

    private fun fileUrl(path: String): String {
        val normalized = if (path.isEmpty() || path == ".") "" else "/$path"
        return "$baseUrl/files$normalized"
    }

    private fun joinPath(a: String, b: String) = if (a.isEmpty() || a == ".") b else "$a/$b"

    // Mirrors the backup library's internal parseMetaFileName/metaFileNameFor (RemoteBackup.kt) -
    // a meta file name is a fixed-width "yyyy-MM-ddTHH-mm-ssZ" timestamp (colons swapped for
    // dashes so it's filesystem-safe) followed by "-" and a random hex suffix for uniqueness.
    private fun parseMetaFileName(name: String): Instant? = runCatching {
        val (datePart, timePart) = name.take(META_TIMESTAMP_LENGTH).split("T", limit = 2)
        Instant.parse("${datePart}T${timePart.removeSuffix("Z").replace('-', ':')}Z")
    }.getOrNull()

    companion object {
        private const val META_FOLDER_NAME = "_meta"
        private const val META_TIMESTAMP_LENGTH = 20 // "yyyy-MM-ddTHH-mm-ssZ"
    }
}
