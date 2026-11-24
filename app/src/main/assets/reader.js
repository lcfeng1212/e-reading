(() => {
  const fixed = window.bookFixed;
  const root = document.documentElement;
  const body = document.body;
  if (!body) return;
  const style = document.createElement('style');
  document.head.append(style);
  const nodes = [];
  const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
  for (let node; (node = walker.nextNode());) {
    if (node.nodeValue.trim() && !node.parentElement.closest('style,script,svg,textarea'))
      nodes.push({node, text: node.nodeValue});
  }
  const rtl = getComputedStyle(body).direction === 'rtl' || getComputedStyle(root).direction === 'rtl';
  let position = 0;
  let settings = {};
  // Scroll mode lays a chapter out as one tall column read top to bottom. Page mode keeps the
  // side-by-side columns. Fixed-layout books always show one whole page.
  function scrolling() { return !fixed && !!settings.scroll; }
  function width() { return Math.max(1, window.innerWidth); }
  function height() { return Math.max(1, window.innerHeight); }
  // One step is a page in page mode. In scroll mode it is a screen less one line, so the last
  // line of the previous screen stays in view.
  function step() {
    if (!scrolling()) return width();
    return Math.max(1, height() - Math.round((settings.font || 23) * (settings.line || 1.55)));
  }
  function maxScroll() { return Math.max(0, Math.max(body.scrollHeight, root.scrollHeight) - height()); }
  function total() {
    if (fixed) return 1;
    if (scrolling()) return Math.ceil(maxScroll() / step()) + 1;
    return Math.max(1, Math.ceil((Math.max(body.scrollWidth, root.scrollWidth) - 1) / width()));
  }
  // In scroll mode the reader may drag the page, so the position follows the real scroll offset.
  function sync() {
    if (!scrolling()) return;
    const y = window.scrollY;
    position = y >= maxScroll() - 2 ? total() - 1 : Math.floor(y / step());
  }
  function go(page) {
    position = Math.max(0, Math.min(total() - 1, Math.floor(page)));
    if (scrolling()) { window.scrollTo(0, Math.min(maxScroll(), position * step())); sync(); }
    else if (!fixed) window.scrollTo((rtl ? -1 : 1) * position * width(), 0);
    return state();
  }
  // Scroll mode: put the absolute vertical offset at the top of the screen.
  function seek(offset) {
    window.scrollTo(0, Math.max(0, Math.min(maxScroll(), offset)));
    sync();
    return state();
  }
  function rangeOf(node, offset) {
    const range = new Range();
    const at = Math.min(Math.max(0, offset), node.length - 1);
    range.setStart(node, at);
    range.setEnd(node, Math.min(node.length, at + 1));
    return range.getBoundingClientRect();
  }
  // A line-end full-width space or punctuation can hang past its column into the next page.
  // The centre of a glyph decides which page owns it.
  function centre(rect) { return (rect.left + rect.right) / 2; }
  function middle(rect) { return (rect.top + rect.bottom) / 2; }
  // Trailing spaces, including the full-width U+3000 indent, hang past the column edge.
  // Measure them by the nearest preceding glyph so they stay on that glyph's page.
  function glyph(node, offset) {
    const text = node.nodeValue;
    let at = Math.min(Math.max(0, offset), node.length - 1);
    for (let k = at; k > 0 && k > at - 16 && /[\s\u3000]/.test(text[k]); k--) if (!/[\s\u3000]/.test(text[k - 1])) { at = k - 1; break; }
    return rangeOf(node, at);
  }
  function visible(rect) {
    if (rect.width <= 0 || rect.height <= 0) return false;
    if (scrolling()) {
      const y = middle(rect);
      return y > 0 && y < height() && rect.right > 0 && rect.left < width();
    }
    const x = centre(rect);
    return x > 0 && x < width() && rect.bottom > 0 && rect.top < innerHeight;
  }
  // Pages advance in document order, so earlier text lies entirely before the viewport.
  // Binary searches keep a page turn fast even when one paragraph spans many pages.
  function before(rect) {
    if (scrolling()) return middle(rect) <= 0;
    return rtl ? centre(rect) >= width() : centre(rect) <= 0;
  }
  function after(rect) {
    if (scrolling()) return middle(rect) >= height();
    return rtl ? centre(rect) <= 0 : centre(rect) >= width();
  }
  function rects(node) {
    const block = new Range();
    block.selectNodeContents(node);
    return Array.from(block.getClientRects()).filter(rect => rect.width > 0 && rect.height > 0);
  }
  function nodeBefore(index) {
    const list = rects(nodes[index].node);
    return !list.length || list.every(before);
  }
  function nodeVisible(index) { return nodes[index].node.length > 0 && rects(nodes[index].node).some(visible); }
  function firstNode() {
    let low = 0, high = nodes.length;
    while (low < high) {
      const middle = (low + high) >> 1;
      if (nodeBefore(middle)) low = middle + 1; else high = middle;
    }
    for (let i = low; i < Math.min(nodes.length, low + 64); i++) if (nodeVisible(i)) return i;
    for (let i = 0; i < nodes.length; i++) if (nodeVisible(i)) return i;
    return -1;
  }
  function firstChar(node) {
    let low = 0, high = node.length;
    while (low < high) {
      const middle = (low + high) >> 1;
      if (before(glyph(node, middle))) low = middle + 1; else high = middle;
    }
    for (let j = low; j < Math.min(node.length, low + 64); j++) if (visible(glyph(node, j))) return j;
    for (let j = 0; j < node.length; j++) if (visible(glyph(node, j))) return j;
    return 0;
  }
  function endChar(node, start) {
    let low = start, high = node.length;
    while (low < high) {
      const middle = (low + high) >> 1;
      if (after(glyph(node, middle))) high = middle; else low = middle + 1;
    }
    return low;
  }
  function state() {
    let index = -1, ratio = 0, sample = '';
    if (!fixed) {
      index = firstNode();
      if (index >= 0) {
        const {node, text} = nodes[index];
        ratio = firstChar(node) / node.length;
        sample = text.trim().slice(0, 72);
      }
    }
    sync();
    return {page: position, total: total(), paragraph: index, offset: ratio, sample, scroll: scrolling()};
  }
  function layout(next) {
    settings = next;
    const palette = settings.night ? 'background:#141414!important;color:#f5f5f5!important;' : 'background:white!important;color:#161616!important;';
    const family = settings.serif ? 'serif' : 'sans-serif';
    if (fixed) {
      style.textContent = 'html{background:white;overflow:hidden} body{margin:0;transform-origin:0 0;}';
      const meta = document.querySelector('meta[name=viewport]');
      const content = meta ? meta.content : '';
      const naturalWidth = Number((content.match(/width\s*=\s*(\d+)/i) || [])[1]) || body.scrollWidth || width();
      const naturalHeight = Number((content.match(/height\s*=\s*(\d+)/i) || [])[1]) || body.scrollHeight || innerHeight;
      if (naturalWidth > 0 && naturalHeight > 0) {
        const scale = Math.min(width() / naturalWidth, innerHeight / naturalHeight);
        body.style.width = naturalWidth + 'px';
        body.style.height = naturalHeight + 'px';
        body.style.transform = 'translate(' + ((width() - naturalWidth * scale) / 2) + 'px,' +
          ((innerHeight - naturalHeight * scale) / 2) + 'px) scale(' + scale + ')';
      }
      return state();
    }
    const flow = scrolling()
      ? 'html{margin:0!important;padding:0!important;overflow-x:hidden!important;overflow-y:auto!important;' + palette + '}' +
        'body{box-sizing:border-box!important;margin:0!important;padding:18px 22px 40px!important;' +
        'height:auto!important;min-height:100vh!important;width:100vw!important;columns:auto!important;' +
        'column-width:auto!important;column-count:auto!important;overflow:visible!important;'
      : 'html{margin:0!important;padding:0!important;overflow:hidden;' + palette + '}' +
        'body{box-sizing:border-box!important;margin:0!important;padding:18px 22px!important;' +
        'height:100vh!important;width:100vw!important;column-width:calc(100vw - 44px)!important;' +
        'column-gap:44px!important;column-fill:auto!important;overflow:visible!important;';
    style.textContent = flow +
      'font-size:' + settings.font + 'px!important;line-height:' + settings.line + '!important;' +
      'font-family:' + family + '!important;' + palette + '}' +
      'p,li,div,section {max-width:100%;overflow-wrap:anywhere;}' +
      'img,svg,video{max-width:100%!important;max-height:calc(100vh - 50px)!important;object-fit:contain;}' +
      'pre{white-space:pre-wrap;overflow-wrap:anywhere;}' +
      (settings.night ? 'body *:not(img):not(svg):not(path){color:inherit!important;background-color:transparent!important;}' : '');
    // A new layout can clamp the scroll offset. Read the position back from where the page is.
    if (scrolling()) sync();
    else position = Math.round(Math.abs(window.scrollX) / width());
    return state();
  }
  // The glyph of a stored anchor, or null when it cannot be found in this chapter.
  function anchor(paragraph, offset, sample) {
    let entry = nodes[paragraph];
    if (sample && (!entry || !entry.text.trim().startsWith(sample)))
      entry = nodes.find(item => item.text.trim().startsWith(sample)) || entry;
    if (!entry || !entry.node.length) return null;
    // Round, not floor: the stored ratio can land a hair below the first visible character.
    const rect = glyph(entry.node, Math.min(entry.node.length - 1, Math.round(entry.node.length * offset)));
    return rect.width > 0 ? rect : null;
  }
  // Where a glyph lies along the reading direction, measured from the chapter start.
  function absolute(rect) {
    if (scrolling()) return rect.top + window.scrollY;
    return rtl ? position * width() + width() - centre(rect) : centre(rect) + position * width();
  }
  function pageAt(offset) { return Math.floor(Math.max(0, offset) / step()); }
  // Shows the glyph: its page in page mode, its line at the top of the screen in scroll mode.
  function reach(rect) {
    if (scrolling()) return seek(absolute(rect) - 4);
    return go(pageAt(absolute(rect)));
  }
  function pageOf(paragraph, offset, sample) {
    const rect = anchor(paragraph, offset, sample);
    return rect ? pageAt(absolute(rect)) : -1;
  }
  function locate(page, paragraph, offset, sample) {
    if (fixed) return go(0);
    const rect = anchor(paragraph, offset, sample);
    if (rect) return reach(rect);
    if (scrolling() && page < 0) return seek(maxScroll());
    return go(page < 0 ? total() - 1 : page);
  }
  function visibleText() {
    if (fixed) return body.innerText || '';
    const result = [];
    const start = firstNode();
    if (start < 0) return '';
    for (let i = start; i < nodes.length; i++) {
      const {node} = nodes[i];
      if (!node.length) continue;
      const list = rects(node);
      if (list.length && list.every(after)) break;
      if (!list.some(visible)) continue;
      if (list.every(visible)) { result.push(node.nodeValue); continue; }
      const from = firstChar(node);
      const text = node.nodeValue.slice(from, endChar(node, from));
      if (text) result.push(text);
    }
    return result.join('\n');
  }
  window.reader = {
    state, layout, locate, go,
    // Index of the first anchor [paragraph, offset, sample] that falls on the current page.
    onPage: anchors => fixed ? (anchors.length ? 0 : -1)
      : scrolling() ? anchors.findIndex(([paragraph, offset, sample]) => {
          const rect = anchor(paragraph, offset, sample);
          return rect !== null && visible(rect);
        })
      : anchors.findIndex(([paragraph, offset, sample]) => pageOf(paragraph, offset, sample) === position),
    // Null at either end of the chapter, so the caller can move to the next or previous one.
    turn: delta => {
      if (scrolling()) {
        const y = window.scrollY;
        if (delta > 0 && y >= maxScroll() - 2) return null;
        if (delta < 0 && y <= 1) return null;
        return seek(y + delta * step());
      }
      const next = position + delta;
      return next < 0 || next >= total() ? null : go(next);
    },
    // Automatic scrolling: moves down by a fraction of a screen. Null at the chapter end.
    glide: fraction => {
      if (!scrolling()) return null;
      const y = window.scrollY;
      if (y >= maxScroll() - 2) return null;
      return seek(y + Math.max(1, step() * fraction));
    },
    // Text to read aloud from the top of the screen to the chapter end: [paragraph, from, text].
    speech: () => {
      if (fixed) return [];
      const start = firstNode();
      if (start < 0) return [];
      const out = [];
      for (let i = start; i < nodes.length; i++) {
        const node = nodes[i].node;
        if (!node.length) continue;
        const from = i === start ? firstChar(node) : 0;
        const text = node.nodeValue.slice(from);
        if (text.trim()) out.push([i, from, text]);
      }
      return out;
    },
    // Keeps the character being read on screen. Null when it is already visible.
    show: (index, offset) => {
      const entry = nodes[index];
      if (fixed || !entry || !entry.node.length) return null;
      const rect = glyph(entry.node, Math.min(entry.node.length - 1, Math.max(0, offset)));
      if (rect.width <= 0 || visible(rect)) return null;
      return reach(rect);
    },
    originals: () => nodes.map(item => item.text),
    replace: values => nodes.forEach((item, index) => item.node.nodeValue = values[index] ?? item.text),
    visibleText,
    selected: () => window.getSelection()?.toString() || '',
    selectionClear: () => window.getSelection()?.removeAllRanges(),
    // Searches the chapter's text as one string, so a match split across tags (e.g. bold text
    // inside a sentence) is still found. Returns null when the chapter does not contain it.
    search: word => {
      const query = word.toLocaleLowerCase();
      if (!query) return null;
      let joined = '';
      const starts = [];
      for (const item of nodes) { starts.push(joined.length); joined += item.node.nodeValue; }
      const at = joined.toLocaleLowerCase().indexOf(query);
      if (at < 0) return null;
      let index = 0;
      while (index + 1 < nodes.length && starts[index + 1] <= at) index++;
      const rect = glyph(nodes[index].node, at - starts[index]);
      return rect.width > 0 ? reach(rect) : state();
    },
    // The link under a tap at view pixel (x, y): its absolute address, or null.
    linkAt: (x, y) => {
      const ratio = window.devicePixelRatio || 1;
      const element = document.elementFromPoint(x / ratio, y / ratio);
      const link = element && element.closest('a[href]');
      return link ? link.href : null;
    },
    goFraction: fraction => {
      const part = Math.max(0, Math.min(0.999, fraction));
      return scrolling() ? seek(part * maxScroll()) : go(Math.floor(part * total()));
    },
    chapterLink: fragment => {
      // Older EPUBs mark footnotes with <a name="..."> instead of an id.
      const target = document.getElementById(fragment)
        || Array.from(document.getElementsByName(fragment)).find(item => item.tagName.toLowerCase() === 'a');
      if (!target || fixed) return state();
      const rect = target.getBoundingClientRect();
      if (scrolling()) return seek(rect.top + window.scrollY - 4);
      return go(Math.floor((position * width() + (rtl ? width() - rect.right : rect.left)) / width()));
    }
  };
})()
