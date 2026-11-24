package com.thelightphone.sdk.server.installer

import java.io.File

internal fun installFromPrivateCopy(
    source: File,
    stagingDirectory: File,
    isAllowed: (File) -> Boolean,
    install: (File) -> Unit,
): Boolean {
    val staged = File.createTempFile("install-", ".apk", stagingDirectory)
    try {
        source.inputStream().use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        if (!isAllowed(staged)) return false
        install(staged)
        return true
    } finally {
        staged.delete()
    }
}
