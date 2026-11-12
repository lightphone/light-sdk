package com.thelightphone.sdk

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LightContacts internal constructor(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    data class Contact(
        val id: Long,
        val name: String,
    )

    data class Directory(
        val contacts: List<Contact>,
        val truncated: Boolean,
    )

    data class LabeledValue(
        val label: String,
        val value: String,
    )

    data class Details(
        val id: Long,
        val name: String,
        val phones: List<LabeledValue>,
        val emails: List<LabeledValue>,
        val addresses: List<LabeledValue>,
        val websites: List<LabeledValue>,
        val events: List<LabeledValue>,
        val organization: String?,
        val note: String?,
    )

    /**
     * load contacts using standard Android ContentResolver
     *
     * will return a [Directory] with a maximum of [limit] entries
     * passing a negative [limit] will return all possible contacts
     *
     * runs on [Dispatchers.IO] by default
     */
    suspend fun load(limit: Int = MAX_LISTED): Directory = withContext(ioDispatcher) {
        val contacts = mutableListOf<Contact>()
        var truncated = false
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ),
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            if (idIndex < 0 || nameIndex < 0) return@withContext Directory(
                emptyList(),
                truncated = false
            )
            while (cursor.moveToNext()) {
                if (contacts.size == limit) {
                    truncated = true
                    break
                }
                val name = cursor.getString(nameIndex).orEmpty()
                if (name.isBlank()) continue
                contacts += Contact(cursor.getLong(idIndex), name)
            }
        } ?: return@withContext Directory(emptyList(), truncated = false)
        Directory(contacts, truncated)
    }

    suspend fun loadDetails(id: Long): Details? = withContext(ioDispatcher) {
        val name = queryString(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(id.toString()),
        ) { cursor ->
            cursor.getString(0).orEmpty()
        } ?: return@withContext null

        Details(
            id = id,
            name = name,
            phones = labeledValues(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                id,
            ) { type, custom ->
                ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, custom)
            },
            emails = labeledValues(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE,
                ContactsContract.CommonDataKinds.Email.LABEL,
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                id,
            ) { type, custom ->
                ContactsContract.CommonDataKinds.Email.getTypeLabel(context.resources, type, custom)
            },
            addresses = addresses(id),
            websites = dataValues(
                ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Website.URL,
                ContactsContract.CommonDataKinds.Website.TYPE,
                ContactsContract.CommonDataKinds.Website.LABEL,
                id,
            ) { type, custom ->
                websiteLabel(type, custom)
            },
            events = dataValues(
                ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Event.START_DATE,
                ContactsContract.CommonDataKinds.Event.TYPE,
                ContactsContract.CommonDataKinds.Event.LABEL,
                id,
            ) { type, custom ->
                ContactsContract.CommonDataKinds.Event.getTypeLabel(context.resources, type, custom)
            },
            organization = organization(id),
            note = notes(id),
        )
    }

    private fun addresses(id: Long): List<LabeledValue> {
        val rows = mutableListOf<LabeledValue>()
        query(
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
                ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                ContactsContract.CommonDataKinds.StructuredPostal.LABEL,
            ),
            "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
            arrayOf(id.toString()),
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val formatted = cursor.getString(0)
                val value = if (!formatted.isNullOrBlank()) {
                    formatted
                } else {
                    (1..5).mapNotNull { cursor.getString(it)?.takeIf(String::isNotBlank) }
                        .joinToString(", ")
                }
                if (value.isBlank()) continue
                val label = ContactsContract.CommonDataKinds.StructuredPostal.getTypeLabel(
                    context.resources,
                    cursor.getInt(6),
                    cursor.getString(7),
                ).toString()
                rows += LabeledValue(label, value)
            }
        }
        return rows.distinct()
    }

    private fun organization(id: Long): String? {
        val lines = mutableListOf<String>()
        query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Organization.TITLE,
                ContactsContract.CommonDataKinds.Organization.COMPANY,
                ContactsContract.CommonDataKinds.Organization.DEPARTMENT,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(id.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val line = (0..2).mapNotNull { cursor.getString(it)?.takeIf(String::isNotBlank) }
                    .joinToString(", ")
                if (line.isNotBlank()) lines += line
            }
        }
        return lines.distinct().joinToString("\n").ifBlank { null }
    }

    private fun notes(id: Long): String? {
        val lines = mutableListOf<String>()
        query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(id.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(0)
                if (!value.isNullOrBlank()) lines += value
            }
        }
        return lines.distinct().joinToString("\n\n").ifBlank { null }
    }

    private fun labeledValues(
        uri: Uri,
        valueColumn: String,
        typeColumn: String,
        labelColumn: String,
        selection: String,
        id: Long,
        typeLabel: (Int, String?) -> CharSequence,
    ): List<LabeledValue> {
        val rows = mutableListOf<LabeledValue>()
        query(
            uri,
            arrayOf(valueColumn, typeColumn, labelColumn),
            selection,
            arrayOf(id.toString()),
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(0) ?: continue
                if (value.isBlank()) continue
                rows += LabeledValue(
                    typeLabel(cursor.getInt(1), cursor.getString(2)).toString(),
                    value,
                )
            }
        }
        return rows.distinct()
    }

    private fun dataValues(
        mimeType: String,
        valueColumn: String,
        typeColumn: String,
        labelColumn: String,
        id: Long,
        typeLabel: (Int, String?) -> CharSequence,
    ): List<LabeledValue> {
        val rows = mutableListOf<LabeledValue>()
        query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(valueColumn, typeColumn, labelColumn),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(id.toString(), mimeType),
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(0) ?: continue
                if (value.isBlank()) continue
                rows += LabeledValue(
                    typeLabel(cursor.getInt(1), cursor.getString(2)).toString(),
                    value,
                )
            }
        }
        return rows.distinct()
    }

    private fun queryString(
        uri: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
        read: (Cursor) -> String,
    ): String? {
        return query(uri, projection, selection, selectionArgs)?.use { cursor ->
            if (!cursor.moveToFirst()) null else read(cursor)
        }
    }

    private fun websiteLabel(type: Int, custom: String?): String {
        if (!custom.isNullOrBlank()) return custom
        return when (type) {
            ContactsContract.CommonDataKinds.Website.TYPE_HOME -> "Home"
            ContactsContract.CommonDataKinds.Website.TYPE_WORK -> "Work"
            ContactsContract.CommonDataKinds.Website.TYPE_HOMEPAGE -> "Homepage"
            ContactsContract.CommonDataKinds.Website.TYPE_BLOG -> "Blog"
            ContactsContract.CommonDataKinds.Website.TYPE_PROFILE -> "Profile"
            ContactsContract.CommonDataKinds.Website.TYPE_FTP -> "FTP"
            ContactsContract.CommonDataKinds.Website.TYPE_OTHER -> "Other"
            else -> ""
        }
    }

    private fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
    ): Cursor? = context.contentResolver.query(uri, projection, selection, selectionArgs, null)

    private companion object {
        const val MAX_LISTED = 100
    }
}
