package com.thelightphone.sdk.server.backup

/**
 * A Telephony.Sms selection (in ContentResolver.query form) matching rows to leave out of backups.
 * See LightSdkServer.getSmsBackupExclusion.
 */
data class SmsBackupExclusion(
    val selection: String,
    val selectionArgs: List<String> = emptyList(),
)

// Adds "AND NOT (exclusion)" to a query's selection. A null selection means "every row".
internal fun SmsBackupExclusion?.applyTo(
    selection: String?,
    selectionArgs: Array<String>,
): Pair<String?, Array<String>> {
    if (this == null) return selection to selectionArgs
    val excluding = "NOT (${this.selection})"
    val combined = if (selection == null) excluding else "($selection) AND $excluding"
    return combined to (selectionArgs + this.selectionArgs)
}
