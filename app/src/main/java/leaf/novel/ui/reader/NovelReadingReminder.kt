package leaf.novel.ui.reader

/**
 * When the next reading reminder falls due.
 *
 * A reminder here is a nudge rather than an alarm: it only runs while the reader is open, so this
 * is arithmetic over the clock rather than anything handed to the system to schedule.
 *
 * Free of Android types, so the arithmetic can be tested on the JVM.
 */
object NovelReadingReminder {

    private const val MINUTES_PER_DAY = 24 * 60
    private const val MILLIS_PER_MINUTE = 60_000L

    /**
     * Reads a stored `HH:mm` as minutes since midnight, or null when the setting is off.
     *
     * Anything that is not a time reads as off rather than as a failure. The value is typed by
     * hand, so half-written input is the normal case rather than the exceptional one.
     */
    fun minutesOfDay(value: String): Int? {
        val parts = value.split(':')
        if (parts.size != 2) return null

        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null

        return hour * 60 + minute
    }

    /**
     * How long until the clock next reads [target], both given as minutes since midnight.
     *
     * A target already past today waits for tomorrow. A target that is the current minute waits a
     * whole day rather than firing over and over for the sixty seconds it stays true.
     */
    fun millisUntilNext(now: Int, target: Int): Long {
        val difference = target - now
        val minutes = if (difference > 0) difference else difference + MINUTES_PER_DAY
        return minutes * MILLIS_PER_MINUTE
    }
}
