package dev.brahmkshatriya.echo.vibes.listen

import kotlin.math.abs

/**
 * Pure maths of the listening lobby (unit-tested, no Android):
 *  - the server clock offset, from round trips (the sample with the smallest round trip wins,
 *    its error is at most half of that round trip, usually a few ms);
 *  - where the music must be right now, from the host's anchor (song index + position at a
 *    server instant) walking across song durations, so every phone computes the same answer;
 *  - how to correct a phone that is off: tiny errors are ignored, small ones are absorbed by
 *    playing 1-4 % faster/slower for a few seconds (no audible jump), big ones by a seek.
 */
object LobbySync {
    /** One round trip: local send time, server time, local receive time (ms). */
    data class Sample(val sentLocal: Long, val server: Long, val recvLocal: Long) {
        val rtt get() = recvLocal - sentLocal
        val offset get() = server - (sentLocal + recvLocal) / 2
    }

    /** Server minus local clock, from the most precise sample; null if none is usable. */
    fun offset(samples: List<Sample>): Long? =
        samples.filter { it.rtt in 0..3000 }.minByOrNull { it.rtt }?.offset

    /** Upper bound of the error of [offset]. */
    fun offsetError(samples: List<Sample>): Long? = samples.filter { it.rtt in 0..3000 }.minOfOrNull { it.rtt }?.let { it / 2 }

    data class Position(val index: Int, val posMs: Long, val ended: Boolean)

    /**
     * Where playback must be at [serverNow]: song [index] was at [anchorPos] ms at server time
     * [anchorServer]. [durations] in ms (null = unknown: stays on that song).
     */
    fun expected(index: Int, anchorPos: Long, anchorServer: Long, playing: Boolean, serverNow: Long, durations: List<Long?>): Position {
        if (durations.isEmpty()) return Position(0, 0, true)
        var i = index.coerceIn(0, durations.size - 1)
        var pos = anchorPos + if (playing) serverNow - anchorServer else 0
        if (pos < 0) return Position(i, pos, false) // start scheduled in the future: negative = not yet
        while (true) {
            val d = durations[i] ?: return Position(i, pos, false)
            if (pos < d) return Position(i, pos, false)
            if (i == durations.size - 1) return Position(i, d, true)
            pos -= d; i++
        }
    }

    sealed interface Fix {
        data object None : Fix
        /** Play at this speed for a while (1.0 = normal). */
        data class Speed(val speed: Float) : Fix
        data class Seek(val toMs: Long) : Fix
    }

    /**
     * [errMs] = my position - expected position (positive = I am ahead).
     * Dead band 20 ms, speed correction up to 250 ms (gentle: 4 % max = 40 ms recovered per s),
     * seek beyond that (landing [seekLatency] ms ahead to compensate the time a seek takes).
     */
    fun fix(errMs: Long, expectedMs: Long, seekLatency: Long = 0): Fix = when {
        abs(errMs) <= 20 -> Fix.None
        abs(errMs) <= 250 -> Fix.Speed((1f - errMs / 2500f).coerceIn(0.96f, 1.04f))
        else -> Fix.Seek((expectedMs + seekLatency).coerceAtLeast(0))
    }

    /** Median of recent errors: one noisy reading must not trigger a correction. */
    fun median(xs: List<Long>): Long = xs.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
}
