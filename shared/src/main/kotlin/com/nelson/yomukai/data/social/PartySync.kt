package com.nelson.yomukai.data.social

import kotlin.math.abs
import kotlin.random.Random

/**
 * What a watch party is playing, as the host last said, stamped with the server's clock. Everyone works out where the
 * video should be now from it; nobody trusts their own clock against someone else's.
 */
data class PartyState(
    val sourceId: String,
    val mediaId: String,
    val chapterId: String,
    val title: String = "",
    val episode: String = "",
    /** Name of the video server the host is using, so others pick the same one when they can. */
    val server: String = "",
    /** Seconds into the episode when [at] was stamped. */
    val position: Double = 0.0,
    val playing: Boolean = false,
    val speed: Double = 1.0,
    /** Server time (ms) of [position]. */
    val at: Long = 0
)

/** What a client should do to stay with the party. */
sealed interface Correction {
    data object None : Correction
    data class Seek(val to: Double) : Correction

    /** Play a little faster or slower until caught up (then back to the party's speed). */
    data class Rate(val rate: Double) : Correction
}

object PartySync {
    /** More than this apart: jump. */
    const val SEEK_THRESHOLD = 2.0

    /** Less than this apart: leave it, nobody notices. */
    const val NUDGE_THRESHOLD = 0.3

    /** Someone loading pauses the party; after this long the rest carry on without them. */
    const val READY_TIMEOUT_MS = 10_000L

    /** Where the video should be at server time [now]. */
    fun expectedPosition(state: PartyState, now: Long): Double =
        if (!state.playing) state.position else state.position + (now - state.at).coerceAtLeast(0) / 1000.0 * state.speed

    /** How a client at [local] seconds catches up with [expected]: seek when far, nudge the speed when close, else nothing. */
    fun correction(local: Double, expected: Double, speed: Double = 1.0): Correction {
        val drift = local - expected
        return when {
            abs(drift) > SEEK_THRESHOLD -> Correction.Seek(expected)
            abs(drift) > NUDGE_THRESHOLD -> Correction.Rate(speed * if (drift < 0) 1.05 else 0.95)
            else -> Correction.None
        }
    }

    /** The party resumes once nobody is loading any more, or [READY_TIMEOUT_MS] after the wait began. */
    fun canResume(loading: Set<Long>, waitingSince: Long?, now: Long): Boolean =
        loading.isEmpty() || (waitingSince != null && now - waitingSince >= READY_TIMEOUT_MS)

    /** A paused copy of [state] frozen where it was at [now] (the party waits there for whoever is loading). */
    fun pausedAt(state: PartyState, now: Long): PartyState = state.copy(position = expectedPosition(state, now), playing = false, at = now)

    /** Playing again from where it was paused, stamped [now]. */
    fun resumedAt(state: PartyState, now: Long): PartyState = state.copy(playing = true, at = now)

    /**
     * Who hosts once [leaving] goes: nobody changes while the host stays; otherwise whoever has been in the room the
     * longest (members in joining order). Null when the room is left empty.
     */
    fun nextHost(currentHost: Long?, members: List<Long>, leaving: Long?): Long? {
        val staying = members.filter { it != leaving }
        return when {
            currentHost != null && currentHost != leaving && currentHost in staying -> currentHost
            else -> staying.firstOrNull()
        }
    }

    /** Whether [user] may change what plays: the host, or anyone when the host allowed it. */
    fun canControl(user: Long, host: Long?, everyoneControls: Boolean): Boolean = everyoneControls || user == host
}

/** Short room codes people can read out or type: no 0/O, 1/I/L, nor letters that look alike. */
object RoomCodes {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    fun generate(random: Random = Random.Default): String = (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** What someone typed (lower case, spaces, dashes) as a code, or null when it cannot be one. */
    fun normalize(input: String): String? {
        val clean = input.trim().uppercase().filter { !it.isWhitespace() && it != '-' }
        return clean.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }
}
