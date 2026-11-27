package com.thelightphone.sdk.server.toolmanager

import com.thelightphone.sdk.server.ToolResourceRegistry
import com.thelightphone.toolmanager.*
import com.thelightphone.toolmanager.datatree.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T> accessResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    } catch (error: Exception) {
        Result.failure(error)
    }

/** Wrap only discovered external-tool views; host-owned settings/recovery stay independent. */
fun authorizedToolDataView(
    view: DataView<*>,
    packageName: String,
    resources: ToolResourceRegistry,
    isAllowed: () -> Boolean,
): DataView<*> {
    fun requireAllowed() {
        if (!isAllowed()) throw SecurityException("Tool Manager access refused")
    }
    suspend fun <T> access(block: suspend () -> T): T {
        requireAllowed()
        return resources.withResource(packageName, block)
    }
    fun wrap(child: DataView<*>) = authorizedToolDataView(child, packageName, resources, isAllowed)
    return when (view) {
        is BranchView ->
            BranchView(
                view.spec,
                object : BranchDataTree {
                    override suspend fun getChildren(): List<DataView<*>> = access {
                        view.provider.getChildren().map(::wrap)
                    }

                    override suspend fun invalidateCache() = access {
                        view.provider.invalidateCache()
                    }
                },
                view.isHidden,
            )
        is LeafView ->
            LeafView(
                view.spec,
                object : LeafDataTree {
                    override suspend fun getDirectoryForPath(
                        path: Path,
                        pageRequest: PageRequest,
                        invalidateCache: Boolean,
                    ) = accessResult {
                        access {
                            view.provider
                                .getDirectoryForPath(path, pageRequest, invalidateCache)
                                .getOrThrow()
                        }
                    }

                    override suspend fun getBytes(filePath: Path): Result<InputStream> =
                        accessResult {
                            access {
                                resources.trackInputStream(
                                    packageName,
                                    view.provider.getBytes(filePath).getOrThrow(),
                                )
                            }
                        }

                    override suspend fun getThumbnailBytes(
                        filePath: Path,
                        type: EntryType,
                    ): Result<InputStream> = accessResult {
                        access {
                            resources.trackInputStream(
                                packageName,
                                view.provider.getThumbnailBytes(filePath, type).getOrThrow(),
                            )
                        }
                    }

                    override suspend fun checkWrite(filePath: Path): WriteCheck = access {
                        view.provider.checkWrite(filePath)
                    }

                    override suspend fun <T> writeBytes(
                        filePath: Path,
                        block: suspend (OutputStream) -> T,
                    ): Result<T> = accessResult {
                        access {
                            view.provider
                                .writeBytes(filePath) { output ->
                                    resources.trackOutputStream(packageName, output).use {
                                        block(it)
                                    }
                                }
                                .getOrThrow()
                        }
                    }

                    override suspend fun delete(filePath: Path) = accessResult {
                        access { view.provider.delete(filePath).getOrThrow() }
                    }

                    override suspend fun rename(filePath: Path, newName: String) = accessResult {
                        access { view.provider.rename(filePath, newName).getOrThrow() }
                    }

                    override suspend fun getDirectoryMeta(directoryPath: Path) = accessResult {
                        access { view.provider.getDirectoryMeta(directoryPath).getOrThrow() }
                    }

                    override suspend fun notify(directoryPath: Path) = access {
                        view.provider.notify(directoryPath)
                    }

                    override suspend fun invalidateCache() = access {
                        view.provider.invalidateCache()
                    }

                    override fun appendMeta(entry: Entry): Entry {
                        requireAllowed()
                        return view.provider.appendMeta(entry)
                    }

                    override fun validateFile(targetPath: Path, tempFile: Path): Boolean =
                        isAllowed() && view.provider.validateFile(targetPath, tempFile)
                },
                view.isHidden,
            )
    }
}
