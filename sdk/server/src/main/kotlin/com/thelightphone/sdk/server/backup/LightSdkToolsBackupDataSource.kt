package com.thelightphone.sdk.server.backup

import android.content.Context
import com.thelightphone.backup.BackupDataSource
import com.thelightphone.backup.BackupPath
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.toolmanager.ClientBranchNode
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientTreeNode
import com.thelightphone.toolmanager.ContentResolverDataTree
import com.thelightphone.toolmanager.Entry
import com.thelightphone.toolmanager.Logger
import com.thelightphone.toolmanager.PageRequest
import com.thelightphone.toolmanager.SortBy
import com.thelightphone.toolmanager.SortOrder
import com.thelightphone.toolmanager.ToolManagerTool
import com.thelightphone.toolmanager.discoverToolManagerEnabledTools
import com.thelightphone.toolmanager.effectiveBasePath
import kotlinx.io.files.Path
import java.io.InputStream
import java.nio.file.Path as JavaPath
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.time.Clock
import kotlin.time.Instant

private const val TAG = "LightSdkToolsBackupDataSource"

/**
 * Bridges the gap between Tool Manager I/O and cloud backups
 * Wraps ContentResolverDataTree (reads/writes files from tools)
 * with the BackupDataSource interface (sources data for backups)
 *
 * Also auto-discovers and filters Tools that offer backups via Context.discoverToolManagerEnabledTools
 */
class LightSdkToolsBackupDataSource(
    private val appContext: Context,
    private val logger: Logger
) : BackupDataSource {

    // Populated by getPathsToBackUp(); keyed by the same string each BackupPath.localPath /
    // getFilesToBackUpForPath Path is built from below. readFile/hashForFile/
    // getFilesToBackUpForPath only receive a bare kotlinx.io.files.Path (no authority), so this is
    // how they find their way back to the right tool leaf's ContentResolverDataTree.
    private val leafTrees = ConcurrentHashMap<String, ContentResolverDataTree>()

    override suspend fun getPathsToBackUp(): Result<List<BackupPath>> = runCatching {
        val clientFilterLevel = LightSdkServer.provideSdkSettings(appContext).clientFilterLevel
        val tools = appContext.discoverToolManagerEnabledTools(logger) {
            LightSdkServer.canBackUpFromPackage(clientFilterLevel, appContext, it)
        }
        tools.flatMap { tool -> backupPathsForTool(tool) }
            .also { logger.log(TAG, "${it.size} tools to be backed up.") }
    }.onFailure { logger.reportError(TAG, it, "Failed to list backup paths") }

    override suspend fun getFilesToBackUpForPath(
        parent: Path,
        lowerBound: Instant,
        upperBound: Instant
    ): Result<List<Path>> = runCatching {
        val leafKey = parent.toString()
        val tree =
            leafTrees[leafKey] ?: throw NoSuchElementException("Unknown backup path: $parent")
        val lowerMs = lowerBound.toEpochMilliseconds()
        val upperMs = upperBound.toEpochMilliseconds()
        collectFileEntriesInRange(tree, Paths.get("."), lowerMs, upperMs)
            .map { Path("$leafKey/${it.path}") }
            .also { logger.log(TAG, "${it.size} files to be backed up.") }
    }.onFailure { logger.reportError(TAG, it, "Failed to list files to back up for $parent") }

    override suspend fun getEarliestPossibleBackupDate(parent: Path): Result<Instant> =
        runCatching {
            val leafKey = parent.toString()
            val tree =
                leafTrees[leafKey] ?: throw NoSuchElementException("Unknown backup path: $parent")
            val response = tree.getDirectoryForPath(
                Paths.get("."),
                PageRequest(
                    page = 1,
                    size = 1,
                    sortBy = SortBy.DATE,
                    sortOrder = SortOrder.ASC,
                    flatten = true
                )
            ).getOrThrow()
            val instant = response.data.firstOrNull()
                ?.let { Instant.fromEpochMilliseconds(it.lastModified) }
                ?: Clock.System.now()
            logger.log(TAG, "earliest possible backup date: $instant")
            instant
        }.onFailure { logger.reportError(TAG, it, "Failed to determine earliest possible backup date for $parent") }

    override suspend fun readFile(path: Path): Result<InputStream> {
        // Only log leafKey!
        val (leafKey, relativePath) = splitLeafPath(path)
        val tree = leafTrees[leafKey] ?: run {
            val error = NoSuchElementException("Unknown backup path: $leafKey")
            logger.reportError(TAG, error, "Failed to read file under $leafKey")
            return Result.failure(error)
        }
        return tree.getBytes(Paths.get(relativePath))
            .onFailure { logger.reportError(TAG, it, "Failed to read file under $leafKey") }
    }

    override suspend fun hashForFile(path: Path): Result<String> = runCatching {
        val (leafKey, relativePath) = splitLeafPath(path)
        val tree = leafTrees[leafKey] ?: throw NoSuchElementException("Unknown backup path: $leafKey")
        val digest = MessageDigest.getInstance("SHA-256")
        tree.getBytes(Paths.get(relativePath)).getOrThrow().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.onFailure { logger.reportError(TAG, it, "Failed to hash file under ${splitLeafPath(path).first}") }

    private fun backupPathsForTool(tool: ToolManagerTool): List<BackupPath> {
        return backupLeaves(tool.manifest.roots).map { leaf ->
            val basePath = leaf.effectiveBasePath()
            val key = "${tool.authority}::$basePath"
            leafTrees[key] = ContentResolverDataTree(
                contentResolver = appContext.contentResolver,
                authority = tool.authority,
                basePath = basePath,
                showHiddenFiles = leaf.showHiddenFiles
            )
            BackupPath(authority = tool.authority, localPath = Path(key), label = key)
        }
    }

    private fun backupLeaves(nodes: List<ClientTreeNode>): List<ClientLeafNode> =
        nodes.flatMap { node ->
            when (node) {
                is ClientLeafNode -> if (node.canBeBackedUp) listOf(node) else emptyList()
                is ClientBranchNode -> backupLeaves(node.children)
            }
        }

    // Files nested under a leaf's root are encoded as "<leafKey>/<path relative to that leaf's root>"
    private fun splitLeafPath(path: Path): Pair<String, String> {
        val raw = path.toString()
        val leafKey =
            leafTrees.keys.firstOrNull { raw == it || raw.startsWith("$it/") } ?: return raw to "."
        val relative = raw.removePrefix(leafKey).removePrefix("/").ifEmpty { "." }
        return leafKey to relative
    }

    private suspend fun collectFileEntriesInRange(
        tree: ContentResolverDataTree,
        path: JavaPath,
        lowerMs: Long,
        upperMs: Long,
    ): List<Entry> {
        val entries = mutableListOf<Entry>()
        var page = 1
        while (true) {
            val response = tree.getDirectoryForPath(
                path,
                PageRequest(
                    page = page,
                    size = 500,
                    sortBy = SortBy.DATE,
                    sortOrder = SortOrder.DESC,
                    flatten = true
                )
            ).getOrThrow()
            for (entry in response.data) {
                if (entry.lastModified <= lowerMs) return entries
                if (entry.lastModified <= upperMs) entries += entry
            }
            if (!response.pagination.hasNext) break
            page++
        }
        return entries
    }
}