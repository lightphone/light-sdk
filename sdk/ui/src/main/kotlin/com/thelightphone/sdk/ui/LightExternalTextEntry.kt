package com.thelightphone.sdk.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.thelightphone.lp3Keyboard.textentry.TextEntryRequest

/**
 * Full-screen text entry in a separate keyboard app, used by [LightTextInputEditor] in place of
 * its embedded keyboard when one is available.
 */
interface LightExternalTextEntry {
    /**
     * whether there's a keyboard app, as last checked. null if it hasn't been checked yet. lets the
     * editor skip building its embedded keyboard when the keyboard app is about to open over it.
     */
    val isAvailable: Boolean?

    /** opens the keyboard on [initialText], showing [request], and suspends until the user is done */
    suspend fun enterText(initialText: String, request: TextEntryRequest): Result

    sealed interface Result {
        /** the user finished with the action [actionId] */
        data class Saved(val actionId: String, val text: String) : Result
        data object Cancelled : Result

        /** there's no keyboard app to enter text with */
        data object Unavailable : Result
    }
}

/**
 * Provided by the client's LightActivity, backed by its LightTextEntry. null where there's no
 * external text entry, e.g. previews.
 */
val LocalExternalTextEntry = staticCompositionLocalOf<LightExternalTextEntry?> { null }
