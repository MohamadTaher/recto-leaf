package leaf.novel.presentation.reader.components

/** Resolves a queued source location without WebView's document-wide, asynchronous find cursor. */
internal const val NOVEL_SPEECH_SCRIPT = """
    (() => {
      let pending = null;
      let selectionRange = null;
      const clear = () => {
        if (window.CSS && CSS.highlights) CSS.highlights.delete('recto-leaf-speech');
        if (selectionRange) {
          const selection = window.getSelection();
          if (selection.rangeCount && selection.getRangeAt(0) === selectionRange) selection.removeAllRanges();
          selectionRange = null;
        }
      };

      /**
       * The occurrence of `needle` closest to `target`, or -1 when the block no longer says it.
       *
       * Walking forward and stopping once the distance grows again: matches come out in order, so
       * the first one that is no nearer than the last is the point the sequence turns away.
       */
      const nearestOccurrence = (haystack, needle, target) => {
        let best = -1;
        for (let at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
          if (best >= 0 && Math.abs(at - target) >= Math.abs(best - target)) break;
          best = at;
        }
        return best;
      };

      const prose = block => {
        // Rebuild jsoup's collapsed prose while retaining the DOM endpoints for every character.
        let text = '';
        const points = [];
        const add = (character, node, offset) => {
          if (character === '\u200b' || character === '\u00ad') return;
          if (/\s/.test(character)) {
            if (!text || text.endsWith(' ')) return;
            character = ' ';
          }
          text += character;
          points.push({node, offset});
        };
        const visit = node => {
          if (node.nodeType === Node.TEXT_NODE) {
            for (let i = 0; i < node.data.length; i++) add(node.data[i], node, i);
          } else if (node.nodeType === Node.ELEMENT_NODE) {
            // Every tag jsoup calls a block, since the text being matched came from its `text()`.
            const separates = node !== block &&
              /^(BR|DIV|SECTION|ASIDE|PRE|TABLE|TR|TD|TH|UL|OL|HR|FIGURE|FIGCAPTION)$/.test(node.tagName);
            if (separates) add(' ', node, 0);
            node.childNodes.forEach(visit);
            if (separates) add(' ', node, 0);
          }
        };
        visit(block);
        return {text, points};
      };

      // Geometry is needed here; Kotlin chooses the containing paragraph, sentence or word.
      const firstVisible = () => {
        const visible = rect => rect.width > 0 && rect.height > 0 && rect.bottom > 0 &&
          rect.right > 0 && rect.top < innerHeight && rect.left < innerWidth;
        for (const block of document.querySelectorAll('[data-leaf-speech-block]')) {
          if (getComputedStyle(block).visibility !== 'visible') continue;
          if (!Array.from(block.getClientRects()).some(visible)) continue;
          const {points} = prose(block);
          const chars = points.map((point, start) => ({...point, start}))
            .filter(point => point.node.nodeType === Node.TEXT_NODE);
          const range = document.createRange();
          const rectAt = index => {
            const point = chars[index];
            range.setStart(point.node, point.offset);
            range.setEnd(point.node, point.offset + 1);
            return range.getBoundingClientRect();
          };
          // Characters before the viewport are above it (scrolling) or left of it (columns).
          let low = 0, high = chars.length;
          while (low < high) {
            const mid = (low + high) >>> 1;
            const rect = rectAt(mid);
            if (rect.bottom <= 0 || rect.right <= 0) low = mid + 1; else high = mid;
          }
          // Collapsed whitespace at a line break can have a zero-width rectangle.
          for (let index = low; index < chars.length; index++) {
            const rect = rectAt(index);
            if (rect.top >= innerHeight || rect.left >= innerWidth) break;
            if (visible(rect)) return {
              chapterId: block.closest('[data-leaf-chapter]')?.dataset.leafChapter || rectoLeafChapterId,
              block: Number(block.dataset.leafSpeechBlock), start: chars[index].start,
            };
          }
        }
        return null;
      };

      const follow = position => {
        clear();
        if (!position) return;
        const chapters = Array.from(document.querySelectorAll('[data-leaf-chapter]'));
        const chapter = chapters.length
          ? chapters.find(it => it.dataset.leafChapter === position.chapterId)
          : (position.chapterId === rectoLeafChapterId ? document.body : null);
        // A read-ahead request can arrive before its chapter has been appended. Never search elsewhere.
        if (!chapter) { pending = position; return; }
        const block = chapter.querySelector('[data-leaf-speech-block="' + position.block + '"]');
        if (!block) return;

        const {text, points} = prose(block);
        // The offset was taken before the reading aids ran, and one of them can put prose in front
        // of it — a printed page number inside the paragraph is the usual case. Naming the chapter
        // and the block has already made the match unambiguous, so the offset is only choosing
        // between repeats within one paragraph: when it no longer lands on the text, take the
        // occurrence nearest to where it used to be. Prose the aids removed outright matches
        // nothing, and leaves the page still.
        const wanted = position.start;
        const start = wanted >= 0 && text.slice(wanted, wanted + position.text.length) === position.text
          ? wanted
          : nearestOccurrence(text, position.text, Math.max(0, wanted));
        if (start < 0) return;
        const end = start + position.text.length;
        const first = points[start];
        const last = points[end - 1];
        if (!first || !last || first.node.nodeType !== Node.TEXT_NODE || last.node.nodeType !== Node.TEXT_NODE) return;
        const range = document.createRange();
        range.setStart(first.node, first.offset);
        range.setEnd(last.node, last.offset + 1);
        const rect = range.getClientRects()[0];
        if (!rect || (!rect.width && !rect.height)) return;
        if (window.CSS && CSS.highlights && window.Highlight) {
          CSS.highlights.set('recto-leaf-speech', new Highlight(range));
        } else {
          const selection = window.getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
          selectionRange = range;
        }
        if (document.documentElement.scrollWidth > window.innerWidth + 1) {
          if (rect.left < 0 || rect.right > window.innerWidth) {
            window.scrollTo(Math.floor((window.scrollX + rect.left) / window.innerWidth) * window.innerWidth, 0);
          }
        } else if (rect.top < 0 || rect.bottom > window.innerHeight) {
          window.scrollTo(0, window.scrollY + rect.top - window.innerHeight * 0.2);
        }
      };

      window.rectoLeafSpeech = {
        firstVisible,
        highlight: position => { pending = null; follow(position); },
        retry: () => {
          if (!pending) return;
          const position = pending;
          pending = null;
          follow(position);
        },
      };
    })();
"""
