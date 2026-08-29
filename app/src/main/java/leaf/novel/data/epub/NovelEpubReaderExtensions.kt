package leaf.novel.data.epub

import android.content.Context
import com.hippo.unifile.UniFile
import mihon.core.archive.archiveReader

/**
 * Opens [this] as a novel EPUB. Throws [NovelEpubException] when the file is not a readable EPUB.
 */
fun UniFile.novelEpubReader(context: Context): NovelEpubReader {
    val reader = try {
        archiveReader(context)
    } catch (e: Exception) {
        throw NovelEpubException(NovelEpubFailure.UNREADABLE)
    }
    return try {
        NovelEpubReader(reader)
    } catch (e: NovelEpubException) {
        reader.close()
        throw e
    } catch (e: Exception) {
        reader.close()
        throw NovelEpubException(NovelEpubFailure.UNREADABLE)
    }
}
