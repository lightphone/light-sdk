package com.thelightphone.sdk.server

import java.util.UUID
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

class ToolResourceRegistry {
    private val blocked = mutableSetOf<String>()
    private val resources = mutableMapOf<String, MutableMap<String, () -> Unit>>()

    @Synchronized fun register(packageName: String, cleanup: () -> Unit): AutoCloseable {
        if (packageName in blocked) throw SecurityException("Tool resources are revoked")
        val id = UUID.randomUUID().toString()
        resources.getOrPut(packageName) { linkedMapOf() }[id] = cleanup
        return AutoCloseable { synchronized(this) { resources[packageName]?.remove(id) } }
    }

    @Synchronized fun allow(packageName: String) { blocked -= packageName }

    suspend fun <T> withResource(packageName: String, action: suspend () -> T): T = supervisorScope {
        val task = async(start = CoroutineStart.LAZY) { action() }
        val registration = try { register(packageName) { task.cancel() } }
            catch (error: Exception) { task.cancel(); throw error }
        try { task.await() } finally { registration.close() }
    }

    fun trackOutputStream(packageName: String, output: OutputStream): OutputStream {
        val stream = object : OutputStream() {
            @Volatile var revoked = false
            var delegateClosed = false
            @Volatile var registration: AutoCloseable? = null
            fun requireOpen() { if (revoked) throw IOException("Tool stream revoked") }
            override fun write(value: Int) { requireOpen(); output.write(value) }
            override fun write(buffer: ByteArray, offset: Int, count: Int) { requireOpen(); output.write(buffer, offset, count) }
            override fun flush() { requireOpen(); output.flush() }
            @Synchronized override fun close() {
                revoked = true
                if (!delegateClosed) { output.close(); delegateClosed = true }
                registration?.close()
            }
        }
        try {
            val handle = register(packageName) { stream.close() }
            stream.registration = handle
            if (stream.revoked) handle.close()
            return stream
        } catch (error: Exception) {
            try { stream.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            throw error
        }
    }

    fun trackInputStream(packageName: String, input: InputStream): InputStream {
        val stream = object : FilterInputStream(input) {
            @Volatile var revoked = false
            var delegateClosed = false
            @Volatile var registration: AutoCloseable? = null
            fun requireOpen() { if (revoked) throw IOException("Tool stream revoked") }
            override fun read(): Int { requireOpen(); return super.read() }
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                requireOpen(); return super.read(buffer, offset, count)
            }
            override fun skip(count: Long): Long { requireOpen(); return super.skip(count) }
            override fun available(): Int { requireOpen(); return super.available() }
            @Synchronized override fun close() {
                revoked = true
                if (!delegateClosed) { super.close(); delegateClosed = true }
                registration?.close()
            }
        }
        try {
            val handle = register(packageName) { stream.close() }
            stream.registration = handle
            if (stream.revoked) handle.close()
            return stream
        } catch (error: Exception) {
            try { stream.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            throw error
        }
    }

    fun revoke(packageName: String) {
        val snapshot = synchronized(this) {
            blocked += packageName
            resources[packageName]?.toMap().orEmpty()
        }
        val failures = mutableListOf<Exception>()
        for ((id, cleanup) in snapshot) {
            try {
                cleanup()
                synchronized(this) { resources[packageName]?.remove(id) }
            } catch (error: Exception) { failures += error }
        }
        if (failures.isNotEmpty()) throw IllegalStateException("Tool resource cleanup incomplete", failures.first()).also {
            failures.drop(1).forEach(it::addSuppressed)
        }
    }
}

class SdkClientAuthorizations {
    private val tokens = mutableMapOf<Int, String>()
    @Synchronized fun issue(uid: Int): String = UUID.randomUUID().toString().also { tokens[uid] = it }
    @Synchronized fun revoke(uid: Int) { tokens.remove(uid) }
    @Synchronized fun clear() { tokens.clear() }

    fun validate(uid: Int, token: String?, currentlyAllowed: () -> Boolean): Boolean {
        if (token == null || synchronized(this) { tokens[uid] } != token) return false
        if (!currentlyAllowed()) { revoke(uid); return false }
        return synchronized(this) { tokens[uid] == token }
    }
}
