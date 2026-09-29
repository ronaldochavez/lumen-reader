/*
 * Folio reader runtime, injected into each chapter document.
 *
 * Positions are character offsets into the chapter's text, where the text is the concatenation of every
 * Text node under <body> (the same thing Range.toString() yields). Highlights, bookmarks and the AI
 * "spoiler cut-off" all use these offsets.
 */
(function () {
  'use strict';

  var Bridge = window.FolioBridge || null;
  var S = {
    nodes: [], starts: [], total: 0,
    page: 0, pageCount: 1, pageStart: 0, pageEnd: 0,
    highlights: [], ready: false, touch: null, suppressClick: false, flashTimer: null
  };

  function call(name) {
    if (!Bridge || typeof Bridge[name] !== 'function') return;
    var args = Array.prototype.slice.call(arguments, 1);
    try { Bridge[name].apply(Bridge, args); } catch (e) { /* ignore */ }
  }

  // Page size in CSS px. Measured here and handed to CSS as --folio-w/--folio-h, because vh/vw can be
  // stuck at 0 in Android WebView when the document loads before the view has been laid out.
  function measureViewport() {
    var de = document.documentElement;
    var w = de.clientWidth || window.innerWidth;
    var h = de.clientHeight || window.innerHeight;
    if (!(w > 0 && h > 0)) return false;
    S.w = w; S.h = h;
    de.style.setProperty('--folio-w', w + 'px');
    de.style.setProperty('--folio-h', h + 'px');
    return true;
  }

  /** Runs [fn] once the viewport has a real size (polls a few seconds at most). */
  function whenSized(fn) {
    var tries = 0;
    (function check() {
      if (measureViewport() || ++tries > 180) fn();
      else setTimeout(check, 16);
    })();
  }

  function pageWidth() { return S.w || document.documentElement.clientWidth || window.innerWidth; }
  function scroller() { return document.scrollingElement || document.documentElement; }

  // ---------- Text index ----------

  function buildIndex() {
    var nodes = [], starts = [], total = 0;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = walker.nextNode())) { nodes.push(n); starts.push(total); total += n.data.length; }
    S.nodes = nodes; S.starts = starts; S.total = total;
  }

  function locate(offset) {
    var s = S.starts;
    if (!s.length) return null;
    offset = Math.max(0, Math.min(offset, S.total));
    var lo = 0, hi = s.length - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (s[mid] <= offset) lo = mid; else hi = mid - 1;
    }
    var node = S.nodes[lo];
    return { node: node, offset: Math.min(offset - s[lo], node.data.length) };
  }

  function offsetOf(container, off) {
    var r = document.createRange();
    r.setStart(document.body, 0);
    try { r.setEnd(container, off); } catch (e) { return 0; }
    return r.toString().length;
  }

  function rangeFor(start, end) {
    var a = locate(start), b = locate(end);
    if (!a || !b) return null;
    var r = document.createRange();
    try { r.setStart(a.node, a.offset); r.setEnd(b.node, b.offset); } catch (e) { return null; }
    return r;
  }

  function textUpTo(offset) {
    var out = [], remaining = Math.max(0, Math.min(offset, S.total));
    for (var i = 0; i < S.nodes.length && remaining > 0; i++) {
      var d = S.nodes[i].data;
      if (d.length <= remaining) { out.push(d); remaining -= d.length; }
      else { out.push(d.substring(0, remaining)); remaining = 0; }
    }
    return out.join('');
  }

  // ---------- Pagination ----------

  /** Page index where the character at [offset] is rendered, or -1 if nothing visible nearby. */
  function pageOfOffset(offset) {
    var w = pageWidth();
    for (var k = 0; k < 48 && offset + k < S.total; k++) {
      var loc = locate(offset + k);
      if (!loc || loc.offset >= loc.node.data.length) continue;
      var r = document.createRange();
      r.setStart(loc.node, loc.offset);
      r.setEnd(loc.node, loc.offset + 1);
      var rects = r.getClientRects();
      if (rects.length && (rects[0].width > 0 || rects[0].height > 0)) {
        // Relative to <body>, so it is independent of the page-turn transform (even mid-animation).
        var x = rects[0].left - document.body.getBoundingClientRect().left;
        return Math.max(0, Math.min(S.pageCount - 1, Math.floor((x + 1) / w)));
      }
    }
    return -1;
  }

  /** First text offset rendered on page [p] (binary search; offsets are laid out left to right). */
  function firstOffsetOfPage(p) {
    if (p <= 0) return 0;
    if (p >= S.pageCount) return S.total;
    var lo = 0, hi = S.total;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      var pg = pageOfOffset(mid);
      if (pg >= p) hi = mid; else lo = mid + 1;
    }
    return lo;
  }

  function computePages() {
    var w = pageWidth();
    // body.scrollWidth is measured in the body's own space, unaffected by its transform.
    var sw = document.body.scrollWidth;
    S.pageCount = Math.max(1, Math.ceil((sw - 2) / w));
  }

  // Pages are turned by translating <body> rather than scrolling: scrolling gets clamped a few pixels
  // short on the last page (trailing column padding isn't scrollable), transforms aren't.
  // [anchor]: the text offset that stands for "where the reader is". Turning a page moves it to the new page's
  // first char; a relayout (resize, rotation, system bars coming back) keeps it, so repeated relayouts can't
  // drift the position backwards one page-start at a time. It's what Kotlin saves as the reading position.
  function goToPage(p, smooth, anchor) {
    p = Math.max(0, Math.min(S.pageCount - 1, p | 0));
    S.page = p;
    var st = document.body.style;
    st.transition = smooth ? 'transform .28s cubic-bezier(.2,.7,.3,1), opacity .18s ease' : 'opacity .18s ease';
    st.transform = 'translate3d(' + (-p * pageWidth()) + 'px,0,0)';
    report(anchor);
  }

  function report(anchor) {
    S.pageStart = firstOffsetOfPage(S.page);
    S.pageEnd = S.page >= S.pageCount - 1 ? S.total : firstOffsetOfPage(S.page + 1);
    S.anchor = anchor != null && anchor >= S.pageStart && anchor <= S.pageEnd ? anchor : S.pageStart;
    var snippet = textUpTo(Math.min(S.total, S.pageStart + 160)).substring(S.pageStart).replace(/\s+/g, ' ').trim();
    call('onPage', S.page, S.pageCount, S.pageStart, S.pageEnd, S.total, snippet, S.anchor);
  }

  function next() {
    if (S.page < S.pageCount - 1) goToPage(S.page + 1, true);
    else call('onBoundary', 'next');
  }

  function prev() {
    if (S.page > 0) goToPage(S.page - 1, true);
    else call('onBoundary', 'prev');
  }

  function goToOffset(offset) {
    var p = pageOfOffset(Math.max(0, offset));
    goToPage(p < 0 ? 0 : p, false, offset);
  }

  // ---------- Read aloud ----------

  /** Sentences starting at [offset], as {s, e, t} with absolute offsets, up to ~[maxChars] of text. */
  function sentencesFrom(offset, maxChars, lang) {
    var start = Math.max(0, Math.min(offset, S.total));
    var end = Math.min(S.total, start + (maxChars || 3000));
    var full = textUpTo(end);
    // Extend to the end of the sentence that straddles the cut (read a bit further, capped).
    if (end < S.total) {
      var more = textUpTo(Math.min(S.total, end + 600));
      var m = /[.!?…]["»”’)\]]*\s/.exec(more.substring(end));
      full = more.substring(0, m ? end + m.index + m[0].length : more.length);
    }
    var chunk = full.substring(start);
    var out = [];
    function push(s, e) {
      var t = chunk.substring(s, e);
      if (/\S/.test(t)) out.push({ s: start + s, e: start + e, t: t.replace(/\s+/g, ' ').trim() });
    }
    if (window.Intl && Intl.Segmenter) {
      var seg = new Intl.Segmenter(lang || undefined, { granularity: 'sentence' });
      for (var it = seg.segment(chunk)[Symbol.iterator](), r = it.next(); !r.done; r = it.next()) {
        push(r.value.index, r.value.index + r.value.segment.length);
      }
    } else {
      var re = /[^.!?…]+(?:[.!?…]+["»”’)\]]*|$)\s*/g, mm;
      while ((mm = re.exec(chunk)) && mm[0].length) push(mm.index, mm.index + mm[0].length);
    }
    // Very long "sentences" (lists, poems without punctuation) are split at line breaks for the TTS engine.
    var split = [];
    out.forEach(function (x) {
      if (x.t.length < 600) { split.push(x); return; }
      var raw = textUpTo(x.e).substring(x.s), pos = 0, parts = raw.split(/\n+/);
      parts.forEach(function (p) {
        var idx = raw.indexOf(p, pos);
        if (/\S/.test(p)) split.push({ s: x.s + idx, e: x.s + idx + p.length, t: p.replace(/\s+/g, ' ').trim() });
        pos = idx + p.length;
      });
    });
    return split;
  }

  function ttsMark(s, e) {
    if (window.CSS && CSS.highlights && window.Highlight) {
      var r = rangeFor(s, e);
      if (r) { var hl = new Highlight(); hl.add(r); CSS.highlights.set('folio-tts', hl); }
    }
    // Follow the voice: turn the page when the sentence starts on another one.
    var p = pageOfOffset(s);
    if (p >= 0 && p !== S.page) goToPage(p, true);
  }

  function ttsClear() {
    if (window.CSS && CSS.highlights) CSS.highlights.delete('folio-tts');
  }

  // ---------- Search ----------

  /** Lowercase + strip accents one UTF-16 unit at a time (same as SearchController.fold on the Kotlin side). */
  function fold(s) {
    var out = new Array(s.length);
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c.charCodeAt(0) >= 128) c = c.normalize('NFD').charAt(0) || c;
      out[i] = c.toLowerCase().charAt(0) || c;
    }
    return out.join('');
  }

  function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  /** Goes to the n-th match of [query] in this chapter and flashes it. Returns false if not found. */
  function findOccurrence(query, n) {
    var words = fold(query).trim().split(/\s+/).filter(function (w) { return w; });
    if (!words.length) return false;
    var re = new RegExp(words.map(escapeRe).join('\\s+'), 'g');
    var text = fold(textUpTo(S.total));
    var m, i = 0;
    while ((m = re.exec(text))) {
      if (i === n) {
        goToOffset(m.index);
        flash(m.index, m.index + m[0].length);
        return true;
      }
      i++;
      if (m[0].length === 0) re.lastIndex++;
    }
    return false;
  }

  function goToTarget(t) {
    if (!t) return goToPage(0, false);
    switch (t.type) {
      case 'search': if (!findOccurrence(t.value, t.n || 0)) goToPage(0, false); return;
      case 'end': return goToPage(S.pageCount - 1, false);
      case 'fraction': return goToPage(Math.round((S.pageCount - 1) * (t.value || 0)), false);
      case 'fragment': {
        var el = t.value && document.getElementById(t.value);
        return el ? goToOffset(offsetOf(el, 0)) : goToPage(0, false);
      }
      case 'offset': default: return goToOffset(t.value || 0);
    }
  }

  function relayout() {
    var anchor = S.anchor != null ? S.anchor : S.pageStart;
    computePages();
    var p = pageOfOffset(anchor);
    goToPage(p < 0 ? Math.min(S.page, S.pageCount - 1) : p, false, anchor);
  }

  // ---------- Settings & highlights ----------

  function applySettings(s) {
    if (!s) return;
    var st = document.documentElement.style;
    st.setProperty('--folio-bg', s.bg);
    st.setProperty('--folio-fg', s.fg);
    st.setProperty('--folio-link', s.link);
    st.setProperty('--folio-font', s.serif
      ? 'Georgia, "Noto Serif", "Droid Serif", serif'
      : '"Roboto", "Noto Sans", system-ui, sans-serif');
    st.setProperty('--folio-size', s.fontSize + 'px');
    st.setProperty('--folio-lh', String(s.lineHeight));
    st.setProperty('--folio-pad-x', s.margin + 'px');
    document.documentElement.setAttribute('data-folio-theme', s.theme);
  }

  function renderHighlights() {
    if (!(window.CSS && CSS.highlights && window.Highlight)) return;
    var groups = {};
    S.highlights.forEach(function (h) {
      var r = rangeFor(h.start, h.end);
      if (!r) return;
      (groups[h.color] = groups[h.color] || []).push(r);
    });
    ['yellow', 'coral', 'mint', 'violet'].forEach(function (c) { CSS.highlights.delete('folio-' + c); });
    Object.keys(groups).forEach(function (c) {
      var hl = new Highlight();
      groups[c].forEach(function (r) { hl.add(r); });
      CSS.highlights.set('folio-' + c, hl);
    });
  }

  function flash(start, end) {
    if (!(window.CSS && CSS.highlights && window.Highlight)) return;
    var r = rangeFor(start, end);
    if (!r) return;
    var hl = new Highlight(); hl.add(r);
    CSS.highlights.set('folio-flash', hl);
    clearTimeout(S.flashTimer);
    S.flashTimer = setTimeout(function () { CSS.highlights.delete('folio-flash'); }, 1600);
  }

  function highlightAt(x, y) {
    var pos = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
    if (!pos) return null;
    var off = offsetOf(pos.startContainer, pos.startOffset);
    for (var i = 0; i < S.highlights.length; i++) {
      var h = S.highlights[i];
      if (off >= h.start && off <= h.end) return h;
    }
    return null;
  }

  // ---------- Selection ----------

  function hasSelection() {
    var sel = window.getSelection();
    return !!(sel && sel.rangeCount && !sel.isCollapsed);
  }

  function selectionInfo() {
    var sel = window.getSelection();
    if (!sel || !sel.rangeCount || sel.isCollapsed) return null;
    var r = sel.getRangeAt(0);
    return {
      text: sel.toString(),
      start: offsetOf(r.startContainer, r.startOffset),
      end: offsetOf(r.endContainer, r.endOffset)
    };
  }

  function clearSelection() {
    var sel = window.getSelection();
    if (sel) sel.removeAllRanges();
  }

  // ---------- Input ----------

  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { S.touch = null; return; }
    var t = e.touches[0];
    S.touch = { x: t.clientX, y: t.clientY, time: Date.now() };
  }, { passive: true });

  document.addEventListener('touchend', function (e) {
    var start = S.touch; S.touch = null;
    if (!start || hasSelection()) return;
    var t = e.changedTouches[0];
    var dx = t.clientX - start.x, dy = t.clientY - start.y;
    if (Math.abs(dx) > 48 && Math.abs(dy) < 80 && Date.now() - start.time < 700) {
      S.suppressClick = true;
      setTimeout(function () { S.suppressClick = false; }, 350);
      if (dx < 0) next(); else prev();
    }
  }, { passive: true });

  document.addEventListener('click', function (e) {
    if (S.suppressClick || hasSelection()) return;
    var link = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (link) {
      e.preventDefault();
      call('onLink', link.href);
      return;
    }
    var h = highlightAt(e.clientX, e.clientY);
    if (h) { call('onHighlightTap', String(h.id)); return; }
    var w = pageWidth();
    if (e.clientX < w * 0.28) prev();
    else if (e.clientX > w * 0.72) next();
    else call('onToggleChrome');
  }, true);

  // The viewport itself must never scroll (e.g. selection auto-scroll); pages move via transform.
  window.addEventListener('scroll', function () {
    var sc = scroller();
    if (sc.scrollLeft || sc.scrollTop) { sc.scrollLeft = 0; sc.scrollTop = 0; }
  });

  window.addEventListener('resize', function () {
    clearTimeout(S.resizeTimer);
    S.resizeTimer = setTimeout(function () {
      // Always re-measure, even mid-load (init lays out with whatever size is current then).
      if (measureViewport() && S.ready) afterLayout(relayout);
    }, 120);
  });

  // Safety net for size changes that arrive without a resize event (seen in some WebView/emulator paths).
  setInterval(function () {
    var de = document.documentElement;
    if (S.w && de.clientWidth > 0 && (de.clientWidth !== S.w || de.clientHeight !== S.h)) {
      window.dispatchEvent(new Event('resize'));
    }
  }, 700);

  // ---------- Public API (called from Kotlin) ----------

  /** Runs [fn] after the next layout; falls back to a timer because rAF doesn't fire while the view is hidden. */
  function afterLayout(fn) {
    var done = false;
    function run() { if (!done) { done = true; fn(); } }
    requestAnimationFrame(function () { requestAnimationFrame(run); });
    setTimeout(run, 250);
  }

  window.Folio = {
    init: function (config) {
      applySettings(config.settings);
      S.highlights = config.highlights || [];
      var fontsReady = (document.fonts && document.fonts.ready) ? document.fonts.ready : Promise.resolve();
      fontsReady.then(function () {
        whenSized(function () { afterLayout(function () {
          buildIndex();
          renderHighlights();
          computePages();
          S.ready = true;
          goToTarget(config.target);
          document.documentElement.classList.add('folio-ready');
          if (config.target && config.target.type === 'offset' && config.target.flashEnd) flash(config.target.value, config.target.flashEnd);
        }); });
      });
    },
    applySettings: function (s) {
      applySettings(s);
      afterLayout(relayout);
    },
    setHighlights: function (list) { S.highlights = list || []; renderHighlights(); },
    next: next,
    prev: prev,
    goToOffset: function (o, flashEnd) { goToOffset(o); if (flashEnd) flash(o, flashEnd); },
    goToFraction: function (f) { goToTarget({ type: 'fraction', value: f }); },
    goToFragment: function (id) { goToTarget({ type: 'fragment', value: id }); },
    findOccurrence: findOccurrence,
    sentencesFrom: sentencesFrom,
    ttsMark: ttsMark,
    ttsClear: ttsClear,
    /** Text around a selection for "Define": ~500 chars before, and after it never past the visible page. */
    contextAround: function (start, end) {
      var from = Math.max(0, start - 500);
      var to = Math.min(S.total, Math.max(end, Math.min(S.pageEnd, end + 400)));
      return textUpTo(to).substring(from);
    },
    selectionInfo: selectionInfo,
    clearSelection: clearSelection,
    textUpTo: textUpTo,
    state: function () { return { page: S.page, pageCount: S.pageCount, start: S.pageStart, end: S.pageEnd, total: S.total }; }
  };

  function ready() { call('onReady'); }
  if (document.readyState === 'complete') ready();
  else window.addEventListener('load', ready);
})();
