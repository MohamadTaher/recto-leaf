package leaf.novel.ui.reader.setting

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * How much of the chapter speech says at a time.
 *
 * Paragraph is the imported default and the better one for prose: the engine's own pauses then fall
 * where the writing puts them. Sentence is finer-grained, which matters when speech is being
 * followed along with rather than listened to.
 */
enum class NovelSpeechDivision(val titleRes: StringResource) {
    PARAGRAPH(MR.strings.leaf_novel_speech_paragraph),
    SENTENCE(MR.strings.leaf_novel_speech_sentence),
}
