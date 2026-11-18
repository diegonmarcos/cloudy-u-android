// A parser for the HTML that renderMarkdown writes (and the browser's contenteditable leaves): tags, double-quoted
// attributes, text with the five entities, void tags. Not a general HTML parser; enough to run md-serialize under node.
const VOID = new Set(['br', 'hr', 'img', 'input']);
const ENT = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'", '&#x27;': "'", '&nbsp;': ' ' };
const decode = s => s.replace(/&(?:amp|lt|gt|quot|#39|#x27|nbsp);/g, m => ENT[m]);

export function parseHtml(html) {
  const root = { t: 'el', tag: '#root', attrs: {}, kids: [] };
  const stack = [root];
  const re = /<(\/?)([a-zA-Z][\w-]*)((?:\s+[\w:-]+(?:="[^"]*")?)*)\s*\/?>|([^<]+)/g;
  let m;
  while ((m = re.exec(html))) {
    const top = stack[stack.length - 1];
    if (m[4] !== undefined) {
      top.kids.push({ t: 'text', v: decode(m[4]) });
    } else if (m[1]) {
      for (let i = stack.length - 1; i > 0; i--) {
        if (stack[i].tag === m[2].toLowerCase()) { stack.length = i; break; }
      }
    } else {
      const attrs = {};
      for (const a of m[3].matchAll(/([\w:-]+)(?:="([^"]*)")?/g)) attrs[a[1]] = a[2] === undefined ? '' : decode(a[2]);
      const el = { t: 'el', tag: m[2].toLowerCase(), attrs, kids: [] };
      top.kids.push(el);
      if (!VOID.has(el.tag)) stack.push(el);
    }
  }
  return root.kids;
}
