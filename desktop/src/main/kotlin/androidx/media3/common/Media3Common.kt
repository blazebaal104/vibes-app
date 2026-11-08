package androidx.media3.common

import android.net.Uri
import android.os.Bundle

object C {
    const val TIME_UNSET = Long.MIN_VALUE + 1
    const val INDEX_UNSET = -1
    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_FLOAT = 4
    const val ENCODING_INVALID = 0
    const val LENGTH_UNSET = -1L
    const val RESULT_END_OF_INPUT = -1
}

class MediaMetadata private constructor(b: Builder) {
    val title: CharSequence? = b.title
    val artist: CharSequence? = b.artist
    val albumTitle: CharSequence? = b.albumTitle
    val artworkUri: Uri? = b.artworkUri
    val extras: Bundle? = b.extras
    val displayTitle: CharSequence? = b.title
    fun buildUpon() = Builder().setTitle(title).setArtist(artist).setAlbumTitle(albumTitle).setArtworkUri(artworkUri).setExtras(extras)
    class Builder {
        internal var title: CharSequence? = null; internal var artist: CharSequence? = null
        internal var albumTitle: CharSequence? = null; internal var artworkUri: Uri? = null; internal var extras: Bundle? = null
        fun setTitle(v: CharSequence?) = apply { title = v }
        fun setDisplayTitle(v: CharSequence?) = apply { title = v }
        fun setArtist(v: CharSequence?) = apply { artist = v }
        fun setAlbumTitle(v: CharSequence?) = apply { albumTitle = v }
        fun setArtworkUri(v: Uri?) = apply { artworkUri = v }
        fun setExtras(v: Bundle?) = apply { extras = v }
        fun setIsPlayable(v: Boolean?) = this
        fun setIsBrowsable(v: Boolean?) = this
        fun build() = MediaMetadata(this)
    }
    companion object { val EMPTY = Builder().build() }
}

/**
 * A queue entry. On desktop it can also carry [desktopSource]: how to open its audio (Deezer tracks are
 * resolved through the extension, so they have no plain URL).
 */
class MediaItem private constructor(
    val mediaId: String,
    val localConfiguration: LocalConfiguration?,
    val mediaMetadata: MediaMetadata,
    /** desktop: lazily opens this item's bytes (used for Deezer tracks, offline files, uploads) */
    val desktopSource: (suspend () -> dev.brahmkshatriya.echo.vibes.desktop.audio.ByteSource)? = null,
    /** desktop: whatever the owner wants to keep with the item (the Track, its extension…) */
    val desktopTag: Any? = null,
) {
    class LocalConfiguration(val uri: Uri, val mimeType: String? = null, val tag: Any? = null)
    class ClippingConfiguration(val startPositionMs: Long, val endPositionMs: Long) {
        class Builder {
            private var s = 0L; private var e = Long.MIN_VALUE
            fun setStartPositionMs(v: Long) = apply { s = v }
            fun setEndPositionMs(v: Long) = apply { e = v }
            fun build() = ClippingConfiguration(s, e)
        }
    }
    val requestMetadata = RequestMetadata()
    class RequestMetadata { val mediaUri: Uri? = null; val extras: Bundle? = null }

    fun buildUpon() = Builder().setMediaId(mediaId).setMediaMetadata(mediaMetadata).also { b ->
        localConfiguration?.let { b.setUri(it.uri); b.setTag(it.tag) }
        b.desktopSource = desktopSource; b.desktopTag = desktopTag
    }

    class Builder {
        private var id = ""; private var uri: Uri? = null; private var tag: Any? = null; private var mime: String? = null
        private var meta = MediaMetadata.EMPTY
        var desktopSource: (suspend () -> dev.brahmkshatriya.echo.vibes.desktop.audio.ByteSource)? = null
        var desktopTag: Any? = null
        fun setMediaId(v: String) = apply { id = v }
        fun setUri(v: String?) = apply { uri = v?.let(Uri::parse) }
        fun setUri(v: Uri?) = apply { uri = v }
        fun setTag(v: Any?) = apply { tag = v }
        fun setMimeType(v: String?) = apply { mime = v }
        fun setMediaMetadata(v: MediaMetadata) = apply { meta = v }
        fun setClippingConfiguration(c: ClippingConfiguration) = apply { clip = c }
        private var clip: ClippingConfiguration? = null
        fun setDesktopSource(v: (suspend () -> dev.brahmkshatriya.echo.vibes.desktop.audio.ByteSource)?) = apply { desktopSource = v }
        fun setDesktopTag(v: Any?) = apply { desktopTag = v }
        fun build() = MediaItem(id.ifEmpty { uri?.toString() ?: "item" }, uri?.let { LocalConfiguration(it, mime, tag) }, meta, desktopSource, desktopTag)
    }

    override fun equals(other: Any?) = other is MediaItem && other.mediaId == mediaId && other.localConfiguration?.uri == localConfiguration?.uri
    override fun hashCode() = mediaId.hashCode()

    companion object {
        val EMPTY = Builder().build()
        fun fromUri(uri: String) = Builder().setUri(uri).build()
        fun fromUri(uri: Uri) = Builder().setUri(uri).build()
    }
}

open class PlaybackException(message: String?, cause: Throwable?, val errorCode: Int) : Exception(message, cause) {
    val errorCodeName: String get() = getErrorCodeName(errorCode)
    companion object {
        const val ERROR_CODE_UNSPECIFIED = 1000
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
        const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
        const val ERROR_CODE_IO_UNSPECIFIED = 2000
        const val ERROR_CODE_DECODING_FAILED = 4003
        const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3003
        fun getErrorCodeName(code: Int) = when (code) {
            ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
            ERROR_CODE_IO_BAD_HTTP_STATUS -> "ERROR_CODE_IO_BAD_HTTP_STATUS"
            ERROR_CODE_IO_UNSPECIFIED -> "ERROR_CODE_IO_UNSPECIFIED"
            ERROR_CODE_DECODING_FAILED -> "ERROR_CODE_DECODING_FAILED"
            ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"
            else -> "ERROR_CODE_UNSPECIFIED"
        }
    }
}

open class Timeline(val windowCount: Int = 0) {
    val isEmpty get() = windowCount == 0
    companion object { val EMPTY = Timeline(0) }
}

data class PlaybackParameters(val speed: Float = 1f, val pitch: Float = 1f) {
    companion object { val DEFAULT = PlaybackParameters() }
}

interface Player {
    val isPlaying: Boolean
    var playWhenReady: Boolean
    val playbackState: Int
    val currentPosition: Long
    val contentPosition: Long get() = currentPosition
    val bufferedPosition: Long
    val duration: Long
    val contentDuration: Long get() = duration
    val currentMediaItem: MediaItem?
    val currentMediaItemIndex: Int
    val mediaItemCount: Int
    val nextMediaItemIndex: Int
    val previousMediaItemIndex: Int
    var repeatMode: Int
    var shuffleModeEnabled: Boolean
    var volume: Float
    val playerError: PlaybackException?
    val currentTimeline: Timeline
    var playbackParameters: PlaybackParameters
    val isLoading: Boolean get() = playbackState == STATE_BUFFERING

    fun getMediaItemAt(index: Int): MediaItem
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean
    fun play()
    fun pause()
    fun prepare()
    fun stop()
    fun release()
    fun seekTo(positionMs: Long)
    fun seekTo(index: Int, positionMs: Long)
    fun seekToDefaultPosition(index: Int) = seekTo(index, 0)
    fun seekToNextMediaItem()
    fun seekToPreviousMediaItem()
    fun seekToNext() = seekToNextMediaItem()
    fun seekToPrevious()
    fun setPlaybackSpeed(speed: Float)
    fun setMediaItem(item: MediaItem)
    fun setMediaItem(item: MediaItem, startPositionMs: Long) = setMediaItems(listOf(item), 0, startPositionMs)
    fun setMediaItems(items: List<MediaItem>)
    fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun addMediaItem(item: MediaItem) = addMediaItems(listOf(item))
    fun addMediaItems(items: List<MediaItem>)
    fun addMediaItems(index: Int, items: List<MediaItem>)
    fun removeMediaItem(index: Int)
    fun removeMediaItems(from: Int, to: Int)
    fun moveMediaItem(from: Int, to: Int)
    fun replaceMediaItem(index: Int, item: MediaItem)
    fun clearMediaItems()
    fun addListener(l: Listener)
    fun removeListener(l: Listener)

    class Events(private val set: Set<Int>) {
        fun contains(e: Int) = e in set
        fun containsAny(vararg e: Int) = e.any { it in set }
        fun size() = set.size
    }

    class PositionInfo(val mediaItemIndex: Int, val positionMs: Long, val mediaItem: MediaItem? = null) {
        val contentPositionMs get() = positionMs
    }

    interface Listener {
        fun onEvents(player: Player, events: Events) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onPlayerErrorChanged(error: PlaybackException?) {}
        fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {}
        fun onTimelineChanged(timeline: Timeline, reason: Int) {}
        fun onRepeatModeChanged(repeatMode: Int) {}
        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
        fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}
        fun onVolumeChanged(volume: Float) {}
        fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {}
    }

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT = 0
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK = 2
        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 3
        const val PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST = 1
        const val PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM = 5
        const val DISCONTINUITY_REASON_AUTO_TRANSITION = 0
        const val DISCONTINUITY_REASON_SEEK = 1
        const val TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED = 0
        const val EVENT_TIMELINE_CHANGED = 0
        const val EVENT_MEDIA_ITEM_TRANSITION = 1
        const val EVENT_PLAYBACK_STATE_CHANGED = 4
        const val EVENT_PLAY_WHEN_READY_CHANGED = 5
        const val EVENT_IS_PLAYING_CHANGED = 7
        const val EVENT_REPEAT_MODE_CHANGED = 8
        const val EVENT_SHUFFLE_MODE_ENABLED_CHANGED = 9
        const val EVENT_PLAYER_ERROR = 10
        const val EVENT_POSITION_DISCONTINUITY = 11
        const val EVENT_PLAYBACK_PARAMETERS_CHANGED = 12
    }
}
