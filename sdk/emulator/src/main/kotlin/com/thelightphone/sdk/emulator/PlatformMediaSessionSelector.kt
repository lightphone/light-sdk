package com.thelightphone.sdk.emulator

/** An active platform media session, as far as picking which one to show goes. */
internal data class SelectableMediaSession(
    val id: String,
    val playing: Boolean,
    val displayable: Boolean,
)

/**
 * Picks the session the lock screen shows: the current one while it plays, else
 * the first playing one in Android's order, else the current one while it can
 * still be shown, else the first one that can. Mirrors LightOS.
 */
internal fun selectPlatformMediaSession(
    sessions: List<SelectableMediaSession>,
    currentId: String?,
): String? {
    val current = sessions.firstOrNull { it.displayable && it.id == currentId }
    if (current != null && current.playing) return current.id
    sessions.firstOrNull { it.displayable && it.playing }?.let { return it.id }
    if (current != null) return current.id
    return sessions.firstOrNull { it.displayable }?.id
}
