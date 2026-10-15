package com.thelightphone.sdk.server.backup

import android.content.Context
import com.thelightphone.backup.BackupDataSource
import com.thelightphone.toolmanager.Logger

// Internal tools are backed up in a custom way
enum class InternalToolBackupSpec(
    val packageName: String,
    val defaultLabel: String,
    val dataSourceFactory: (Context, Logger) -> BackupDataSource
) {
    Messages(
        SmsMmsBackupDataSource.AUTHORITY,
        "Messages",
        { appContext, logger -> SmsMmsBackupDataSource(appContext, logger) }
    ),
    Album(
        AlbumBackupDataSource.AUTHORITY,
        "Album",
        { _, logger -> AlbumBackupDataSource(logger) }
    );

    companion object {
        fun fromPackageName(packageName: String) = entries.firstOrNull { it.packageName == packageName }
    }
}