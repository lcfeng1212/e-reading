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
  function visible(rect) {
    return rect.width > 0 && rect.height > 0 && rect.right > 2 && rect.left < width() - 2
      && rect.bottom > 0 && rect.top < innerHeight;
  }
  function state() {
    let index = -1, ratio = 0, sample = '';
    if (!fixed) for (let i = 0; i < nodes.length; i++) {
      const {node, text} = nodes[i];
      if (!node.length) continue;
      const block = new Range();
      block.selectNodeContents(node);
      if (!Array.from(block.getClientRects()).some(visible)) continue;
      index = i;
      for (let j = 0; j < node.length; j++) if (visible(rangeOf(node, j))) {
        ratio = j / node.length;
        break;
      }
      sample = text.trim().slice(0, 72);
      break;
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
  function locate(page, paragraph, offset, sample) {
    if (fixed) return go(0);
    let entry = nodes[paragraph];
    if (sample && (!entry || !entry.text.trim().startsWith(sample)))
      entry = nodes.find(item => item.text.trim().startsWith(sample)) || entry;
    if (entry && entry.node.length) {
      const rect = rangeOf(entry.node, Math.floor(entry.node.length * offset));
      if (rect.width > 0) {
        const absolute = rtl ? position * width() + width() - rect.right : rect.left + position * width();
        return go(Math.floor(Math.max(0, absolute) / width()));
      }
    }
    return go(page < 0 ? total() - 1 : page);
  }
  function visibleText() {
    if (fixed) return body.innerText || '';
    const result = [];
    for (const {node} of nodes) {
      if (!node.length) continue;
      const range = new Range();
      range.selectNodeContents(node);
      const rects = Array.from(range.getClientRects());
      if (!rects.some(visible)) continue;
      if (rects.every(visible)) result.push(node.nodeValue);
      else {
        let text = '';
        for (let i = 0; i < node.length; i++) if (visible(rangeOf(node, i))) text += node.nodeValue[i];
        if (text) result.push(text);
      }
    }
    return result.join('\n');
  }
  window.reader = {
    state, layout, locate, go, turn: delta => {
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
