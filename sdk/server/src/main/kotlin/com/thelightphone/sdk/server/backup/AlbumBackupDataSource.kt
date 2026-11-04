package com.thelightphone.sdk.server.backup

import com.thelightphone.backup.BackupDataSource
import com.thelightphone.backup.BackupPath
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.toolmanager.Logger
import kotlinx.io.files.Path
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Backs up photos and videos from the directories provided by LightSdkServer.getAlbumPhotoBackupDirs
 * and getAlbumVideoBackupDirs.
 */
class AlbumBackupDataSource(
    private val logger: Logger,
) : BackupDataSource {

    private enum class Kind(val label: String) {
        Image("image"),
        Video("video");

        fun directories(): List<File> = when (this) {
            Image -> LightSdkServer.getAlbumPhotoBackupDirs()
            Video -> LightSdkServer.getAlbumVideoBackupDirs()
        }

        companion object {
            fun fromLabel(label: String) = entries.firstOrNull { it.label == label }
        }
    }

    override suspend fun getPathsToBackUp(): Result<List<BackupPath>> = runCatching {
        if (!LightSdkServer.canBackUpFromPackage(BackupCapableTool(AUTHORITY, true))) {
            return@runCatching emptyList()
        }
        Kind.entries
            .filter { it.directories().isNotEmpty() }
            .map { BackupPath(authority = AUTHORITY, localPath = Path(it.label), label = it.label) }
    }.onFailure { logger.reportError(TAG, it, "Failed to list backup paths") }

    override suspend fun getFilesToBackUpForPath(
        parent: Path,
        lowerBound: Instant,
        upperBound: Instant
    ): Result<List<Path>> = runCatching {
        val kind = kindFor(parent)
        val lowerMs = lowerBound.toEpochMilliseconds()
        val upperMs = upperBound.toEpochMilliseconds()
        kind.directories()
            .flatMap { dir -> filesIn(dir).filter { it.lastModified() in (lowerMs + 1)..upperMs }.map { dir to it } }
            .map { (dir, file) -> pathFor(kind, dir, file) }
            .also { if (it.isNotEmpty()) logger.log(TAG, "${it.size} ${kind.label} files to be backed up.") }
    }.onFailure { logger.reportError(TAG, it, "Failed to list files to back up for $parent") }

    override suspend fun getEarliestPossibleBackupDate(parent: Path): Result<Instant> = runCatching {
        val kind = kindFor(parent)
        val earliest = kind.directories()
            .flatMap { filesIn(it) }
            .minOfOrNull { it.lastModified() }
            ?.let { Instant.fromEpochMilliseconds(it) }
            ?: Clock.System.now()
        logger.log(TAG, "earliest possible ${kind.label} backup date: $earliest")
        earliest
    }.onFailure { logger.reportError(TAG, it, "Failed to determine earliest possible backup date for $parent") }

    override suspend fun readFile(path: Path): Result<InputStream> = runCatching<InputStream> {
        FileInputStream(resolve(path))
    }.onFailure { logger.reportError(TAG, it, "Failed to read file under ${labelOf(path)}") }

    override suspend fun hashForFile(path: Path): Result<String> = runCatching {
        FileInputStream(resolve(path)).digestHexString()
    }.onFailure { logger.reportError(TAG, it, "Failed to hash file under ${labelOf(path)}") }

    private fun kindFor(parent: Path): Kind =
        Kind.fromLabel(parent.toString()) ?: throw NoSuchElementException("Unknown backup path: $parent")

    // skip hidden files
    private fun filesIn(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && !file.name.startsWith(".") }?.toList() ?: emptyList()

    // "<kind label>/<dir tag>-<file name>"
    private fun pathFor(kind: Kind, dir: File, file: File): Path =
        Path("${kind.label}/${dirTag(dir)}-${file.name}")

    private fun resolve(path: Path): File {
        val segments = path.toString().split("/", limit = 2)
        val kind = Kind.fromLabel(segments[0]) ?: throw NoSuchElementException("Unknown backup path: $path")
        val (tag, name) = segments.getOrNull(1)?.split("-", limit = 2)
            ?.takeIf { it.size == 2 }
            ?: throw IllegalArgumentException("Malformed album backup path: $path")
        val dir = kind.directories().firstOrNull { dirTag(it) == tag }
            ?: throw NoSuchElementException("No ${kind.label} directory for tag $tag")
        return File(dir, name)
    }

    // never log file names, just where they were
    private fun labelOf(path: Path) = path.toString().substringBefore('/')

    private fun dirTag(dir: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(dir.absolutePath.toByteArray(Charsets.UTF_8))
            .toHexString()
            .take(TAG_LENGTH)

    companion object {
        private const val TAG = "AlbumBackupDataSource"
        const val AUTHORITY = "album"
        private const val TAG_LENGTH = 8
    }
}
