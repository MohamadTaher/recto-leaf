package leaf.novel.data.epub

import mihon.core.archive.ArchiveReader
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.Closeable
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import org.jsoup.parser.Parser as JsoupParser

/** Why an EPUB could not be read. Each reason maps to a user-facing message in the importer. */
enum class NovelEpubFailure {
    UNREADABLE,
    MISSING_PACKAGE,
    EMPTY_SPINE,
    DRM_PROTECTED,
}

class NovelEpubException(val failure: NovelEpubFailure) : Exception(failure.name)

/** Dublin Core metadata lifted from the OPF package document. */
data class NovelMetadata(
    val title: String?,
    val author: String?,
    val description: String?,
    val genres: List<String>,
    val language: String?,
    val date: Long?,
)

/** One entry of the EPUB spine, which is to say exactly one novel chapter. */
data class SpineItem(
    val id: String,
    /** Full archive entry path, already resolved against the OPF directory. */
    val href: String,
    /** Label from the table of contents, or null when the TOC does not cover this document. */
    val title: String?,
)

/**
 * Reads the text side of an EPUB: metadata, spine, table of contents, cover and raw entries.
 *
 * [mihon.core.archive.EpubReader] parses the same structure but only to collect `<img>` tags, so it
 * cannot serve a text reader; wrapping [ArchiveReader] here costs the same code and touches no
 * upstream file.
 *
 * Every entry name is indexed once on open. [ArchiveReader.getInputStream] scans the archive from
 * the start on every call, so unguarded lookups over a novel with hundreds of spine items are
 * quadratic — the index makes misses free and keeps hits to one scan each.
 */
class NovelEpubReader(private val reader: ArchiveReader) : Closeable by reader {

    private val entryNames: Set<String> = reader.useEntries { entries ->
        entries.filter { it.isFile }.mapTo(HashSet()) { it.name }
    }

    /** Some archives are written with Windows separators; probe for it the way EpubReader does. */
    private val separator: String = if (CONTAINER_PATH.replace("/", "\\") in entryNames) "\\" else "/"

    /** Path of the OPF package document inside the archive. */
    val opfPath: String = findPackagePath()

    private val packageDocument: Document = readDocument(opfPath, xml = true)
        ?: throw NovelEpubException(NovelEpubFailure.MISSING_PACKAGE)

    private val opfDirectory: String = parentOf(opfPath)

    private val manifest: Map<String, ManifestItem> = packageDocument.select("manifest > item")
        .mapNotNull { element ->
            val id = element.attr("id")
            val href = element.attr("href")
            if (id.isEmpty() || href.isEmpty()) {
                null
            } else {
                ManifestItem(
                    id = id,
                    href = resolve(opfDirectory, href),
                    mediaType = element.attr("media-type"),
                    properties = element.attr("properties"),
                )
            }
        }
        .associateBy { it.id }

    val metadata: NovelMetadata = parseMetadata()

    val spine: List<SpineItem> = parseSpine()

    /**
     * Archive path of the cover image, or null when no usable cover was found. Lazy because the
     * last fallback reads the first spine document, which costs an extra archive scan.
     */
    val coverEntry: String? by lazy { findCoverEntry() }

    fun hasEntry(path: String): Boolean = path in entryNames

    /** Opens an archive entry, or returns null when it is not present. */
    fun readEntry(path: String): InputStream? = if (hasEntry(path)) reader.getInputStream(path) else null

    /** Resolves an archive-internal reference. The arithmetic itself lives in [EpubPath]. */
    fun resolve(base: String, relative: String): String = EpubPath.resolve(base, relative, separator)

    private fun findPackagePath(): String {
        val encryptionPath = withSeparator(ENCRYPTION_PATH)
        if (entryNames.any { it.equals(encryptionPath, ignoreCase = true) }) {
            throw NovelEpubException(NovelEpubFailure.DRM_PROTECTED)
        }

        val container = readDocument(withSeparator(CONTAINER_PATH), xml = true)
        val declared = container?.getElementsByTag("rootfile")?.firstOrNull()?.attr("full-path")
            ?.takeIf { it.isNotEmpty() }
            ?.let(EpubPath::decodeHref)

        return listOfNotNull(declared, withSeparator(FALLBACK_PACKAGE_PATH))
            .firstOrNull { it in entryNames }
            ?: throw NovelEpubException(NovelEpubFailure.MISSING_PACKAGE)
    }

    private fun parseMetadata(): NovelMetadata {
        fun tag(name: String): String? = packageDocument.getElementsByTag(name).firstOrNull()
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val rawDate = tag("dc:date")
            ?: packageDocument.select("meta[property=dcterms:modified]").firstOrNull()?.text()

        return NovelMetadata(
            title = tag("dc:title"),
            author = tag("dc:creator"),
            description = tag("dc:description"),
            genres = packageDocument.getElementsByTag("dc:subject")
                .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
                .distinct(),
            language = tag("dc:language"),
            date = rawDate?.let(::parseDate),
        )
    }

    private fun parseSpine(): List<SpineItem> {
        val labels = parseTableOfContents()
        val items = packageDocument.select("spine > itemref")
            .filterNot { it.attr("linear").equals("no", ignoreCase = true) }
            .mapNotNull { manifest[it.attr("idref")] }
            .map { SpineItem(id = it.id, href = it.href, title = labels[it.href]) }

        if (items.isEmpty()) throw NovelEpubException(NovelEpubFailure.EMPTY_SPINE)
        return items
    }

    /** Maps a spine document's archive path to its table-of-contents label. */
    private fun parseTableOfContents(): Map<String, String> {
        val ncx = packageDocument.select("spine").firstOrNull()?.attr("toc")
            ?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }
        if (ncx != null) {
            val labels = parseNcx(ncx)
            if (labels.isNotEmpty()) return labels
        }

        val nav = manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }
        return nav?.let(::parseNavigationDocument).orEmpty()
    }

    private fun parseNcx(item: ManifestItem): Map<String, String> {
        val document = readDocument(item.href, xml = true) ?: return emptyMap()
        val base = parentOf(item.href)
        val labels = LinkedHashMap<String, String>()
        document.select("navPoint").forEach { point ->
            val label = point.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
            val src = point.selectFirst("content")?.attr("src").orEmpty()
            if (label.isNotEmpty() && src.isNotEmpty()) {
                labels.putIfAbsent(resolve(base, src), label)
            }
        }
        return labels
    }

    private fun parseNavigationDocument(item: ManifestItem): Map<String, String> {
        val document = readDocument(item.href, xml = false) ?: return emptyMap()
        val base = parentOf(item.href)
        val navigations = document.select("nav")
        val toc = navigations.firstOrNull { it.attr("epub:type").split(' ').contains("toc") }
            ?: navigations.firstOrNull()
            ?: return emptyMap()

        val labels = LinkedHashMap<String, String>()
        toc.select("a[href]").forEach { anchor ->
            val label = anchor.text().trim()
            if (label.isNotEmpty()) {
                labels.putIfAbsent(resolve(base, anchor.attr("href")), label)
            }
        }
        return labels
    }

    private fun findCoverEntry(): String? {
        val declaredId = packageDocument.select("metadata > meta[name=cover]").firstOrNull()?.attr("content")
        val byMeta = declaredId?.let { manifest[it] }
        val byProperty = manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }

        return listOfNotNull(byMeta?.href, byProperty?.href).firstOrNull { it in entryNames }
            ?: firstImageInFirstSpineDocument()
    }

    private fun firstImageInFirstSpineDocument(): String? {
        val first = spine.firstOrNull() ?: return null
        val document = readDocument(first.href, xml = false) ?: return null
        val base = parentOf(first.href)
        return document.allElements
            .asSequence()
            .mapNotNull {
                when (it.tagName()) {
                    "img" -> it.attr("src")
                    "image" -> it.attr("xlink:href").ifEmpty { it.attr("href") }
                    else -> null
                }
            }
            .filter(String::isNotEmpty)
            .map { resolve(base, it) }
            .firstOrNull { it in entryNames }
    }

    private fun readDocument(path: String, xml: Boolean): Document? {
        val parser = if (xml) JsoupParser.xmlParser() else JsoupParser.htmlParser()
        return try {
            readEntry(path)?.use { Jsoup.parse(it, null, "", parser) }
        } catch (e: Exception) {
            null
        }
    }

    private fun withSeparator(path: String): String = EpubPath.withSeparator(path, separator)

    private fun parentOf(path: String): String = EpubPath.parentOf(path, separator)

    private fun parseDate(raw: String): Long? {
        DATE_FORMATS.forEach { pattern ->
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(raw)?.time
            } catch (e: ParseException) {
                // Try the next pattern.
            }
        }
        return null
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    private companion object {
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val ENCRYPTION_PATH = "META-INF/encryption.xml"
        const val FALLBACK_PACKAGE_PATH = "OEBPS/content.opf"
        const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"

        val DATE_FORMATS = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")
    }
}
