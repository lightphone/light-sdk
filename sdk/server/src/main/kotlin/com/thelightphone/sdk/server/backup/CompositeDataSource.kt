package com.thelightphone.sdk.server.backup

import com.thelightphone.backup.BackupDataSource
import com.thelightphone.backup.BackupPath
import com.thelightphone.toolmanager.Logger
import kotlinx.io.files.Path
import java.io.InputStream
import kotlin.time.Instant

// Muxes several BackupDataSource implementations behind one. getFilesToBackUpForPath/readFile/
// hashForFile only receive a bare Path with no other context, so every Path this hands out is
// "<source index>/<path as that source itself produced it>" - decode() strips the index back off
// to find which source to delegate to, and to recover the path in that source's own terms.

class CompositeDataSource(
    sources: List<BackupDataSource>,
    private val logger: Logger
) : BackupDataSource {
    // Copy since we're mapping by index
    private val sources: List<BackupDataSource> = sources.toList()

    override suspend fun getPathsToBackUp(): Result<List<BackupPath>> = runCatching {
        sources.flatMapIndexed { index, source ->
            source.getPathsToBackUp()
                .onFailure { logger.reportError(TAG, it, "Source $index failed to list backup paths") }
                .getOrDefault(emptyList())
                .map { it.copy(localPath = encode(index, it.localPath)) }
        }
    }

    override suspend fun getFilesToBackUpForPath(
        parent: Path,
        lowerBound: Instant,
        upperBound: Instant
    ): Result<List<Path>> = runCatching {
        val (index, innerPath) = decode(parent)
        sources[index].getFilesToBackUpForPath(innerPath, lowerBound, upperBound)
            .getOrThrow()
            .map { encode(index, it) }
    }

    override suspend fun getEarliestPossibleBackupDate(parent: Path): Result<Instant> = runCatching {
        val (index, innerPath) = decode(parent)
        sources[index].getEarliestPossibleBackupDate(innerPath).getOrThrow()
    }

    override suspend fun readFile(path: Path): Result<InputStream> = runCatching {
        val (index, innerPath) = decode(path)
        sources[index].readFile(innerPath).getOrThrow()
    }

    override suspend fun hashForFile(path: Path): Result<String> = runCatching {
        val (index, innerPath) = decode(path)
        sources[index].hashForFile(innerPath).getOrThrow()
    }

    private fun encode(index: Int, path: Path): Path = Path("$index$SEPARATOR$path")

    private fun decode(path: Path): Pair<Int, Path> {
        val raw = path.toString()
        val separatorIndex = raw.indexOf(SEPARATOR)
        require(separatorIndex >= 0) { "Malformed composite backup path: $path" }
        val index = raw.substring(0, separatorIndex).toInt()
        val inner = raw.substring(separatorIndex + SEPARATOR.length)
        return index to Path(inner)
    }

    companion object {
        private const val TAG = "CompositeDataSource"
        private const val SEPARATOR = "/"
    }
}
