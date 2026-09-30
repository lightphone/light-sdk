package com.thelightphone.sdk.server.backup

import android.content.Context
import com.thelightphone.backup.BackupDataSource
import com.thelightphone.toolmanager.Logger

enum class InternalToolBackupSpec(
    val packageName: String,
    val defaultLabel: String,
    val dataSourceFactory: (Context, Logger) -> BackupDataSource
) {
    Messages(
        SmsMmsBackupDataSource.AUTHORITY,
        "Messages",
        { appContext, logger -> SmsMmsBackupDataSource(appContext, logger)
    });

    companion object {
        fun fromPackageName(packageName: String) = entries.firstOrNull { it.packageName == packageName }
    }
}