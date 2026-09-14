package com.thelightphone.sdk.emulator.http

import android.content.Context
import android.util.Log
import com.thelightphone.sdk.emulator.EmulatorLocationHelper
import com.thelightphone.sdk.server.BuildConfig
import com.thelightphone.sdk.server.LightPushDistributor
import com.thelightphone.sdk.server.LightPushRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.time.Clock
import io.ktor.server.application.ApplicationCall
import io.ktor.utils.io.jvm.javaio.copyTo
import androidx.core.content.edit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * A place to dump otherwise cloud-based features.
 */
class EmulatorHttpServer(private val context: Context) {

    companion object {
        private const val TAG = "EmulatorHttpServer"
        const val PORT = 8090

        // Dev-only shared secret gating the OAuth password screen below. This server only
        // binds to the emulator's local network, so a static password is an acceptable
        // trade-off for the minimal auth flow.
        private const val OAUTH_PASSWORD = "emulator-dev-password"
        private const val AUTH_CODE_TTL_MS = 60_000L
        private const val ACCESS_TOKEN_TTL_MS = 3_600_000L
        private const val REFRESH_TOKEN_TTL_MS = 30L * 24 * 3_600_000L

        private const val TOKENS_PREFS_NAME = "emulator_http_server_tokens"
        private const val PREF_ACCESS_TOKENS = "access_tokens"
        private const val PREF_REFRESH_TOKENS = "refresh_tokens"
    }

    private data class IssuedCode(val redirectUri: String, val expiresAt: Long)
    private data class IssuedToken(val expiresAt: Long)

    private val tokenPrefs = context.getSharedPreferences(TOKENS_PREFS_NAME, Context.MODE_PRIVATE)

    private val authCodes = ConcurrentHashMap<String, IssuedCode>()
    private val accessTokens = ConcurrentHashMap<String, IssuedToken>().apply {
        loadTokenMap(PREF_ACCESS_TOKENS).forEach { (token, expiresAt) -> put(token, IssuedToken(expiresAt)) }
    }
    private val refreshTokens = ConcurrentHashMap<String, Long>().apply {
        putAll(loadTokenMap(PREF_REFRESH_TOKENS))
    }

    private fun loadTokenMap(key: String): Map<String, Long> {
        val raw = tokenPrefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.long }
        }.getOrDefault(emptyMap())
    }

    private fun persistTokens() {
        tokenPrefs.edit {
            putString(
                PREF_ACCESS_TOKENS,
                buildJsonObject { accessTokens.forEach { (token, issued) -> put(token, issued.expiresAt) } }.toString()
            )
            putString(
                PREF_REFRESH_TOKENS,
                buildJsonObject { refreshTokens.forEach { (token, expiresAt) -> put(token, expiresAt) } }.toString()
            )
        }
    }

    private val filesRoot: File by lazy {
        File(context.filesDir, "fileserver").apply { mkdirs() }
    }

    private fun resolveFile(relativePath: String): File {
        val root = filesRoot.canonicalPath
        val file = File(filesRoot, relativePath).canonicalFile
        if (file.path != root && !file.path.startsWith(root + File.separator)) {
            throw SecurityException("Path traversal not allowed")
        }
        return file
    }

    private fun generateOpaqueToken(): String = UUID.randomUUID().toString()

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun passwordPageHtml(redirectUri: String, state: String?, error: Boolean): String = """
        <!DOCTYPE html>
        <html>
        <head><title>Emulator Login</title></head>
        <body>
            <h2>Emulator File Server Login</h2>
            We're all friends here... the password is $OAUTH_PASSWORD :)
            ${if (error) "<p style=\"color:red\">Incorrect password</p>" else ""}
            <form method="post" action="/oauth/authorize">
                <input type="hidden" name="redirect_uri" value="${redirectUri.htmlEscape()}" />
                <input type="hidden" name="state" value="${(state ?: "").htmlEscape()}" />
                <input type="password" name="password" placeholder="Password" autofocus />
                <button type="submit">Sign in</button>
            </form>
        </body>
        </html>
    """.trimIndent()

    private val server = embeddedServer(Netty, port = PORT) {
        install(ContentNegotiation) { json() }

        // Ktor's own unhandled-exception logging goes through SLF4J, which has no Android
        // backend configured here, so it silently no-ops - nothing shows up in logcat by
        // default. This makes sure a route handler crash is both visible in logcat and
        // surfaced in the response body, instead of Ktor's default bare 500 with no detail.
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Log.e(TAG, "Unhandled exception handling ${call.request.httpMethod.value} ${call.request.uri}", cause)
                call.respondText(
                    cause.stackTraceToString(),
                    status = HttpStatusCode.InternalServerError
                )
            }
        }

        install(Authentication) {
            bearer("files-auth") {
                realm = "Emulator File Server"
                authenticate { credentials ->
                    val now = System.currentTimeMillis()
                    val issued = accessTokens[credentials.token]
                    if (issued != null && issued.expiresAt > now) {
                        UserIdPrincipal(credentials.token)
                    } else {
                        null
                    }
                }
            }
        }

        routing {
            get("/version") {
                call.respondText(BuildConfig.SDK_VERSION, status = HttpStatusCode.OK)
            }
            post("/push/{token}") {
                val token = call.parameters["token"]
                if (token == null) {
                    call.respondText("Missing token", status = HttpStatusCode.BadRequest)
                    return@post
                }
                val registration = LightPushRegistry.getByToken(context, token)
                if (registration == null) {
                    call.respondText("Unknown token", status = HttpStatusCode.NotFound)
                    return@post
                }
                val body = call.receiveText()
                Log.d(TAG, "Received push for ${registration.packageName}: $body")
                LightPushDistributor.sendMessage(
                    context,
                    registration.packageName,
                    token,
                    body.toByteArray()
                )
                call.respondText("OK", status = HttpStatusCode.OK)
            }
            post("/location/default") {
                val latitude = call.request.queryParameters["latitude"]?.toDoubleOrNull()
                val longitude = call.request.queryParameters["longitude"]?.toDoubleOrNull()
                if (latitude == null || longitude == null) {
                    EmulatorLocationHelper.updateDefaultLocation(null)
                    call.respondText("Cleared default location", status = HttpStatusCode.OK)
                    return@post
                }
                EmulatorLocationHelper.updateDefaultLocation(
                    EmulatorLocationHelper.Location(latitude, longitude)
                )
                call.respondText("OK", status = HttpStatusCode.OK)
            }
            post("/location/current") {
                val latitude = call.request.queryParameters["latitude"]?.toDoubleOrNull()
                val longitude = call.request.queryParameters["longitude"]?.toDoubleOrNull()
                if (latitude == null || longitude == null) {
                    EmulatorLocationHelper.updateCurrentLocation(null)
                    call.respondText("Cleared current location", status = HttpStatusCode.OK)
                    return@post
                }
                val accuracyMeters =
                    call.request.queryParameters["accuracyMeters"]?.toDoubleOrNull() ?: 0.0
                val updated = EmulatorLocationHelper.updateCurrentLocation(
                    EmulatorLocationHelper.CurrentLocation(
                        latitude,
                        longitude,
                        accuracyMeters,
                        Clock.System.now()
                    )
                )
                if (updated) {
                    call.respondText("OK", status = HttpStatusCode.OK)
                } else {
                    call.respondText(
                        "No tools are currently polling for location updates",
                        status = HttpStatusCode.Conflict
                    )
                }
            }

            get("/oauth/authorize") {
                val redirectUri = call.request.queryParameters["redirect_uri"]
                if (redirectUri == null) {
                    call.respondText("Missing redirect_uri", status = HttpStatusCode.BadRequest)
                    return@get
                }
                val state = call.request.queryParameters["state"]
                call.respondText(passwordPageHtml(redirectUri, state, error = false), ContentType.Text.Html)
            }

            post("/oauth/authorize") {
                val params = call.receiveParameters()
                val redirectUri = params["redirect_uri"]
                if (redirectUri == null) {
                    call.respondText("Missing redirect_uri", status = HttpStatusCode.BadRequest)
                    return@post
                }
                val state = params["state"]
                if (params["password"] != OAUTH_PASSWORD) {
                    call.respondText(
                        passwordPageHtml(redirectUri, state, error = true),
                        ContentType.Text.Html,
                        HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val now = System.currentTimeMillis()
                authCodes.entries.removeAll { it.value.expiresAt < now }
                val code = generateOpaqueToken()
                authCodes[code] = IssuedCode(redirectUri, now + AUTH_CODE_TTL_MS)
                val separator = if (redirectUri.contains("?")) "&" else "?"
                val location = buildString {
                    append(redirectUri)
                    append(separator)
                    append("code=").append(code)
                    if (!state.isNullOrEmpty()) append("&state=").append(state)
                }
                call.respondRedirect(location)
            }

            post("/oauth/token") {
                val params = call.receiveParameters()
                val now = System.currentTimeMillis()
                when (params["grant_type"]) {
                    "authorization_code" -> {
                        val code = params["code"]
                        if (code == null) {
                            call.respondOAuthError(HttpStatusCode.BadRequest, "invalid_request")
                            return@post
                        }
                        val issued = authCodes.remove(code)
                        if (issued == null || issued.expiresAt < now || issued.redirectUri != params["redirect_uri"]) {
                            call.respondOAuthError(HttpStatusCode.BadRequest, "invalid_grant")
                            return@post
                        }
                        call.respondTokenResponse(issueAccessToken(now))
                    }
                    "refresh_token" -> {
                        val refreshToken = params["refresh_token"]
                        val expiresAt = refreshToken?.let { refreshTokens[it] }
                        if (refreshToken == null || expiresAt == null || expiresAt < now) {
                            call.respondOAuthError(HttpStatusCode.BadRequest, "invalid_grant")
                            return@post
                        }
                        call.respondTokenResponse(issueAccessToken(now, existingRefreshToken = refreshToken))
                    }
                    else -> call.respondOAuthError(HttpStatusCode.BadRequest, "invalid_request")
                }
            }

            // acts as a cloud storage provider to test backups
            authenticate("files-auth") {
                route("/files") {
                    get {
                        handleGetFiles(call, "")
                    }
                    get("/{path...}") {
                        handleGetFiles(call, call.parameters.getAll("path")?.joinToString("/") ?: "")
                    }
                    post("/{path...}") {
                        val relativePath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                        val file = try {
                            resolveFile(relativePath)
                        } catch (e: SecurityException) {
                            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
                            return@post
                        }
                        if (file.isFile) {
                            call.respondText("A file already exists at this path", status = HttpStatusCode.Conflict)
                            return@post
                        }
                        file.mkdirs()
                        call.respondText("OK")
                    }
                    put("/{path...}") {
                        val relativePath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                        val file = try {
                            resolveFile(relativePath)
                        } catch (e: SecurityException) {
                            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
                            return@put
                        }
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            call.receiveChannel().copyTo(output)
                        }
                        call.respondText("OK")
                    }
                    delete("/{path...}") {
                        val relativePath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                        val file = try {
                            resolveFile(relativePath)
                        } catch (e: SecurityException) {
                            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
                            return@delete
                        }
                        if (file.isFile) file.delete()
                        call.respondText("OK")
                    }
                }
            }
        }
    }

    private suspend fun handleGetFiles(call: ApplicationCall, relativePath: String) {
        val file = try {
            resolveFile(relativePath)
        } catch (e: SecurityException) {
            call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        if (!file.exists()) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return
        }
        if (file.isDirectory) {
            val entries = buildJsonArray {
                file.listFiles()?.forEach { child ->
                    add(
                        buildJsonObject {
                            put("name", child.name)
                            put("isDirectory", child.isDirectory)
                            put("lastModified", child.lastModified())
                        }
                    )
                }
            }
            call.respondText(entries.toString(), ContentType.Application.Json)
        } else {
            call.respondFile(file)
        }
    }

    private fun issueAccessToken(now: Long, existingRefreshToken: String? = null): Pair<String, String> {
        accessTokens.entries.removeAll { it.value.expiresAt < now }
        refreshTokens.entries.removeAll { it.value < now }
        val accessToken = generateOpaqueToken()
        accessTokens[accessToken] = IssuedToken(now + ACCESS_TOKEN_TTL_MS)
        val refreshToken = existingRefreshToken ?: generateOpaqueToken().also {
            refreshTokens[it] = now + REFRESH_TOKEN_TTL_MS
        }
        persistTokens()
        return accessToken to refreshToken
    }

    private suspend fun ApplicationCall.respondTokenResponse(tokens: Pair<String, String>) {
        val (accessToken, refreshToken) = tokens
        respondText(
            buildJsonObject {
                put("access_token", accessToken)
                put("token_type", "Bearer")
                put("expires_in", ACCESS_TOKEN_TTL_MS / 1000)
                put("refresh_token", refreshToken)
                put("scope", "files")
            }.toString(),
            ContentType.Application.Json
        )
    }

    private suspend fun ApplicationCall.respondOAuthError(status: HttpStatusCode, error: String) {
        respondText(
            buildJsonObject { put("error", error) }.toString(),
            ContentType.Application.Json,
            status
        )
    }

    fun start() {
        server.start(wait = false)
        Log.i(TAG, "HTTP server started on port $PORT")
    }

    fun stop() {
        server.stop(1000, 2000)
        Log.i(TAG, "HTTP server stopped")
    }
}
