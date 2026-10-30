package com.thelightphone.sdk.textentry

import android.content.ActivityNotFoundException
import android.content.Context
import com.thelightphone.lp3Keyboard.textentry.TextEntryClient
import com.thelightphone.lp3Keyboard.textentry.TextEntryRequest
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import java.io.File

/**
 * Full-screen text entry in the keyboard app, whichever one the server says handles it (see
 * [LightServiceMethod.GetKeyboardPackage]).
 *
 * Text is shared through files rather than intents, so large text works too. The keyboard is only
 * granted the two files of the current entry, and the grants are revoked as soon as it ends.
 *
 * One entry is open at a time per app: starting another cancels the previous one, which then
 * returns null.
 */
class LightTextEntry internal constructor(context: Context) {

    private val client = client(context)

    /** whether there's a keyboard to enter text with, as last checked. null if it hasn't been checked yet */
    val isKeyboardAvailable: Boolean?
        get() = client.isKeyboardAvailable

    /**
     * Opens the keyboard on [initialText], showing [request] (a title, a layout and the actions
     * the user can finish with), and returns the action the user hit along with what they
     * entered, or null if they cancelled. Throws [ActivityNotFoundException] if there's no
     * keyboard to enter text with.
     */
    suspend fun enterText(
        initialText: String = "",
        request: TextEntryRequest = TextEntryRequest(),
    ): TextEntryClient.Result? = client.enterText(initialText, request)

    /**
     * [enterText], but what the user entered comes back as a stream instead of being read into
     * memory first. read it off the main thread, once, and close it when done (it's [java.io.Closeable],
     * so use { } works). Returns null if they cancelled. Throws [ActivityNotFoundException] if
     * there's no keyboard to enter text with. You probably don't need to use this.
     */
    suspend fun enterTextStream(
        initialText: String = "",
        request: TextEntryRequest = TextEntryRequest(),
    ): TextEntryClient.StreamResult? = client.enterTextStream(initialText, request)

    /**
     * Opens the keyboard on the contents of [source] (empty if null), showing [request], and
     * returns the action the user hit along with a file holding what they entered, or null if
     * they cancelled. [source] is only read. The caller owns the returned file and should delete
     * it when done. Throws [ActivityNotFoundException] if there's no keyboard to enter text with.
     */
    suspend fun editFile(
        source: File? = null,
        request: TextEntryRequest = TextEntryRequest(),
    ): TextEntryClient.FileResult? = client.editFile(source, request)

    internal companion object {
        // one per process, so every LightTextEntry shares the cached keyboard package
        private var client: TextEntryClient? = null

        @Synchronized
        private fun client(context: Context): TextEntryClient =
            client ?: TextEntryClient(context) {
                // older servers don't know the method, which means no keyboard as well
                callRemoteServiceMethod(LightServiceMethod.GetKeyboardPackage, Unit).getOrNull()?.packageName
            }.also { client = it }

        /** asks the server which app handles text entry, and caches the answer */
        suspend fun refreshKeyboardPackage(context: Context) {
            client(context).refreshKeyboardPackage()
        }
    }
}
