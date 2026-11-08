package dev.brahmkshatriya.echo.history.db

import dev.brahmkshatriya.echo.common.models.Track

/** Same rule as the phone (HistoryEntity.kt): drop heavy fields, keep the routing keys. */
fun Track.toSlimKeepingRoutingKeys(keepPlayable: Boolean = false): Track {
    val slim = copy(
        streamables = emptyList(), extras = emptyMap(), description = null, background = null, subtitle = null,
        genres = emptyList(), plays = null, releaseDate = null, playlistAddedDate = null, isrc = null,
        artists = artists.map { it.copy(bio = null, extras = emptyMap(), background = null, banners = emptyList()) },
        album = album?.copy(description = null, extras = emptyMap(), background = null, artists = emptyList()),
    ).let { if (keepPlayable || it.isPlayable == Track.Playable.Yes) it else it.copy(isPlayable = Track.Playable.Yes) }
    val keep = extras.filterKeys { it == "extension_id" || it == "FALLBACK_ID" }.filterValues { it.isNotEmpty() }
    return if (keep.isEmpty()) slim else slim.copy(extras = keep)
}
