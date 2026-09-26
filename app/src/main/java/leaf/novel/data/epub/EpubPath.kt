package leaf.novel.data.epub

import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * Path arithmetic for archive-internal EPUB references.
 *
 * Kept free of Android types on purpose: every chapter URL in the database is produced by [resolve],
 * so this is the one piece of the EPUB layer that most needs tests, and the unit-test source set has
 * no Android framework available (`android.jar` methods throw "not mocked").
 *
 * `EpubReader.resolveZipPath` upstream does the same job with `File(...).canonicalPath`, which is
 * avoided here: that consults the real filesystem, so an EPUB with a top-level folder that happens to
 * be an Android symlink (`etc`, `bin`, `sdcard`) resolves to a path no archive entry matches. It also
 * costs a syscall per manifest and TOC entry, of which a novel has hundreds.
 */
object EpubPath {

    const val FORWARD_SLASH = "/"
    const val BACKSLASH = "\\"

    /** Either separator is accepted on input; the archive's own is used on output. */
    private val SEPARATORS = Regex("""[/\\]""")

    /**
     * Resolves [relative] against [base], both archive-internal paths, emitting [separator].
     *
     * Hrefs in the OPF are relative to the OPF's own directory, and hrefs inside a document are
     * relative to that document.
     */
    fun resolve(base: String, relative: String, separator: String): String {
        val href = decodeHref(relative)
        val absolute = href.startsWith('/') || href.startsWith('\\')
        return normalize(if (absolute || base.isEmpty()) href else "$base/$href", separator)
    }

    /** Lexical `.` and `..` collapsing. Never touches the filesystem. */
    fun normalize(path: String, separator: String): String {
        val segments = ArrayDeque<String>()
        path.split(SEPARATORS).forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString(separator)
    }

    /** The directory containing [path], or an empty string when it is at the archive root. */
    fun parentOf(path: String, separator: String): String {
        val index = path.lastIndexOf(separator)
        return if (index >= 0) path.substring(0, index) else ""
    }

    /** Rewrites a `/`-separated literal to the archive's separator. */
    fun withSeparator(path: String, separator: String): String = path.replace(FORWARD_SLASH, separator)

    /**
     * An archive path as a URL path, [percentDecode]'s inverse. Each segment is escaped on its own,
     * so a `#` or `%` in a name stays part of it rather than ending or re-encoding the URL.
     */
    fun urlPathOf(path: String): String = path.split(SEPARATORS).joinToString(FORWARD_SLASH) {
        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }

    /** Strips the fragment and percent-decodes, since OPF and TOC hrefs are URL references. */
    fun decodeHref(href: String): String = percentDecode(href.substringBefore('#'))

    /**
     * Percent-decoding with `Uri.decode`'s contract: escapes only, `+` left alone. Deliberately not
     * `URLDecoder`, which applies form encoding and would turn a `+` in a filename into a space.
     *
     * Differs from `Uri.decode` in one way, on purpose: a malformed escape is kept verbatim rather
     * than replaced with U+FFFD. A replacement character can never match an archive entry, whereas a
     * literal `%` in a filename can.
     */
    fun percentDecode(value: String): String {
        if (!value.contains('%')) return value

        val out = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val high = if (char == '%' && index + 2 < value.length) hexOf(value[index + 1]) else -1
            val low = if (high >= 0) hexOf(value[index + 2]) else -1
            if (high >= 0 && low >= 0) {
                out.write((high shl 4) or low)
                index += 3
            } else {
                // The literal run up to the next escape at once: a surrogate pair written one half
                // at a time is lost.
                val end = value.indexOf('%', index + 1).takeIf { it >= 0 } ?: value.length
                out.write(value.substring(index, end).toByteArray(Charsets.UTF_8))
                index = end
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexOf(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }
}
