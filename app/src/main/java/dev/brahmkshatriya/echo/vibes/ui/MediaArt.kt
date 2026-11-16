package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.vibes.backend.SongRef.Companion.url
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture of anything shown as a card (album, playlist, artist, daily mix).
 *
 * Fixes two things Deezer hands us:
 *  - "Made for you" mixes: their CDN cover is a TEMPLATE (a flat colour band on top where Deezer's own
 *    app paints the title, two artist photos below). Shown as-is it looked half empty. The extension
 *    passes the artist photos in extras["collage"]: we draw our own grid instead.
 *  - Artists with no photo: Deezer serves a grey silhouette (the md5 of an empty string). We show the
 *    cover of their most popular release instead, or a coloured initial.
 */
@Composable
fun MediaArt(item: EchoMediaItem, modifier: Modifier, shape: Shape) {
    val collage = item.extras["collage"]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    if (collage.size >= 2) { CollageArt(collage, modifier, shape); return }
    val url = item.cover.url()
    if (item is Artist && isBlankPicture(url)) { ArtistFallbackArt(item, modifier, shape); return }
    Cover(url, modifier, shape)
}

private const val EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e"

/** Deezer's "no picture" image: empty md5 or an empty path segment. */
fun isBlankPicture(url: String?): Boolean =
    url.isNullOrBlank() || EMPTY_MD5 in url || "/artist//" in url || "/images/artist/-" in url

@Composable
fun CollageArt(urls: List<String>, modifier: Modifier, shape: Shape) {
    Box(modifier.clip(shape)) {
        if (urls.size == 2 || urls.size == 3) Row(Modifier.fillMaxSize()) {
            urls.take(2).forEach { Cover(it, Modifier.weight(1f).fillMaxSize(), RectangleShape) }
        } else Column(Modifier.fillMaxSize()) {
            for (r in 0 until 2) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until 2) Cover(urls[r * 2 + c], Modifier.weight(1f).fillMaxSize(), RectangleShape)
            }
        }
    }
}

private object ArtistArt {
    val found = ConcurrentHashMap<String, String>()
    val none = ConcurrentHashMap.newKeySet<String>()
}

@Composable
private fun ArtistFallbackArt(a: Artist, modifier: Modifier, shape: Shape) {
    val key = a.id
    var alt by remember(key) { mutableStateOf(ArtistArt.found[key]) }
    LaunchedEffect(key) {
        if (alt != null || key in ArtistArt.none) return@LaunchedEffect
        val id = key.substringAfterLast(':').toLongOrNull()
        val pic = id?.let { i ->
            runCatching {
                val d = dev.brahmkshatriya.echo.vibes.VibesGraph.deezer
                d.artistInfo(i)?.picture?.takeUnless { isBlankPicture(it) }
                    ?: d.artistTop(i, 1).firstOrNull()?.cover
                    ?: d.artistAlbums(i).firstOrNull()?.cover
            }.getOrNull()
        }
        if (pic != null) { ArtistArt.found[key] = pic; alt = pic } else ArtistArt.none += key
    }
    val u = alt
    if (u != null) Cover(u, modifier, shape)
    else Box(
        modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF7B2FF7), Color(0xFFC2187A), Color(0xFFFF6A3D)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(a.name.trim().take(1).uppercase(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    }
}
