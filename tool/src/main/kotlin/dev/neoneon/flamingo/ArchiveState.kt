package dev.neoneon.flamingo

/** The online half of the archive. It comes from the server, so it can be loading, closed or failed. */
sealed interface OnlineArchive {
    data object Loading : OnlineArchive

    /** Carries the local player id and the seats' names, so each row titles itself as the games list does. */
    data class Loaded(
        val games: List<Game>,
        val playerId: String,
        val names: Map<String, String> = emptyMap(),
    ) : OnlineArchive

    /** The service is `disabled`; its message stands in for the rows. */
    data class Unavailable(val message: ServiceStatus.Message) : OnlineArchive

    data class Failed(val message: String) : OnlineArchive
}

/**
 * What [ArchiveScreen] shows: online games this seat archived, read from the server, and local
 * games saved on this phone. Mirrors iOS's `ArchiveView`, Online first.
 */
data class ArchiveState(
    val online: OnlineArchive = OnlineArchive.Loading,
    /** Newest first; `null` until the archive on this phone has been read. */
    val localGames: List<ArchivedHotSeatGame>? = null,
) {
    /**
     * Nothing worth showing but "Loading…": the local games haven't been read yet, or there are
     * none and the server hasn't answered. Local games on their own are worth showing straight away.
     */
    val isLoading: Boolean
        get() = localGames == null || (localGames.isEmpty() && online is OnlineArchive.Loading)

    /** Both halves have answered and neither holds a game, so the whole screen can say so. */
    val isEmpty: Boolean
        get() = localGames?.isEmpty() == true && (online as? OnlineArchive.Loaded)?.games?.isEmpty() == true
}
