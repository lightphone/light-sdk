import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.server.backup.AlbumBackupDataSource
import com.thelightphone.toolmanager.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private object SilentLogger : Logger {
    override fun log(tag: String, message: String) = Unit
    override fun reportError(tag: String, exception: Throwable?, message: String) = Unit
}

class AlbumBackupDataSourceTest {
    private val root = Files.createTempDirectory("album-test").toFile()
    private val cameraDir = File(root, "camera").apply { mkdirs() }
    private val screenshotsDir = File(root, "screenshots").apply { mkdirs() }
    private val moviesDir = File(root, "movies").apply { mkdirs() }

    private val dataSource = AlbumBackupDataSource(SilentLogger)

    init {
        LightSdkServer.getAlbumPhotoBackupDirs = { listOf(cameraDir, screenshotsDir) }
        LightSdkServer.getAlbumVideoBackupDirs = { listOf(moviesDir) }
        LightSdkServer.canBackUpFromPackage = { true }
    }

    @AfterTest
    fun tearDown() {
        LightSdkServer.getAlbumPhotoBackupDirs = { emptyList() }
        LightSdkServer.getAlbumVideoBackupDirs = { emptyList() }
        LightSdkServer.canBackUpFromPackage = { true }
        root.deleteRecursively()
    }

    private fun file(dir: File, name: String, content: String = name, modifiedMs: Long = 1_000) =
        File(dir, name).apply {
            writeText(content)
            setLastModified(modifiedMs)
        }

    private val everything = Instant.fromEpochMilliseconds(0) to Instant.fromEpochMilliseconds(Long.MAX_VALUE / 2)

    private fun filesFor(label: String, range: Pair<Instant, Instant> = everything) = runBlocking {
        dataSource.getFilesToBackUpForPath(Path(label), range.first, range.second).getOrThrow()
    }

    @Test
    fun pathsToBackUp_areJustImageAndVideo() = runBlocking {
        val paths = dataSource.getPathsToBackUp().getOrThrow()

        assertEquals(listOf("image", "video"), paths.map { it.label })
        assertEquals(listOf("image", "video"), paths.map { it.localPath.toString() })
    }

    @Test
    fun pathsToBackUp_omitsKindsWithNoDirectories() = runBlocking {
        LightSdkServer.getAlbumVideoBackupDirs = { emptyList() }

        assertEquals(listOf("image"), dataSource.getPathsToBackUp().getOrThrow().map { it.label })
    }

    @Test
    fun pathsToBackUp_isEmptyWhenUserDisallowsAlbumBackup() = runBlocking {
        LightSdkServer.canBackUpFromPackage = { it.packageName != AlbumBackupDataSource.AUTHORITY }

        assertTrue(dataSource.getPathsToBackUp().getOrThrow().isEmpty())
    }

    @Test
    fun files_fromAllDirectoriesAreFlattenedUnderOneParent() {
        file(cameraDir, "a.jpg")
        file(screenshotsDir, "b.png")
        file(moviesDir, "c.mp4")

        val images = filesFor("image")
        val videos = filesFor("video")

        assertEquals(2, images.size)
        assertEquals(1, videos.size)
        // exactly one directory level under the label, whatever the source directory
        assertTrue(images.all { it.toString().removePrefix("image/").count { c -> c == '/' } == 0 })
    }

    @Test
    fun sameNamedFilesInDifferentDirectories_getDistinctPathsAndReadTheirOwnContent() = runBlocking {
        file(cameraDir, "IMG_1.jpg", content = "from camera")
        file(screenshotsDir, "IMG_1.jpg", content = "from screenshots")

        val paths = filesFor("image")

        assertEquals(2, paths.toSet().size)
        val contents = paths.map { dataSource.readFile(it).getOrThrow().use { s -> s.readBytes().decodeToString() } }
        assertEquals(setOf("from camera", "from screenshots"), contents.toSet())
    }

    @Test
    fun files_areFilteredToTheModifiedRange_lowerExclusiveUpperInclusive() {
        file(cameraDir, "old.jpg", modifiedMs = 1_000)
        file(cameraDir, "at-lower.jpg", modifiedMs = 2_000)
        file(cameraDir, "inside.jpg", modifiedMs = 3_000)
        file(cameraDir, "at-upper.jpg", modifiedMs = 4_000)
        file(cameraDir, "new.jpg", modifiedMs = 5_000)

        val paths = filesFor(
            "image",
            Instant.fromEpochMilliseconds(2_000) to Instant.fromEpochMilliseconds(4_000),
        )

        assertEquals(setOf("inside.jpg", "at-upper.jpg"), paths.map { it.toString().substringAfter("-") }.toSet())
    }

    @Test
    fun hiddenFilesAndSubdirectories_areSkipped() {
        file(cameraDir, "keep.jpg")
        file(cameraDir, ".pending-123-photo.jpg")
        File(cameraDir, "nested").mkdirs()
        file(File(cameraDir, "nested"), "deep.jpg")

        val paths = filesFor("image")

        assertEquals(listOf("keep.jpg"), paths.map { it.toString().substringAfter("-") })
    }

    @Test
    fun pathNames_areStableAcrossCalls() {
        file(cameraDir, "a.jpg")

        assertEquals(filesFor("image"), filesFor("image"))
    }

    @Test
    fun hashForFile_isSha256OfContents() = runBlocking {
        file(cameraDir, "a.jpg", content = "hello")

        val hash = dataSource.hashForFile(filesFor("image").single()).getOrThrow()

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun earliestBackupDate_isOldestModifiedAcrossDirectories() = runBlocking {
        file(cameraDir, "a.jpg", modifiedMs = 9_000)
        file(screenshotsDir, "b.png", modifiedMs = 3_000)

        val earliest = dataSource.getEarliestPossibleBackupDate(Path("image")).getOrThrow()

        assertEquals(Instant.fromEpochMilliseconds(3_000), earliest)
    }

    @Test
    fun unknownParent_fails() = runBlocking {
        assertTrue(dataSource.getFilesToBackUpForPath(Path("audio"), everything.first, everything.second).isFailure)
        assertTrue(dataSource.readFile(Path("image/deadbeef-missing.jpg")).isFailure)
    }
}
