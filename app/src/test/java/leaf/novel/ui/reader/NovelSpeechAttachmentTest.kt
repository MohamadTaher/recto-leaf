package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [NovelSpeechAttachment] is the shape M4 needed: a stop-then-play cycle builds a new engine, and a
 * reader must attach to *that* one rather than being permanently latched onto "have I ever
 * attached" — and a session ending with a reader still watching must not tear itself down under it.
 */
class NovelSpeechAttachmentTest {

    @Test
    fun `each new engine gets a generation later than the one before it`() {
        val attachment = NovelSpeechAttachment()
        attachment.newGeneration()
        val first = attachment.generation

        attachment.newGeneration()

        attachment.generation shouldBe first + 1
    }

    @Test
    fun `a new generation starts unattached, even if the previous one was attached`() {
        val attachment = NovelSpeechAttachment()
        attachment.newGeneration()
        attachment.attach()

        attachment.newGeneration()

        attachment.attached shouldBe false
    }

    @Test
    fun `a reader wired to a since-replaced engine must attach again`() {
        // This is M4: a stop-then-play cycle replaces the engine, so the generation a reader last
        // attached to no longer matches — the mismatch that makes attaching wire a fresh collector
        // instead of returning early onto an engine nothing is listening to.
        val attachment = NovelSpeechAttachment()
        attachment.newGeneration()
        val attachedGeneration = attachment.generation

        attachment.newGeneration()

        (attachedGeneration == attachment.generation) shouldBe false
    }

    @Test
    fun `may reset when speech ends and nobody is attached`() {
        val attachment = NovelSpeechAttachment()

        attachment.mayResetOn(ended = true) shouldBe true
    }

    @Test
    fun `must not reset when speech ends but a reader is attached`() {
        // This is M4's first half: a reader sitting right there already hides the notification
        // itself through its own collector, so tearing the engine down under it would orphan that
        // collector instead of merely being redundant.
        val attachment = NovelSpeechAttachment()
        attachment.attach()

        attachment.mayResetOn(ended = true) shouldBe false
    }

    @Test
    fun `an edge that is not ENDED never triggers a reset, attached or not`() {
        val attachment = NovelSpeechAttachment()

        attachment.mayResetOn(ended = false) shouldBe false
    }

    @Test
    fun `detaching clears the flag so a later end may reset`() {
        val attachment = NovelSpeechAttachment()
        attachment.attach()

        attachment.detach()

        attachment.mayResetOn(ended = true) shouldBe true
    }
}
