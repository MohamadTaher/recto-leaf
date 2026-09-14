// Run with Node and Playwright available: node scripts/test-novel-speech.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const source = fs.readFileSync(path.join(__dirname,
  '../app/src/main/java/leaf/novel/presentation/reader/components/NovelSpeechScript.kt'), 'utf8');
const script = source.split('internal const val NOVEL_SPEECH_SCRIPT = """')[1].split('"""')[0];

(async () => {
  const browser = await chromium.launch({ channel: process.env.SPEECH_TEST_BROWSER || 'msedge', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 800, height: 600 } });
    const reset = async body => {
      await page.setContent(`<style>p { margin: 0; } section { min-height: 3000px; }</style>${body}`);
      await page.evaluate(`(() => { const rectoLeafChapterId = '20'; ${script} })()`);
    };
    const highlight = position => page.evaluate(position => window.rectoLeafSpeech.highlight(position), position);
    const selected = () => page.evaluate(() => {
      const range = Array.from(CSS.highlights.get('recto-leaf-speech') || [])[0];
      if (!range) return null;
      const block = range.startContainer.parentElement.closest('[data-leaf-speech-block]');
      return { chapter: block.closest('section')?.dataset.leafChapter, block: block.dataset.leafSpeechBlock,
        text: range.toString(), offset: range.startOffset };
    });
    await reset(`
      <section data-leaf-chapter="10"><p data-leaf-speech-block="0">Yes.</p></section>
      <section data-leaf-chapter="20"><p data-leaf-speech-block="0">Yes.</p>
        <p data-leaf-speech-block="1">He said yes. Yes. Yes.</p></section>
      <section data-leaf-chapter="30"><p data-leaf-speech-block="0">Yes.</p></section>`);
    // The same phrase occurs in earlier and later chapters, and within an earlier sentence.
    await highlight({ chapterId: '20', block: 1, start: 18, text: 'Yes.' });
    assert.deepEqual(await selected(), { chapter: '20', block: '1', text: 'Yes.', offset: 18 });
    const scroll = await page.evaluate(() => scrollY);
    assert(scroll > 2000 && scroll < 4000, `Unexpected forward jump to ${scroll}`);
    await highlight({ chapterId: '30', block: 0, start: 0, text: 'Yes.' });
    await highlight({ chapterId: '10', block: 0, start: 0, text: 'Yes.' });
    assert.equal((await selected()).chapter, '10');

    // A missing chapter must not resolve against identical text in the current document.
    const before = await page.evaluate(() => scrollY);
    await highlight({ chapterId: '40', block: 0, start: 0, text: 'Yes.' });
    assert.equal(await selected(), null);
    assert.equal(await page.evaluate(() => scrollY), before);
    await page.evaluate(() => {
      document.body.insertAdjacentHTML('beforeend',
        '<section data-leaf-chapter="40"><p data-leaf-speech-block="0">Yes.</p></section>');
      window.rectoLeafSpeech.retry();
    });
    assert.equal((await selected()).chapter, '40');

    // Stopping or a newer utterance supersedes pending read-ahead work.
    await highlight({ chapterId: '50', block: 0, start: 0, text: 'Yes.' });
    await highlight(null);
    await page.evaluate(() => {
      document.body.insertAdjacentHTML('beforeend',
        '<section data-leaf-chapter="50"><p data-leaf-speech-block="0">Yes.</p></section>');
      window.rectoLeafSpeech.retry();
    });
    assert.equal(await selected(), null);

    await reset('<p data-leaf-speech-block="0">One&nbsp; <em>word</em>.<br>Yes. Yes.</p>');
    await highlight({ chapterId: '20', block: 0, start: 15, text: 'Yes.' });
    assert.equal((await selected()).text, 'Yes.');
    // An offset a reading aid has shifted still resolves, to the nearest occurrence in the block.
    await highlight({ chapterId: '20', block: 0, start: 1, text: 'Yes.' });
    assert.equal(await page.evaluate(() => Array.from(CSS.highlights.get('recto-leaf-speech'))[0].startOffset), 0);
    await highlight({ chapterId: '20', block: 0, start: 17, text: 'Yes.' });
    assert.equal(await page.evaluate(() => Array.from(CSS.highlights.get('recto-leaf-speech'))[0].startOffset), 5);
    // Prose a reading aid removed outright matches nothing, and leaves the page still.
    await highlight({ chapterId: '20', block: 0, start: 0, text: 'Never said.' });
    assert.equal(await selected(), null);
    await highlight({ chapterId: '21', block: 0, start: 15, text: 'Yes.' });
    assert.equal(await selected(), null);

    // CSS columns use horizontal scrolling, and older WebViews use a selection as the highlight.
    await reset(`<style>body { margin: 0; height: 600px; column-count: 1; column-gap: 0; column-fill: auto; }
      p { height: 600px; }</style><p data-leaf-speech-block="0">Yes.</p>
      <p data-leaf-speech-block="1">Yes.</p><p data-leaf-speech-block="2">Yes.</p>`);
    await highlight({ chapterId: '20', block: 2, start: 0, text: 'Yes.' });
    assert.equal((await selected()).block, '2');
    assert.equal(await page.evaluate(() => scrollX), 1600);
    await highlight({ chapterId: '20', block: 0, start: 0, text: 'Yes.' });
    assert.equal(await page.evaluate(() => scrollX), 0);
    await highlight(null);
    await page.evaluate(() => { Object.defineProperty(CSS, 'highlights', { value: undefined }); });
    await highlight({ chapterId: '20', block: 0, start: 0, text: 'Yes.' });
    assert.equal(await page.evaluate(() => getSelection().toString()), 'Yes.');
    await highlight(null);
    assert.equal(await page.evaluate(() => getSelection().toString()), '');

    // Exercise the actual chapter command bridge, including a head script waiting for the body.
    const bridgeSource = fs.readFileSync(path.join(__dirname,
      '../app/src/main/java/leaf/novel/presentation/reader/components/NovelChapterWebView.kt'), 'utf8');
    const bridgeScript = bridgeSource.split('private const val CHAPTER_OBSERVER_SCRIPT = """')[1].split('"""')[0];
    const bridgePage = await browser.newPage();
    const errors = [];
    bridgePage.on('pageerror', error => errors.push(error.message));
    await bridgePage.setContent(`<head><script>
      const rectoLeafGeneration = 1;
      const rectoLeafChapterId = '20';
      const messages = [];
      const RectoLeafChapterBridge = { postMessage: value => messages.push(JSON.parse(value)) };
      ${script}\n${bridgeScript}
      </script></head><body><section data-leaf-chapter="20"><p data-leaf-speech-block="0">Yes.</p></section></body>`);
    assert(await bridgePage.evaluate(() => messages.some(it => it.type === 'ready')));
    await bridgePage.evaluate(() => {
      const command = value => window.rectoLeafChapters.command(JSON.stringify(value));
      command({ type: 'speech', position: { chapterId: '21', block: 0, start: 0, text: 'Yes.' } });
      command({ type: 'append', html: '<section data-leaf-chapter="21"><p data-leaf-speech-block="0">Yes.</p></section>' });
    });
    assert.equal(await bridgePage.evaluate(() => {
      const range = Array.from(CSS.highlights.get('recto-leaf-speech'))[0];
      return range.startContainer.parentElement.closest('section').dataset.leafChapter;
    }), '21');
    assert.deepEqual(errors, []);
    console.log('PASS: repeated text, chapter scope, backward seeks, read-ahead, cancellation, inline markup, mismatches, pagination, selection fallback and chapter bridge');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
