package dev.brahmkshatriya.echo.vibes.backend

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.history.db.toSlimKeepingRoutingKeys
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A song as Vibes stores and sends it: display fields for any device, plus the slim engine Track
 * (as JSON) so a tap on another phone can play it through the same extension.
 */
@Serializable
data class SongRef(
    val ext: String,
    val id: String,
    val title: String,
    val artist: String,
    val cover: String? = null,
    val album: String? = null,
    val duration: Long? = null,
    val data: String? = null,
) {
    fun toTrack(): Track = data?.toData<Track>()?.getOrNull() ?: Track(
        id = id,
        title = title,
        cover = cover?.toImageHolder(),
        artists = artist.split(", ").filter { it.isNotBlank() }.map { Artist(it, it) },
        album = album?.let { Album(it, it) },
        duration = duration,
    )

    val key get() = "$ext:$id"

    companion object {
        fun ImageHolder?.url(): String? = (this as? ImageHolder.NetworkRequestImageHolder)?.request?.url

        fun from(ext: String, t: Track) = SongRef(
            ext = ext,
            id = t.id,
            title = t.title,
            artist = t.artists.joinToString(", ") { it.name },
            cover = (t.cover ?: t.album?.cover).url(),
            album = t.album?.title,
            duration = t.duration,
            data = runCatching { t.toSlimKeepingRoutingKeys().toJson() }.getOrNull(),
        )
    }
}

@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String = "",
    val bio: String = "",
    @SerialName("avatar_color") val avatarColor: String = "#B84DFF",
    @SerialName("avatar_emoji") val avatarEmoji: String = "🎧",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("invite_code") val inviteCode: String = "",
    val top4: List<SongRef> = emptyList(),
    @SerialName("now_playing") val nowPlaying: SongRef? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("fav_artist") val favArtist: FavArtist? = null,
    @SerialName("fav_song") val favSong: SongRef? = null,
    @SerialName("fav_playlist") val favPlaylist: FavPlaylist? = null,
    @SerialName("stats_week") val statsWeek: WeekSummary? = null,
    val records: Records? = null,
    val privacy: Privacy = Privacy(),
) {
    val name get() = displayName.ifBlank { username }
}

@Serializable
data class FavArtist(val id: Long = 0, val name: String, val image: String? = null)

/** A playlist pinned on the profile; [sharedId] points at vibes_shared_playlists so anyone can save it. */
@Serializable
data class FavPlaylist(val sharedId: String, val name: String, val cover: String? = null, val count: Int = 0, val emoji: String = "🎵", val color: String = "#B84DFF")

@Serializable
data class RankedArtist(val name: String, val image: String? = null, val minutes: Int = 0)

@Serializable
data class RankedSong(val song: SongRef, val plays: Int = 0, val minutes: Int = 0)

@Serializable
data class RankedName(val name: String, val minutes: Int = 0, val cover: String? = null, val id: String? = null)

/** One week of listening, Monday to Sunday (also what friends see for the current week). */
@Serializable
data class WeekSummary(
    val weekStart: String,               // ISO date of the Monday
    val minutes: Int = 0,
    val plays: Int = 0,
    val artists: List<RankedArtist> = emptyList(),
    val songs: List<RankedSong> = emptyList(),
    val playlists: List<RankedName> = emptyList(),
    val genres: List<RankedName> = emptyList(),
    val newArtists: Int = 0,
    val activeDays: Int = 0,
)

@Serializable
data class Records(
    val mostPlaysSong: SongRef? = null,
    val mostPlays: Int = 0,
    val mostPlaysWeek: String? = null,
    val mostMinutes: Int = 0,
    val mostMinutesWeek: String? = null,
    val longestStreakDays: Int = 0,
)

@Serializable
data class Privacy(
    val nowPlaying: Boolean = true,
    val weekStats: Boolean = true,
    val records: Boolean = true,
    val favorites: Boolean = true,
)

@Serializable
data class Message(
    val id: Long = 0,
    @SerialName("sender_id") val senderId: String,
    @SerialName("receiver_id") val receiverId: String,
    val kind: String = "text",
    val body: String = "",
    val payload: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
) {
    /** Local-only: still being sent (single grey tick). */
    @kotlinx.serialization.Transient
    var pending: Boolean = false
}

@Serializable
data class FriendRequest(
    val id: String,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    val status: String = "pending",
)

@Serializable
data class SharedPlaylist(
    val id: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    val name: String,
    val tracks: List<SongRef> = emptyList(),
)

@Serializable
data class Lobby(
    val id: String,
    val game: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("guest_id") val guestId: String,
    val status: String,
    val state: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class StoryStyle(
    val top: String = "#B84DFF",
    val bottom: String = "#120A1F",
    val preview: String? = null,
)

@Serializable
data class Story(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val song: SongRef,
    val caption: String = "",
    val style: StoryStyle = StoryStyle(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** All active stories of one person, in order, plus whether I've seen them all. */
data class StoryGroup(val user: Profile, val stories: List<Story>, val seen: Set<String>) {
    val allSeen get() = stories.all { it.id in seen }
}
