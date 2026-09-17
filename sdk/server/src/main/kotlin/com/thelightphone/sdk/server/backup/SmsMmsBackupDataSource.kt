package com.thelightphone.sdk.server.backup

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.webkit.MimeTypeMap
import com.thelightphone.backup.BackupDataSource
import com.thelightphone.backup.BackupPath
import com.thelightphone.toolmanager.Logger
import kotlinx.io.files.Path
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

private fun mmsPartUri(partId: Long): Uri = Uri.parse("content://mms/part/$partId")

/**
 * Backs up SMS/MMS (and RCS, insofar as the platform surfaces it through the same Telephony
 * provider) messages.
 */
class SmsMmsBackupDataSource(
    private val appContext: Context,
    private val logger: Logger,
) : BackupDataSource {

    private val contentResolver: ContentResolver get() = appContext.contentResolver

    // BackupRunner always calls hashForFile immediately after readFile for the same path (and
    // never concurrently with another file's readFile), so readFile's stream computes the hash as
    // a side effect of the caller reading it, and hashForFile just collects the result from here.
    private val hashCache = ConcurrentHashMap<String, String>()

    override suspend fun getPathsToBackUp(): Result<List<BackupPath>> = runCatching {
        listOf(BackupPath(authority = AUTHORITY, localPath = Path(ROOT_LABEL), label = ROOT_LABEL))
    }.onFailure { logger.reportError(TAG, it, "Failed to list backup paths") }

    override suspend fun getEarliestPossibleBackupDate(parent: Path): Result<Instant> = runCatching {
        val earliestSms = earliestSinceEpoch(Telephony.Sms.CONTENT_URI, Telephony.Sms.DATE) { Instant.fromEpochMilliseconds(it) }
        val earliestMms = earliestSinceEpoch(Telephony.Mms.CONTENT_URI, Telephony.Mms.DATE) { Instant.fromEpochSeconds(it) }
        val earliest = listOfNotNull(earliestSms, earliestMms).minOrNull() ?: Clock.System.now()
        logger.log(TAG, "Earliest possible backup date: $earliest")
        earliest
    }.onFailure { logger.reportError(TAG, it, "Failed to determine earliest possible backup date") }

    override suspend fun getFilesToBackUpForPath(
        parent: Path,
        lowerBound: Instant,
        upperBound: Instant
    ): Result<List<Path>> = runCatching {
        val files = mutableListOf<Path>()

        val hasSms = hasSmsInRange(lowerBound, upperBound)
        if (hasSms) {
            files += FileKey.SmsWindow(lowerBound, upperBound).toPath()
        }

        val mmsIds = mmsIdsInRange(lowerBound, upperBound)
        var partCount = 0
        if (mmsIds.isNotEmpty()) {
            files += FileKey.MmsWindow(lowerBound, upperBound).toPath()
            for (mmsId in mmsIds) {
                for (part in partsFor(mmsId)) {
                    files += FileKey.MmsPart(lowerBound, upperBound, mmsId, part.id, part.fileName).toPath()
                    partCount++
                }
            }
        }
        logger.log(
            TAG,
            "Window ($lowerBound, $upperBound]: sms=$hasSms, mms=${mmsIds.size}, mmsParts=$partCount",
        )
        files
    }.onFailure { logger.reportError(TAG, it, "Failed to list files to back up for window ($lowerBound, $upperBound]") }

    override suspend fun readFile(path: Path): Result<InputStream> = runCatching {
        val key = FileKey.parse(path)
        logger.log(TAG, "Reading ${key.describe()}")
        val raw = when (key) {
            is FileKey.SmsWindow -> smsWindowStream(key.lowerBound, key.upperBound)
            is FileKey.MmsWindow -> mmsWindowStream(key.lowerBound, key.upperBound)
            is FileKey.MmsPart ->
                contentResolver.openInputStream(mmsPartUri(key.partId))
                    ?: throw NoSuchElementException("MMS part ${key.partId} (message ${key.mmsId}) is no longer available")
        }
        hashingStream(path.toString(), raw)
    }.onFailure { logger.reportError(TAG, it, "Failed to read ${safeDescribe(path)}") }

    override suspend fun hashForFile(path: Path): Result<String> = runCatching {
        hashCache[path.toString()] ?: run {
            logger.log(TAG, "Hash cache miss for ${safeDescribe(path)}, regenerating to compute it")
            readFile(path).getOrThrow().use { it.copyTo(DiscardingOutputStream) }
            hashCache[path.toString()]
                ?: error("Failed to compute hash for ${safeDescribe(path)}")
        }
    }.onFailure { logger.reportError(TAG, it, "Failed to hash ${safeDescribe(path)}") }

    // for logging
    private fun safeDescribe(path: Path): String =
        runCatching { FileKey.parse(path).describe() }.getOrDefault("<unparseable backup path>")

    private fun hasSmsInRange(lower: Instant, upper: Instant): Boolean {
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.DATE} <= ?",
            arrayOf(lower.toEpochMilliseconds().toString(), upper.toEpochMilliseconds().toString()),
            null
        ) ?: throw IllegalStateException("SMS provider query returned null - is READ_SMS granted?")
        return cursor.use { it.moveToFirst() }
    }

    private fun mmsIdsInRange(lower: Instant, upper: Instant): List<Long> {
        val cursor = contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.DATE} > ? AND ${Telephony.Mms.DATE} <= ?",
            arrayOf(lower.epochSeconds.toString(), upper.epochSeconds.toString()),
            null
        ) ?: throw IllegalStateException("MMS provider query returned null - is READ_SMS granted?")
        return cursor.use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
    }

    private fun earliestSinceEpoch(uri: Uri, dateColumn: String, toInstant: (Long) -> Instant): Instant? {
        val cursor = contentResolver.query(uri, arrayOf(dateColumn), null, null, "$dateColumn ASC")
            ?: throw IllegalStateException("Telephony provider query returned null - is READ_SMS granted?")
        return cursor.use { if (it.moveToFirst()) toInstant(it.getLong(0)) else null }
    }

    private fun smsWindowStream(lower: Instant, upper: Instant): InputStream {
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            SMS_PROJECTION,
            "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.DATE} <= ?",
            arrayOf(lower.toEpochMilliseconds().toString(), upper.toEpochMilliseconds().toString()),
            "${Telephony.Sms.DATE} ASC"
        ) ?: throw IllegalStateException("SMS provider query returned null - is READ_SMS granted?")
        return CursorNdjsonInputStream(cursor, ::smsRowToJson)
    }

    private fun mmsWindowStream(lower: Instant, upper: Instant): InputStream {
        val cursor = contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            MMS_PROJECTION,
            "${Telephony.Mms.DATE} > ? AND ${Telephony.Mms.DATE} <= ?",
            arrayOf(lower.epochSeconds.toString(), upper.epochSeconds.toString()),
            "${Telephony.Mms.DATE} ASC"
        ) ?: throw IllegalStateException("MMS provider query returned null - is READ_SMS granted?")
        return CursorNdjsonInputStream(cursor, ::mmsRowToJson)
    }

    private fun smsRowToJson(cursor: Cursor): JSONObject = JSONObject().apply {
        put("id", cursor.getLongOrNull(Telephony.Sms._ID))
        put("tId", cursor.getLongOrNull(Telephony.Sms.THREAD_ID))
        put("adr", cursor.getStringOrNull(Telephony.Sms.ADDRESS))
        put("body", cursor.getStringOrNull(Telephony.Sms.BODY))
        put("d", cursor.getLongOrNull(Telephony.Sms.DATE))
        put("dSent", cursor.getLongOrNull(Telephony.Sms.DATE_SENT))
        put("type", cursor.getIntOrNull(Telephony.Sms.TYPE))
        put("rd", cursor.getIntOrNull(Telephony.Sms.READ))
        put("sId", cursor.getIntOrNull(Telephony.Sms.SUBSCRIPTION_ID))
    }

    private fun mmsRowToJson(cursor: Cursor): JSONObject {
        val id = cursor.getLongOrNull(Telephony.Mms._ID)
        return JSONObject().apply {
            put("id", id)
            put("tId", cursor.getLongOrNull(Telephony.Mms.THREAD_ID))
            // Stored in whole seconds - normalized to milliseconds to match the sms rows above.
            put("d", cursor.getLongOrNull(Telephony.Mms.DATE)?.let { Instant.fromEpochSeconds(it).toEpochMilliseconds() })
            put("dSent", cursor.getLongOrNull(Telephony.Mms.DATE_SENT)?.let { Instant.fromEpochSeconds(it).toEpochMilliseconds() })
            put("mBox", cursor.getIntOrNull(Telephony.Mms.MESSAGE_BOX))
            put("rd", cursor.getIntOrNull(Telephony.Mms.READ))
            put("sId", cursor.getIntOrNull(Telephony.Mms.SUBSCRIPTION_ID))
            put("subj", cursor.getStringOrNull(Telephony.Mms.SUBJECT))
            if (id != null) {
                put("adrs", addressesFor(id))
                put("parts", JSONArray(partsFor(id).map { part ->
                    JSONObject().apply {
                        put("id", part.id)
                        put("cType", part.contentType)
                        put("fName", part.fileName)
                    }
                }))
            }
        }
    }

    // "address"/"type" (PduHeaders From=137/To=151/Cc=130/Bcc=129) are undocumented columns of
    private fun addressesFor(mmsId: Long): JSONArray {
        val uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$mmsId/addr")
        val result = JSONArray()
        contentResolver.query(uri, arrayOf("address", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result.put(JSONObject().apply {
                    put("adr", c.getStringOrNull("address"))
                    put("type", addressTypeName(c.getIntOrNull("type")))
                })
            }
        }
        return result
    }

    // don't feel great about this but it's widely used
    // https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android
    private fun addressTypeName(type: Int?): String = when (type) {
        137 -> "from"
        151 -> "to"
        130 -> "cc"
        129 -> "bcc"
        else -> "unknown"
    }

    // "_id"/"ct"/"name"/"cl" are undocumented columns of the mms part sub-table
    private fun partsFor(mmsId: Long): List<MmsPartInfo> {
        val uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$mmsId/part")
        val result = mutableListOf<MmsPartInfo>()
        contentResolver.query(uri, arrayOf("_id", "ct", "name", "cl"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val partId = c.getLongOrNull("_id") ?: continue
                val contentType = c.getStringOrNull("ct") ?: "application/octet-stream"
                val fileName = (c.getStringOrNull("name") ?: c.getStringOrNull("cl"))
                    ?.replace('/', '_')?.replace('\\', '_')
                    ?: defaultPartFileName(partId, contentType)
                result += MmsPartInfo(partId, contentType, fileName)
            }
        }
        return result
    }

    private fun defaultPartFileName(partId: Long, contentType: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType)
        return if (extension != null) "part-$partId.$extension" else "part-$partId"
    }

    // Since these files are generated by us, we can hash/cache as we go
    private fun hashingStream(pathKey: String, inner: InputStream): InputStream {
        val digest = MessageDigest.getInstance("SHA-256")
        var cached = false
        return object : FilterInputStream(inner) {
            override fun read(): Int {
                val b = super.read()
                if (b == -1) cacheDigest() else digest.update(b.toByte())
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val read = super.read(b, off, len)
                if (read == -1) cacheDigest() else digest.update(b, off, read)
                return read
            }

            private fun cacheDigest() {
                if (cached) return
                cached = true
                hashCache[pathKey] = digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    private data class MmsPartInfo(val id: Long, val contentType: String, val fileName: String)

    companion object {
        private const val TAG = "SmsMmsBackupDataSource"
        private const val AUTHORITY = "sms-mms"
        private const val ROOT_LABEL = "sms-mms"

        private val SMS_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID,
        )

        private val MMS_PROJECTION = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.DATE,
            Telephony.Mms.DATE_SENT,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.READ,
            Telephony.Mms.SUBSCRIPTION_ID,
            Telephony.Mms.SUBJECT,
        )
    }
}

// Encodes/decodes the synthetic Paths this data source hands out
private sealed class FileKey {
    abstract fun toPath(): Path

    // Ids and timestamps only - never the (sender-supplied, for MmsPart) file name - so this is
    // always safe to log.
    abstract fun describe(): String

    data class SmsWindow(val lowerBound: Instant, val upperBound: Instant) : FileKey() {
        override fun toPath() =
            Path("sms/${lowerBound.toEpochMilliseconds()}-${upperBound.toEpochMilliseconds()}/sms.ndjson")

        override fun describe() = "sms window ($lowerBound, $upperBound]"
    }

    data class MmsWindow(val lowerBound: Instant, val upperBound: Instant) : FileKey() {
        override fun toPath() =
            Path("mms/${lowerBound.toEpochMilliseconds()}-${upperBound.toEpochMilliseconds()}/mms.ndjson")

        override fun describe() = "mms window ($lowerBound, $upperBound]"
    }

    data class MmsPart(
        val lowerBound: Instant,
        val upperBound: Instant,
        val mmsId: Long,
        val partId: Long,
        val fileName: String,
    ) : FileKey() {
        override fun toPath() =
            Path("mms/${lowerBound.toEpochMilliseconds()}-${upperBound.toEpochMilliseconds()}/parts/$mmsId-$partId-$fileName")

        override fun describe() = "mms part id=$partId of message id=$mmsId"
    }

    companion object {
        fun parse(path: Path): FileKey {
            val segments = path.toString().split("/")
            val bounds = { raw: String ->
                val (lower, upper) = raw.split("-", limit = 2)
                Instant.fromEpochMilliseconds(lower.toLong()) to Instant.fromEpochMilliseconds(upper.toLong())
            }
            return when (segments.getOrNull(0)) {
                "sms" -> {
                    val (lower, upper) = bounds(segments[1])
                    SmsWindow(lower, upper)
                }
                "mms" -> {
                    val (lower, upper) = bounds(segments[1])
                    if (segments.getOrNull(2) == "parts") {
                        val (mmsId, partId, fileName) = segments[3].split("-", limit = 3)
                        MmsPart(lower, upper, mmsId.toLong(), partId.toLong(), fileName)
                    } else {
                        MmsWindow(lower, upper)
                    }
                }
                else -> throw IllegalArgumentException("Unrecognized backup path: $path")
            }
        }
    }
}

// Lazily turns a Cursor into ndjson bytes one row at a time
private class CursorNdjsonInputStream(
    private val cursor: Cursor,
    private val rowToJson: (Cursor) -> JSONObject,
) : InputStream() {
    private var buffer = ByteArray(0)
    private var bufferPos = 0
    private var exhausted = false

    private fun advance() {
        buffer = if (cursor.moveToNext()) {
            (rowToJson(cursor).toString() + "\n").toByteArray(Charsets.UTF_8)
        } else {
            exhausted = true
            ByteArray(0)
        }
        bufferPos = 0
    }

    override fun read(): Int {
        while (bufferPos >= buffer.size) {
            if (exhausted) return -1
            advance()
        }
        return buffer[bufferPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (bufferPos >= buffer.size) {
            if (exhausted) return -1
            advance()
        }
        val toCopy = minOf(buffer.size - bufferPos, len)
        System.arraycopy(buffer, bufferPos, b, off, toCopy)
        bufferPos += toCopy
        return toCopy
    }

    override fun close() = cursor.close()
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}

private fun Cursor.getStringOrNull(column: String): String? {
    val idx = getColumnIndex(column)
    return if (idx < 0 || isNull(idx)) null else getString(idx)
}

private fun Cursor.getLongOrNull(column: String): Long? {
    val idx = getColumnIndex(column)
    return if (idx < 0 || isNull(idx)) null else getLong(idx)
}

private fun Cursor.getIntOrNull(column: String): Int? {
    val idx = getColumnIndex(column)
    return if (idx < 0 || isNull(idx)) null else getInt(idx)
}
