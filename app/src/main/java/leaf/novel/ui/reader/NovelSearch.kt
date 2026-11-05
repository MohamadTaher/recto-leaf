package leaf.novel.ui.reader

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import leaf.novel.api.NovelChapterContent
import org.jsoup.Jsoup
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.chapter.model.Chapter

/**
 * Searching the book, or only the open chapter, for a word or phrase.
 *
 * Only chapters already on the device are read: an imported book, a download, or one this session
 * has fetched. Fetching the rest would be a request per chapter to a site that may be rate limiting,
 * for an answer the reader expects in seconds, so the state counts what was left out instead, and
 * the reader can download those through the app's own downloader. Each one that [arrivals] reports
 * is searched as it lands, and its results slot into place.
 *
 * Each result is a [NovelSpeech.Position] over the blocks speech and the page already number, so
 * the page can mark the very words that matched.
 */
class NovelSearch(
    private val scope: CoroutineScope,
    private val chapters: () -> List<Chapter>,
    private val currentIndex: () -> Int,
    /** The text replacement rules, which the page applies before anything is shown. */
    private val rules: () -> String,
    /** A chapter's cleaned content, or null when it is not on the device. */
    private val content: suspend (Chapter) -> NovelChapterContent?,
    /** Recent queries, newest first, one to a line. */
    private val history: Preference<String>,
    /** Chapters as they finish downloading. */
    arrivals: Flow<Long> = emptyFlow(),
) {
    private val mutableState = MutableStateFlow(NovelSearchState())
    val state: StateFlow<NovelSearchState> = mutableState.asStateFlow()

    /** Searches the reader went on to open a result from, which are the ones worth offering again. */
    val recent: StateFlow<List<String>> = history.changes()
        .map { it.lines().filter(String::isNotBlank) }
        .stateIn(scope, SharingStarted.Eagerly, history.get().lines().filter(String::isNotBlank))

    private var job: Job? = null

    /** The search the state holds, so a late arrival is never added to a newer one. */
    private var generation = 0
    private var pattern: Regex? = null

    /** Chapters the current search could not read, which a download can still add. */
    private val skippedIds = mutableSetOf<Long>()

    init {
        scope.launch { arrivals.collect(::arrived) }
    }

    /** Each chapter's blocks, so typing on searches text already read. Dropped when the rules change. */
    private val blocks = object : LinkedHashMap<Long, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, List<String>>): Boolean {
            if (cachedLength <= CACHE_LENGTH) return false
            cachedLength -= eldest.value.sumOf { it.length }
            return true
        }
    }
    private var cachedLength = 0L
    private var cachedRules: String? = null

    fun setQuery(query: String) {
        if (query == state.value.query) return
        mutableState.update { it.copy(query = query) }
        restart(debounce = true)
    }

    fun setWholeBook(wholeBook: Boolean) = setOptions { it.copy(wholeBook = wholeBook) }

    fun setMatchCase(matchCase: Boolean) = setOptions { it.copy(matchCase = matchCase) }

    fun setWholeWords(wholeWords: Boolean) = setOptions { it.copy(wholeWords = wholeWords) }

    /** A search of the open chapter alone follows the reader into the next one. */
    fun chapterChanged() {
        if (!state.value.wholeBook && state.value.query.isNotBlank()) restart(debounce = false)
    }

    /** Remembered so the list reopens on the result the reader last went to. */
    fun select(result: NovelSearchResult) {
        mutableState.update { it.copy(selected = result.key) }
        val query = state.value.query.trim()
        history.getAndSet { lines ->
            (listOf(query) + lines.lines().filter { it.isNotBlank() && it != query })
                .take(HISTORY_SIZE)
                .joinToString("\n")
        }
    }

    fun clearHistory() = history.delete()

    /** What the current search left out for not being on the device. */
    fun skippedChapters(): Set<Long> = skippedIds.toSet()

    private fun arrived(chapterId: Long) {
        if (!skippedIds.remove(chapterId)) return
        val pattern = pattern ?: return
        val searching = generation
        val index = chapters().indexOfFirst { it.id == chapterId }
        val chapter = chapters().getOrNull(index) ?: return
        scope.launch {
            val found = withContext(Dispatchers.Default) {
                blocksOf(chapter, rules())?.let { search(it, pattern, chapter, index) }
            }
            if (searching != generation) return@launch
            if (found == null) {
                skippedIds += chapterId
                return@launch
            }
            mutableState.update {
                it.copy(
                    results = (it.results + found).sortedWith(READING_ORDER).take(MAX_RESULTS),
                    total = it.total + found.size,
                    chapterCount = it.chapterCount + if (found.isEmpty()) 0 else 1,
                    skipped = it.skipped - 1,
                )
            }
        }
    }

    private fun setOptions(change: (NovelSearchState) -> NovelSearchState) {
        mutableState.update(change)
        restart(debounce = false)
    }

    private fun restart(debounce: Boolean) {
        job?.cancel()
        generation++
        skippedIds.clear()
        val request = state.value
        val pattern = patternOf(request.query, request.matchCase, request.wholeWords)
        this.pattern = pattern
        if (pattern == null) {
            mutableState.update { NovelSearchState(it.query, it.wholeBook, it.matchCase, it.wholeWords) }
            return
        }
        val all = chapters()
        val current = currentIndex()
        val targets = if (request.wholeBook) {
            all.withIndex().toList()
        } else {
            listOfNotNull(all.getOrNull(current)?.let { IndexedValue(current, it) })
        }
        job = scope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            mutableState.update {
                it.copy(
                    results = emptyList(),
                    total = 0,
                    chapterCount = 0,
                    searched = 0,
                    toSearch = targets.size,
                    skipped = 0,
                    running = true,
                    selected = null,
                )
            }
            val rules = rules()
            // In chunks, so results arrive in reading order while several chapters are read at once.
            targets.chunked(PARALLEL_CHAPTERS).forEach { chunk ->
                val found = chunk.map { (index, chapter) ->
                    async(Dispatchers.Default) { blocksOf(chapter, rules)?.let { search(it, pattern, chapter, index) } }
                }.awaitAll()
                ensureActive()
                chunk.zip(found).forEach { (target, results) -> if (results == null) skippedIds += target.value.id }
                mutableState.update { state ->
                    state.copy(
                        // Sorted rather than appended: a download may have slotted in a later chapter.
                        results = (state.results + found.filterNotNull().flatten())
                            .sortedWith(READING_ORDER)
                            .take(MAX_RESULTS),
                        total = state.total + found.sumOf { it?.size ?: 0 },
                        chapterCount = state.chapterCount + found.count { !it.isNullOrEmpty() },
                        searched = state.searched + chunk.size,
                        skipped = state.skipped + found.count { it == null },
                    )
                }
            }
            mutableState.update { it.copy(running = false) }
        }
    }

    private suspend fun blocksOf(chapter: Chapter, rules: String): List<String>? {
        synchronized(blocks) {
            if (cachedRules != rules) {
                blocks.clear()
                cachedLength = 0
                cachedRules = rules
            }
            blocks[chapter.id]?.let { return it }
        }
        val loaded = content(chapter) ?: return null
        val texts = blocksIn(NovelTextReplacements.apply(loaded.html, rules), listOfNotNull(chapter.name, loaded.title))
        synchronized(blocks) {
            if (cachedRules == rules && chapter.id !in blocks) {
                cachedLength += texts.sumOf { it.length }
                blocks[chapter.id] = texts
            }
        }
        return texts
    }

    companion object {
        /** Enough for any phrase worth finding; a word on every page is better refined than listed. */
        const val MAX_RESULTS = 2_000

        private val READING_ORDER = compareBy<NovelSearchResult>(
            { it.chapterIndex },
            { it.position.block },
            { it.position.start },
        )

        private const val DEBOUNCE_MS = 250L
        private const val HISTORY_SIZE = 8
        private const val PARALLEL_CHAPTERS = 8

        /** Characters of chapter text kept between searches, a few hundred chapters' worth. */
        private const val CACHE_LENGTH = 8_000_000L

        private const val CONTEXT_BEFORE = 50
        private const val CONTEXT_AFTER = 160

        /**
         * The query as a pattern, or null when there is nothing to look for.
         *
         * Typed punctuation stands for its typeset forms, since a keyboard gives `'` where the book
         * prints `’`, and any run of spaces for any other.
         */
        fun patternOf(query: String, matchCase: Boolean, wholeWords: Boolean): Regex? {
            val words = query.trim()
            if (words.isEmpty()) return null
            val body = buildString {
                var spaced = false
                for (character in words) {
                    if (character.isWhitespace()) {
                        if (!spaced) append("[\\s\\p{Z}]+")
                        spaced = true
                        continue
                    }
                    spaced = false
                    append(
                        when (character) {
                            in APOSTROPHES -> "['‘’ʼ`]"
                            in QUOTES -> "[\"“”„]"
                            in DASHES -> "[-‐‑‒–—]"
                            else -> Regex.escape(character.toString())
                        },
                    )
                }
            }
            val pattern = if (wholeWords) "(?<![\\p{L}\\p{N}_])$body(?![\\p{L}\\p{N}_])" else body
            return if (matchCase) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
        }

        /**
         * The chapter's blocks as the page numbers them, by index, with any the page leaves out
         * empty: the title the page drops under its own heading would be a result leading nowhere.
         */
        fun blocksIn(html: String, titles: List<String>): List<String> {
            val shown = NovelHtmlSanitizer.dropRepeatedTitle(NovelSpeech.anchorBlocks(html), titles)
            val blocks = Jsoup.parseBodyFragment(shown).select("[${NovelSpeech.BLOCK_ATTRIBUTE}]")
                .associate { it.attr(NovelSpeech.BLOCK_ATTRIBUTE).toInt() to it.text().trim() }
            return List((blocks.keys.maxOrNull() ?: -1) + 1) { blocks[it].orEmpty() }
        }

        /** Every match in one chapter's [blocks], in reading order. */
        fun search(blocks: List<String>, pattern: Regex, chapter: Chapter, chapterIndex: Int): List<NovelSearchResult> =
            blocks.flatMapIndexed { block, text ->
                pattern.findAll(text).map { match ->
                    val (snippet, highlight) = snippetOf(text, match.range)
                    NovelSearchResult(
                        chapterIndex = chapterIndex,
                        chapterName = chapter.name,
                        // The page reads a no-break space as a space, and the mark must find the words.
                        position = NovelSpeech.Position(
                            chapter.id,
                            block,
                            match.range.first,
                            match.value.replace(' ', ' '),
                        ),
                        snippet = snippet,
                        highlight = highlight,
                    )
                }.toList()
            }

        /**
         * A stretch of [text] around [match], cut at spaces, and where the match sits in it.
         *
         * Little before and more after: the words leading up to a match say where it is, and the
         * reader reads on from it.
         */
        fun snippetOf(text: String, match: IntRange): Pair<String, IntRange> {
            var from = (match.first - CONTEXT_BEFORE).coerceAtLeast(0)
            if (from > 0) {
                val space = text.indexOf(' ', from)
                if (space in from..<match.first) from = space + 1
            }
            var to = (match.last + 1 + CONTEXT_AFTER).coerceAtMost(text.length)
            if (to < text.length) {
                val space = text.lastIndexOf(' ', to)
                if (space > match.last) to = space
            }
            val prefix = if (from > 0) "…" else ""
            val suffix = if (to < text.length) "…" else ""
            val start = prefix.length + match.first - from
            return prefix + text.substring(from, to) + suffix to start..<start + match.last + 1 - match.first
        }

        private val APOSTROPHES = setOf('\'', '‘', '’', 'ʼ', '`')
        private val QUOTES = setOf('"', '“', '”', '„')
        private val DASHES = setOf('-', '‐', '‑', '‒', '–', '—')
    }
}

@Immutable
data class NovelSearchState(
    val query: String = "",
    val wholeBook: Boolean = true,
    val matchCase: Boolean = false,
    val wholeWords: Boolean = false,
    /** In reading order, at most [NovelSearch.MAX_RESULTS] of them. */
    val results: List<NovelSearchResult> = emptyList(),
    /** Every match, including any past the listed ones. */
    val total: Int = 0,
    val chapterCount: Int = 0,
    val searched: Int = 0,
    val toSearch: Int = 0,
    /** Chapters not on the device, which were not searched. */
    val skipped: Int = 0,
    val running: Boolean = false,
    val selected: String? = null,
)

@Immutable
data class NovelSearchResult(
    val chapterIndex: Int,
    val chapterName: String,
    /** The match itself, as speech names a place on the page. */
    val position: NovelSpeech.Position,
    val snippet: String,
    /** Where the match sits in [snippet]. */
    val highlight: IntRange,
) {
    val key: String get() = "${position.chapterId}:${position.block}:${position.start}"
}
