package leaf.novel.ui.reader

/**
 * Where speech is in its chapter as time, for the system media player's seek bar.
 *
 * The engine reports which unit it is on, never how long a chapter takes, so characters become
 * time at a rate measured from speech itself, starting from an average voice. The bar's scale is
 * fixed per chapter so the bar does not rescale under the listener; any drift between that scale
 * and the measured rate goes into the playback speed, which the player moves the bar by between
 * units.
 *
 * Free of Android types, so the arithmetic can be tested on the JVM.
 */
internal class NovelSpeechClock(private val now: () -> Long) {

    data class Reading(val position: Long, val duration: Long, val speed: Float)

    data class ChapterChars(val chapterId: Long, val before: Int, val total: Int)

    private var msPerChar = DEFAULT_MS_PER_CHAR
    private var samples = 0
    private var scale = DEFAULT_MS_PER_CHAR
    private var chapterId: Long? = null
    private var lastChars = 0
    private var lastAt = 0L
    private var lastPlaying = false

    fun read(chapterId: Long, charsBefore: Int, total: Int, playing: Boolean): Reading {
        val at = now()
        // Only uninterrupted speech moving forward through one chapter says how fast it talks.
        if (playing && lastPlaying && chapterId == this.chapterId && charsBefore > lastChars) {
            calibrate((at - lastAt).toFloat() / (charsBefore - lastChars))
        }
        if (chapterId != this.chapterId || msPerChar / scale !in DRIFT) scale = msPerChar
        this.chapterId = chapterId
        lastChars = charsBefore
        lastAt = at
        lastPlaying = playing
        return Reading(
            position = (charsBefore * scale).toLong(),
            duration = (total * scale).toLong(),
            speed = if (playing) scale / msPerChar else 0f,
        )
    }

    private fun calibrate(sample: Float) {
        // A seek moves the unit without the time to say it, far off any voice's pace.
        if (samples >= TRUSTED_SAMPLES && sample / msPerChar !in PLAUSIBLE) return
        val bounded = sample.coerceIn(MIN_MS_PER_CHAR, MAX_MS_PER_CHAR)
        msPerChar = if (samples == 0) bounded else msPerChar * (1 - WEIGHT) + bounded * WEIGHT
        samples++
    }

    companion object {
        /** Roughly 150 words a minute, a typical voice at normal speed. */
        const val DEFAULT_MS_PER_CHAR = 70f
        private const val MIN_MS_PER_CHAR = 15f
        private const val MAX_MS_PER_CHAR = 400f
        private const val WEIGHT = 0.3f
        private const val TRUSTED_SAMPLES = 3
        private val DRIFT = 0.8f..1.25f
        private val PLAUSIBLE = 0.4f..2.5f

        /** The chapter unit [index] is in, the characters before it there, and the chapter's total. */
        fun chapterChars(positions: List<NovelSpeech.Position>, index: Int): ChapterChars? {
            val chapterId = positions.getOrNull(index)?.chapterId ?: return null
            var before = 0
            var total = 0
            positions.forEachIndexed { i, position ->
                if (position.chapterId != chapterId) return@forEachIndexed
                if (i < index) before += position.text.length
                total += position.text.length
            }
            return ChapterChars(chapterId, before, total)
        }
    }
}
