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
import com.thelightphone.toolmanager.EntryType
import com.thelightphone.toolmanager.Logger
import com.thelightphone.toolmanager.PageRequest
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
import kotlin.time.Instant

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
            .also { println("tools to be backed up: $it") }
    }

    override suspend fun getFilesToBackUpForPath(
        parent: Path,
        timeOfLastBackup: Instant
    ): Result<List<Path>> = runCatching {
        val leafKey = parent.toString()
        val tree = leafTrees[leafKey] ?: throw NoSuchElementException("Unknown backup path: $parent")
        collectFiles(tree, leafKey, Paths.get("."), timeOfLastBackup.toEpochMilliseconds())
            .also { println("get files to back up for $parent: $it" ) }
    }

    override suspend fun readFile(path: Path): Result<InputStream> {
        val (leafKey, relativePath) = splitLeafPath(path)
        val tree = leafTrees[leafKey]
            ?: return Result.failure(NoSuchElementException("Unknown backup path: $path"))
        return tree.getBytes(Paths.get(relativePath))
    }

    override suspend fun hashForFile(path: Path): Result<String> = runCatching {
        val (leafKey, relativePath) = splitLeafPath(path)
        val tree = leafTrees[leafKey] ?: throw NoSuchElementException("Unknown backup path: $path")
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
    }

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

    private fun backupLeaves(nodes: List<ClientTreeNode>): List<ClientLeafNode> = nodes.flatMap { node ->
        when (node) {
            is ClientLeafNode -> if (node.canBeBackedUp) listOf(node) else emptyList()
            is ClientBranchNode -> backupLeaves(node.children)
        }
    }

    // Files nested under a leaf's root are encoded as "<leafKey>/<path relative to that leaf's root>"
    private fun splitLeafPath(path: Path): Pair<String, String> {
        val raw = path.toString()
        val leafKey = leafTrees.keys.firstOrNull { raw == it || raw.startsWith("$it/") } ?: return raw to "."
        val relative = raw.removePrefix(leafKey).removePrefix("/").ifEmpty { "." }
        return leafKey to relative
    }

    private suspend fun collectFiles(
        tree: ContentResolverDataTree,
        leafKey: String,
        path: JavaPath,
        cutoffMs: Long
    ): List<Path> {
        val entries = mutableListOf<Entry>()
        var page = 1
        while (true) {
            val response = tree.getDirectoryForPath(path, PageRequest(page = page, size = 500)).getOrThrow()
            entries += response.data
            if (!response.pagination.hasNext) break
            page++
        }
        return entries.flatMap { entry ->
            when {
                entry.type == EntryType.Directory -> collectFiles(tree, leafKey, Paths.get(entry.path), cutoffMs)
                entry.lastModified > cutoffMs -> listOf(Path("$leafKey/${entry.path}"))
                else -> emptyList()
            }
        }
    }
}