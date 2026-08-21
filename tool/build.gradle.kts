import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
    testImplementation(libs.kotlin.test)
    ksp(libs.androidx.room.compiler)
}

/**
 * Uploads the debug APK to a device/emulator running the Light SDK server, via the
 * "developer" file manager branch (see ApkInboxDataTree.kt in sdk/server), and waits for
 * the device to report that the tool was (re)installed.
 *
 * Usage:
 *   ./gradlew :tool:uploadTool -Pdevice.host=192.168.1.42 -Pdevice.token=<bearer token> \
 *       [-Pdevice.port=54449] [-Pdevice.timeoutSeconds=60]
 */
abstract class UploadToolTask : DefaultTask() {

    @get:Internal
    abstract val apkDir: DirectoryProperty

    @get:Internal
    abstract val packageName: Property<String>

    @get:Internal
    abstract val host: Property<String>

    @get:Internal
    abstract val port: Property<Int>

    @get:Internal
    abstract val token: Property<String>

    @get:Internal
    abstract val timeoutSeconds: Property<Long>

    @get:Internal
    abstract val pollIntervalSeconds: Property<Long>

    // The server presents a cert for its own *.my.local-ip.co hostname (see
    // ToolManagerServiceAndroid), which will never match when we connect straight to a LAN
    // IP/port. Trusting everything here is fine: this is a local dev-upload tool talking
    // to a device the caller already picked by IP, not a general-purpose HTTP client.
    private fun insecureHttpClient(): HttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        val sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "" }
        return HttpClient.newBuilder()
            .sslContext(sslContext)
            .sslParameters(sslParameters)
            .build()
    }

    private fun baseUrl() = "https://${host.get()}:${port.get()}"

    private fun authedRequest(uri: URI): HttpRequest.Builder =
        HttpRequest.newBuilder(uri).header("Authorization", "Bearer ${token.get()}")

    // Server serializes the tools list as {"tools":[{"packageName":"...","lastUpdateMillis":n},...]}
    // in declaration order (ApkInboxDataTree.ToolMeta). A build script doesn't get the
    // kotlinx-serialization compiler plugin applied, so rather than pull one in just for
    // this, match the known shape directly.
    private fun extractLastUpdateMillis(json: String, packageName: String): Long? {
        val pattern = Regex(
            "\\{\"packageName\":\"${Regex.escape(packageName)}\",\"lastUpdateMillis\":(\\d+)\\}"
        )
        return pattern.find(json)?.groupValues?.get(1)?.toLong()
    }

    @TaskAction
    fun upload() {
        val missing = listOfNotNull(
            "device.host".takeUnless { host.isPresent },
            "device.token".takeUnless { token.isPresent },
        )
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing required propert${if (missing.size == 1) "y" else "ies"}: " +
                    missing.joinToString(", ") { "-P$it=..." }
            )
        }

        val apk = apkDir.get().asFileTree.files.filter { it.extension == "apk" }.let { apks ->
            apks.singleOrNull()
                ?: throw GradleException("Expected exactly one .apk in ${apkDir.get()}, found: $apks")
        }
        val pkg = packageName.get()
        val client = insecureHttpClient()
        val toolMetaUri = URI("${baseUrl()}/api/data/developer/toolMeta")

        fun fetchLastUpdateMillis(): Long? {
            val response = client.send(authedRequest(toolMetaUri).GET().build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) {
                "Failed to query toolMeta: HTTP ${response.statusCode()} - ${response.body()}"
            }
            return extractLastUpdateMillis(response.body(), pkg)
        }

        logger.lifecycle("Checking current install state of $pkg on ${host.get()}:${port.get()}...")
        val before = fetchLastUpdateMillis()
        logger.lifecycle(
            if (before == null) "$pkg is not currently installed on the device."
            else "$pkg is currently installed, last updated at $before."
        )

        val uploadFileName = "$pkg.apk"
        logger.lifecycle("Uploading ${apk.name} (${apk.length()} bytes) as $uploadFileName...")
        val uploadResponse = client.send(
            authedRequest(URI("${baseUrl()}/api/upload/developer/apkInbox/$uploadFileName"))
                .POST(HttpRequest.BodyPublishers.ofFile(apk.toPath()))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(uploadResponse.statusCode() == 200) {
            "Upload failed: HTTP ${uploadResponse.statusCode()} - ${uploadResponse.body()}"
        }

        val notifyResponse = client.send(
            authedRequest(URI("${baseUrl()}/api/notify/developer/apkInbox/$uploadFileName"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(notifyResponse.statusCode() == 200) {
            "Notify failed: HTTP ${notifyResponse.statusCode()} - ${notifyResponse.body()}"
        }

        logger.lifecycle("Uploaded. Waiting for the device to install/update $pkg...")
        val deadlineMs = System.currentTimeMillis() + timeoutSeconds.get() * 1000
        val pollIntervalMs = pollIntervalSeconds.get() * 1000
        while (true) {
            Thread.sleep(pollIntervalMs)
            val after = fetchLastUpdateMillis()
            if (after != null && after != before) {
                logger.lifecycle("$pkg installed/updated (lastUpdateMillis=$after).")
                return
            }
            if (System.currentTimeMillis() >= deadlineMs) {
                throw GradleException(
                    "Timed out after ${timeoutSeconds.get()}s waiting for $pkg to install/update on the device."
                )
            }
        }
    }
}

tasks.register<UploadToolTask>("uploadTool") {
    group = "light sdk"
    description = "Uploads the debug APK to a Light SDK server and waits for it to install/update."
    dependsOn("assembleDebug")

    apkDir.set(layout.buildDirectory.dir("outputs/apk/debug"))
    packageName.set(requireNotNull(android.defaultConfig.applicationId) { "applicationId is not set" })
    host.set(providers.gradleProperty("device.host").orElse("127-0-0-1.my.local-ip.co"))
    port.set(providers.gradleProperty("device.port").map { it.toInt() }.orElse(54449))
    token.set(providers.gradleProperty("device.token").orElse("testKeyPassword"))
    timeoutSeconds.set(providers.gradleProperty("device.timeoutSeconds").map { it.toLong() }.orElse(60L))
    pollIntervalSeconds.set(2L)
}
