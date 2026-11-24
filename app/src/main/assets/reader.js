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
  function width() { return Math.max(1, window.innerWidth); }
  function total() {
    if (fixed) return 1;
    return Math.max(1, Math.ceil((Math.max(body.scrollWidth, root.scrollWidth) - 1) / width()));
  }
  function go(page) {
    position = Math.max(0, Math.min(total() - 1, Math.floor(page)));
    if (!fixed) window.scrollTo((rtl ? -1 : 1) * position * width(), 0);
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
  // Trailing spaces, including the full-width U+3000 indent, hang past the column edge.
  // Measure them by the nearest preceding glyph so they stay on that glyph's page.
  function glyph(node, offset) {
    const text = node.nodeValue;
    let at = Math.min(Math.max(0, offset), node.length - 1);
    for (let k = at; k > 0 && k > at - 16 && /[\s\u3000]/.test(text[k]); k--) if (!/[\s\u3000]/.test(text[k - 1])) { at = k - 1; break; }
    return rangeOf(node, at);
  }
  function visible(rect) {
    const x = centre(rect);
    return rect.width > 0 && rect.height > 0 && x > 0 && x < width()
      && rect.bottom > 0 && rect.top < innerHeight;
  }
  // Pages advance in document order, so earlier text lies entirely before the viewport.
  // Binary searches keep a page turn fast even when one paragraph spans many pages.
  function before(rect) { return rtl ? centre(rect) >= width() : centre(rect) <= 0; }
  function after(rect) { return rtl ? centre(rect) <= 0 : centre(rect) >= width(); }
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
    return {page: position, total: total(), paragraph: index, offset: ratio, sample};
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
    style.textContent = 'html{margin:0!important;padding:0!important;overflow:hidden;' + palette + '}' +
      'body{box-sizing:border-box!important;margin:0!important;padding:18px 22px!important;' +
      'height:100vh!important;width:100vw!important;column-width:calc(100vw - 44px)!important;' +
      'column-gap:44px!important;column-fill:auto!important;overflow:visible!important;' +
      'font-size:' + settings.font + 'px!important;line-height:' + settings.line + '!important;' +
      'font-family:' + family + '!important;' + palette + '}' +
      'p,li,div,section {max-width:100%;overflow-wrap:anywhere;}' +
      'img,svg,video{max-width:100%!important;max-height:calc(100vh - 50px)!important;object-fit:contain;}' +
      'pre{white-space:pre-wrap;overflow-wrap:anywhere;}' +
      (settings.night ? 'body *:not(img):not(svg):not(path){color:inherit!important;background-color:transparent!important;}' : '');
    return state();
  }
  // The page holding a stored anchor, or -1 when it cannot be found in this chapter.
  function pageOf(paragraph, offset, sample) {
    let entry = nodes[paragraph];
    if (sample && (!entry || !entry.text.trim().startsWith(sample)))
      entry = nodes.find(item => item.text.trim().startsWith(sample)) || entry;
    if (!entry || !entry.node.length) return -1;
    // Round, not floor: the stored ratio can land a hair below the first visible character.
    const rect = glyph(entry.node, Math.min(entry.node.length - 1, Math.round(entry.node.length * offset)));
    if (rect.width <= 0) return -1;
    const absolute = rtl ? position * width() + width() - centre(rect) : centre(rect) + position * width();
    return Math.floor(Math.max(0, absolute) / width());
  }
  function locate(page, paragraph, offset, sample) {
    if (fixed) return go(0);
    const found = pageOf(paragraph, offset, sample);
    if (found >= 0) return go(found);
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
      : anchors.findIndex(([paragraph, offset, sample]) => pageOf(paragraph, offset, sample) === position),
    turn: delta => {
      const next = position + delta;
      return next < 0 || next >= total() ? null : go(next);
    },
    originals: () => nodes.map(item => item.text),
    replace: values => nodes.forEach((item, index) => item.node.nodeValue = values[index] ?? item.text),
    visibleText,
    selected: () => window.getSelection()?.toString() || '',
    selectionClear: () => window.getSelection()?.removeAllRanges(),
    search: word => {
      const query = word.toLocaleLowerCase();
      const index = nodes.findIndex(item => item.node.nodeValue.toLocaleLowerCase().includes(query));
      if (index < 0) return null;
      const offset = nodes[index].node.nodeValue.toLocaleLowerCase().indexOf(query) / nodes[index].node.length;
      return locate(position, index, offset, '');
    },
    chapterLink: fragment => {
      const target = document.getElementById(fragment);
      if (!target || fixed) return state();
      const rect = target.getBoundingClientRect();
      return go(Math.floor((position * width() + (rtl ? width() - rect.right : rect.left)) / width()));
    }
  };
})()
