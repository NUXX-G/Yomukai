// A tiny markdown reader for the forum. It returns plain objects (paragraphs, quotes, lists, code, bold, links...)
// that Markdown.svelte turns into elements: no HTML string is ever built from what people wrote, so nothing in a post
// can run in someone's browser. Links only go to http(s); images only come from this server's forum images.

const SAFE_LINK = /^https?:\/\/[^\s<>"']+$/i;
const SAFE_IMAGE = /^\/api\/forum\/images\/\d+$/;

/** Block-level parse: paragraphs, > quotes, - lists, ``` code blocks. */
export function parse(text) {
  const lines = String(text || '').replace(/\r\n?/g, '\n').split('\n');
  const blocks = [];
  let para = [];
  const flush = () => { if (para.length) { blocks.push({ t: 'p', c: inline(para.join('\n')) }); para = []; } };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.trim().startsWith('```')) {
      flush();
      const code = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith('```')) code.push(lines[i++]);
      blocks.push({ t: 'pre', v: code.join('\n') });
    } else if (/^>\s?/.test(line)) {
      flush();
      const quote = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) quote.push(lines[i++].replace(/^>\s?/, ''));
      i--;
      blocks.push({ t: 'quote', c: parse(quote.join('\n')) });
    } else if (/^\s*[-*]\s+/.test(line)) {
      flush();
      const items = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) items.push(inline(lines[i++].replace(/^\s*[-*]\s+/, '')));
      i--;
      blocks.push({ t: 'ul', items });
    } else if (!line.trim()) {
      flush();
    } else {
      para.push(line);
    }
  }
  flush();
  return blocks;
}

// Order matters: code first (its content is literal), then images, links, bold, italic, mentions, bare links.
const TOKENS = [
  ['code', /`([^`\n]+)`/],
  ['img', /!\[([^\]\n]{0,100})\]\(([^)\s]+)\)/],
  ['a', /\[([^\]\n]{1,200})\]\(([^)\s]+)\)/],
  ['b', /\*\*([^*\n]+)\*\*/],
  ['i', /(?<![*\w])\*([^*\n]+)\*(?![*\w])|(?<![_\w])_([^_\n]+)_(?![_\w])/],
  ['mention', /(?<![\w@])@([\p{L}\p{N}_.-]{2,40})/u],
  ['url', /https?:\/\/[^\s<>"')]+/]
];

/** Inline parse into text/bold/italic/code/link/image/mention nodes. */
export function inline(text) {
  const out = [];
  let rest = text;
  while (rest) {
    let best = null;
    for (const [type, re] of TOKENS) {
      const m = re.exec(rest);
      if (m && (!best || m.index < best.m.index)) best = { type, m };
    }
    if (!best) { out.push({ t: 'text', v: rest }); break; }
    const { type, m } = best;
    if (m.index > 0) out.push({ t: 'text', v: rest.slice(0, m.index) });
    out.push(node(type, m));
    rest = rest.slice(m.index + m[0].length);
  }
  return out;
}

function node(type, m) {
  switch (type) {
    case 'code': return { t: 'code', v: m[1] };
    case 'img': return SAFE_IMAGE.test(m[2]) ? { t: 'img', src: m[2], alt: m[1] } : { t: 'text', v: m[0] };
    case 'a': return SAFE_LINK.test(m[2]) ? { t: 'a', href: m[2], c: inline(m[1]) } : { t: 'text', v: m[1] };
    case 'b': return { t: 'b', c: inline(m[1]) };
    case 'i': return { t: 'i', c: inline(m[1] ?? m[2]) };
    case 'mention': return { t: 'mention', v: m[1] };
    case 'url': return { t: 'a', href: m[0], c: [{ t: 'text', v: m[0] }] };
  }
  return { t: 'text', v: m[0] };
}

/** The text of a post quoted in a reply ("> line" each), short. */
export function quoteOf(author, body) {
  const text = String(body || '').split('\n').filter((l) => !l.startsWith('>')).join(' ').slice(0, 280);
  return `> **${author}:** ${text}\n\n`;
}
