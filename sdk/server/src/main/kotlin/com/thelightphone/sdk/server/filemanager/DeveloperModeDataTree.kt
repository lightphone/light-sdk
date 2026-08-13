package com.thelightphone.sdk.server.filemanager

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.thelightphone.filemanager.BranchView
import com.thelightphone.filemanager.CustomSpec
import com.thelightphone.filemanager.DropboxSpec
import com.thelightphone.filemanager.EntryType
import com.thelightphone.filemanager.FileBrowserSpec
import com.thelightphone.filemanager.KeyCipher
import com.thelightphone.filemanager.LeafView
import com.thelightphone.filemanager.LeafViewSpec
import com.thelightphone.filemanager.RootViewSpec
import com.thelightphone.filemanager.datatree.CustomDataTree
import com.thelightphone.filemanager.datatree.EncryptingDataTree
import com.thelightphone.filemanager.datatree.FileDataTree
import com.thelightphone.filemanager.datatree.StaticBranchProvider
import com.thelightphone.filemanager.datatree.entryTypeForName
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.server.installer.LightApkInboxScanWorker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.file.Path

private const val TAG = "ApkInboxDataTree"
class ApkInboxDataTree(root: File, private val context: Context) : FileDataTree(root, emptyMap()) {
    override suspend fun notify(directoryPath: Path) {
        super.notify(directoryPath)
        Log.d(TAG, "file manager path notified: $directoryPath")
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

class ApkInboxMetaDataTree(private val context: Context) : CustomDataTree() {
    private val json  = Json {
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

fun developerModeDataView(
    context: Context,
    cipher: KeyCipher,
    inboxSpec: LeafViewSpec = DropboxSpec("Upload Tools", "apkInbox", headerText = "Use the button below to upload tools. If they meet your selected security settings, they'll be installed shortly after upload.", buttonText = "Choose APK to Install"),
    signatureSpec: LeafViewSpec = FileBrowserSpec("Tool Signing Keys", "signingKeys", headerText = "You can allowlist specific signing keys, so you don't have to blow a hole in LightOS' security to install an in-progress tool.\n\nUpload a .txt file with your key's public hash as a hexidecimal string. You can upload as many as you want, but LightOS will only read one per file."),
    authSpec: LeafViewSpec = FileBrowserSpec("Authentication", "clientKeys", headerText = "Each file represents a persisted authentication key, which can be used to access the File Manager's API endpoints (to upload/download files, etc.). The files are encrypted on disk.\n\nIf you want a client (i.e. your dev machine) to always have access to the File Manager's API endpoints, you can upload a plaintext file with a key here. It will remain until you delete it. Keys minted by the server may also exist here - these will expire automatically, but feel free to delete them.")
): BranchView {
    val inboxDir = LightSdkServer.getApkInboxDirectory(context)
    val authDir = LightSdkServer.getApkInboxAuthDirectory(context)
    val signaturesDir = LightSdkServer.getApkInboxSignaturesDirectory(context)
    val inboxView = LeafView(
        spec = inboxSpec,
        provider = ApkInboxDataTree(inboxDir, context)
    )
    val signatureView = LeafView(
        spec = signatureSpec,
        provider = SigningKeyDataTree(signaturesDir)
    )
    val authView = LeafView(
        spec = authSpec,
        provider = EncryptingDataTree(authDir, cipher)
    )
    val metaView = LeafView(
        spec = CustomSpec("toolMeta", "toolMeta"),
        provider = ApkInboxMetaDataTree(context)
    )
    return BranchView(
        RootViewSpec("Developer", "developer"),
        StaticBranchProvider(listOf(inboxView, signatureView, authView, metaView))
    )
}