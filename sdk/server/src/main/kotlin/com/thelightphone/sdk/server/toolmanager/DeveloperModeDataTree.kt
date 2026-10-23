package com.thelightphone.sdk.server.toolmanager

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.thelightphone.toolmanager.BranchView
import com.thelightphone.toolmanager.CustomSpec
import com.thelightphone.toolmanager.UploadSpec
import com.thelightphone.toolmanager.EntryType
import com.thelightphone.toolmanager.FileBrowserSpec
import com.thelightphone.toolmanager.KeyCipher
import com.thelightphone.toolmanager.LeafView
import com.thelightphone.toolmanager.LeafViewSpec
import com.thelightphone.toolmanager.RootViewSpec
import com.thelightphone.toolmanager.datatree.CustomDataTree
import com.thelightphone.toolmanager.datatree.EncryptingDataTree
import com.thelightphone.toolmanager.datatree.FileDataTree
import com.thelightphone.toolmanager.datatree.StaticBranchProvider
import com.thelightphone.toolmanager.datatree.entryTypeForName
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.server.installer.LightApkInboxScanWorker
import com.thelightphone.sdk.server.installer.LightApkUninstallWorker
import com.thelightphone.toolmanager.FormItem
import com.thelightphone.toolmanager.FormSpec
import com.thelightphone.toolmanager.FormSubmission
import com.thelightphone.toolmanager.datatree.WriteCheck
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

private const val TAG = "DeveloperModeDataTree"

class ApkInboxDataTree(root: File, private val context: Context) : FileDataTree(root, emptyMap()) {
    override suspend fun notify(directoryPath: Path) {
        super.notify(directoryPath)
        Log.d(TAG, "tool manager path notified: $directoryPath")
        LightApkInboxScanWorker.enqueue(context)
        invalidateParentCache(directoryPath)
    }

    override fun validateFile(
        targetPath: Path,
        tempFile: Path
    ): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageArchiveInfo(
                tempFile.toString(),
                PackageManager.GET_ACTIVITIES
            )
            packageInfo != null
        } catch (e: Exception) {
            Log.e(TAG, "Error validating APK", e)
            false
        }
    }
}

private val SIGNING_KEY_HASH_REGEX = Regex("^[0-9a-fA-F]{64}$")

class SigningKeyDataTree(root: File) : FileDataTree(root, emptyMap()) {
    override suspend fun notify(directoryPath: Path) {
        super.notify(directoryPath)
        // valid signatures have changed, so tell the server to rescan apks
        LightSdkServer.onApkInstalled(null)
    }

    override fun validateFile(
        targetPath: Path,
        tempFile: Path
    ): Boolean {
        if (entryTypeForName(targetPath.fileName.toString()) != EntryType.Text) {
            return false
        }
        val firstLine = tempFile.toFile().readSigningKeyHash() ?: return false
        return SIGNING_KEY_HASH_REGEX.matches(firstLine.trim())
    }
}

class UninstallRequestDataTree(private val context: Context) : CustomDataTree() {

    override suspend fun getBytes(filePath: Path): Result<InputStream> =
        Result.failure(SecurityException("Cannot read from form data tree"))

    override suspend fun checkWrite(filePath: Path): WriteCheck = WriteCheck.Safe

    override suspend fun <T> writeBytes(filePath: Path, block: suspend (OutputStream) -> T): Result<T> = runCatching {
        val buffer = ByteArrayOutputStream()
        val blockResult = block(buffer)
        val submission = formJson.decodeFromString(FormSubmission.serializer(), buffer.toString(Charsets.UTF_8))
        val packageToUninstall =
            submission.responses.firstOrNull { it.item.id == UNINSTALL_FORM_ITEM }?.response?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("No package name provided.")
        if (!context.isPackageInstalled(packageToUninstall)) {
            throw NoSuchElementException("$packageToUninstall is not installed.")
        }
        // ALWAYS forbid uninstalling the package running tool manager
        if (context.packageName == packageToUninstall ||
            !LightSdkServer.canBeUninstalledByToolManager(packageToUninstall)
        ) throw SecurityException("$packageToUninstall cannot be uninstalled.")

        LightApkUninstallWorker.enqueue(context, packageToUninstall)
        blockResult
    }

    private fun Context.isPackageInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        val formJson = Json { ignoreUnknownKeys = true }
    }
}

class AuthKeyDataTree(root: File, cipher: KeyCipher) : EncryptingDataTree(root, cipher) {
    override suspend fun notify(directoryPath: Path) {
        super.notify(directoryPath)
        ToolManagerLifecycleWrapperService.refreshAuth()
    }
}

class ApkInboxMetaDataTree(private val context: Context) : CustomDataTree() {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Serializable
    data class ToolMeta(val packageName: String, val lastUpdateMillis: Long)

    @Serializable
    data class ToolMetaResponse(val tools: List<ToolMeta>)

    override suspend fun getBytes(filePath: Path): Result<InputStream> {
        val response = with(LightSdkServer) {
            context.queryInstalledClients()
                .map { ToolMeta(it.packageInfo.packageName, it.packageInfo.lastUpdateTime) }
                .let { ToolMetaResponse(it) }
        }
        return Result.success(json.encodeToString(response).byteInputStream())
    }
}

fun File.readSigningKeyHash(): String? {
    return try {
        useLines { it.first() }
    } catch (e: Exception) {
        Log.e(TAG, "Error reading signing key file", e)
        null
    }
}

fun LightSdkServer.getApkInboxSignaturesDirectory(context: Context): File {
    return getApkInboxDirectory(context).resolve("signatures").apply { mkdirs() }
}

fun LightSdkServer.getApkInboxAuthDirectory(context: Context): File {
    return getApkInboxDirectory(context).resolve("auth").apply { mkdirs() }
}

private const val UNINSTALL_FORM_ITEM = "uninstall_package"
private fun uninstallSpecFormItems() = listOf(
    FormItem(id = UNINSTALL_FORM_ITEM, label = "Package Name", required = true),
)

fun developerModeDataView(
    context: Context,
    cipher: KeyCipher,
    inboxSpec: LeafViewSpec = UploadSpec(
        "Upload Tools",
        "apkInbox",
        headerText = "Use the button below to upload tools. If they meet your selected security settings, they'll be installed shortly after upload.",
        buttonText = "Choose APK to Install"
    ),
    uninstallSpec: LeafViewSpec = FormSpec(
        "Uninstall Tools",
        "uninstallRequests",
        headerText = "Enter the package name of the tool you'd like to uninstall. If you're unsure of the package name, you can extract it from an APK using an online \"APK Analyzer\"",
        buttonText = "Uninstall",
        items = uninstallSpecFormItems()
    ),
    signatureSpec: LeafViewSpec = FileBrowserSpec(
        "Developer Keys",
        "developerSigningKeys",
        headerText = "You can allowlist specific signing keys, so you don't have to blow a hole in LightOS' security to install an in-progress tool.\n\nUpload a .txt file with your key's public hash as a hexadecimal string. You can upload as many as you want, but LightOS will only read one per file."
    ),
    authSpec: LeafViewSpec = FileBrowserSpec(
        "Authentication",
        "clientKeys",
        headerText = "Each file represents a persisted authentication key, which can be used to access the Tool Manager's API endpoints (to upload/download files, etc.). The files are encrypted on disk.\n\nIf you want a client (i.e. your dev machine) to always have access to the Tool Manager's API endpoints, you can upload a plaintext file with a key here. It will remain until you delete it. Keys minted by the server may also exist here - these will expire automatically, but feel free to delete them."
    )
): BranchView {
    val inboxDir = LightSdkServer.getApkInboxDirectory(context)
    val authDir = LightSdkServer.getApkInboxAuthDirectory(context)
    val signaturesDir = LightSdkServer.getApkInboxSignaturesDirectory(context)
    val inboxView = LeafView(
        spec = inboxSpec,
        provider = ApkInboxDataTree(inboxDir, context)
    )
    val uninstallView = LeafView(
        spec = uninstallSpec,
        provider = UninstallRequestDataTree(context)
    )
    val signatureView = LeafView(
        spec = signatureSpec,
        provider = SigningKeyDataTree(signaturesDir)
    )
    val authView = LeafView(
        spec = authSpec,
        provider = AuthKeyDataTree(authDir, cipher)
    )
    val metaView = LeafView(
        spec = CustomSpec("toolMeta", "toolMeta"),
        provider = ApkInboxMetaDataTree(context)
    )
    return BranchView(
        RootViewSpec("Developer", "developer"),
        StaticBranchProvider(listOf(inboxView, uninstallView, signatureView, authView, metaView))
    )
}