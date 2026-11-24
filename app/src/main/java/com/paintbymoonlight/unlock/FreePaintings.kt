package com.paintbymoonlight.unlock

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The paintings that need no purchase: [MOST] to begin with and, once one is used, one back at
 * each midnight, never more than [MOST] waiting. Every new painting takes one.
 *
 * Counted on the device alone: a reinstall starts over and a changed clock moves the days, which
 * is accepted rather than sending anything anywhere. Days are counted between local midnights
 * where the painter is now, so a time zone crossed or a change of daylight saving time moves the
 * count by no more than the clock did; a clock set back before the count began gives them all
 * back rather than stalling them until it catches up. The same rules as the iOS app's.
 */
class FreePaintings(left: Int = MOST, since: Instant? = null) {
    /** Free paintings left, as of [since]. */
    val left: Int = left.coerceIn(0, MOST)

    /** When the count of days began (the start of the day one was used, or the last one came back); null while [MOST] are left. */
    val since: Instant? = if (this.left < MOST) since else null

    /** The count as of [now]: one back for each midnight passed since [since]. */
    fun at(now: Instant, zone: ZoneId): FreePaintings {
        val since = since
        if (left >= MOST || since == null) return FreePaintings()
        val today = now.atZone(zone).toLocalDate()
        val first = since.atZone(zone).toLocalDate()
        if (first > today) return FreePaintings()
        val days = ChronoUnit.DAYS.between(first, today)
        if (days <= 0) return this
        return FreePaintings((left + days.coerceAtMost(MOST.toLong())).toInt(), today.atStartOfDay(zone).toInstant())
    }

    /** The count once one free painting is taken at [now]; null when none is left. */
    fun take(now: Instant, zone: ZoneId): FreePaintings? {
        val current = at(now, zone)
        if (current.left == 0) return null
        return FreePaintings(current.left - 1, current.since ?: now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant())
    }

    override fun equals(other: Any?) = other is FreePaintings && other.left == left && other.since == since
    override fun hashCode() = 31 * left + (since?.hashCode() ?: 0)
    override fun toString() = "FreePaintings(left=$left, since=$since)"

    companion object {
        const val MOST = 5
    }
}
