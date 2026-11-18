// #1717 Edit mode, the way back to text: render -> serialise -> render must give the same HTML, so an edit never rewrites
// what it did not change; and what the browser leaves after typing (divs, b/i, nbsp, a bare checkbox) becomes Markdown.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { FIX, src } from './helpers.mjs';
import { parseHtml } from './html-tree.mjs';

const md = await src('markdown.ts');
const ser = await src('core/md-serialize.ts');

const render = text => md.renderMarkdown(text);
const back = html => ser.toMarkdown(parseHtml(html));
const loop = text => back(render(text));

const SAMPLES = {
  headings: '# One\n\n## Two\n\n### Three\n\n###### Six\n',
  paragraphs: 'First paragraph with **bold**, *italic*, ~~gone~~, ==marked== and `code`.\n\nSecond one.\nSame paragraph, a new line.\n',
  lists: '- a\n- b\n  - nested\n  - nested two\n- c\n\n1. one\n2. two\n3. three\n',
  tasks: '- [ ] open\n- [x] done\n',
  quote: '> a quote\n> over two lines\n\nafter\n',
  callout: '> [!warning]- Careful\n> the **body**\n\n> [!tip]\n> bare\n',
  code: '```ts\nconst a = 1 < 2 && 3;\n```\n\n```\nplain\n```\n',
  mermaid: '```mermaid\ngraph TD\n  A-->B\n```\n',
  math: '$$\nE = mc^2\n$$\n\nInline $x_1 + y$ here.\n',
  rule: 'above\n\n---\n\nbelow\n',
  table: '| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n',
  links: 'A [[Note]], [[Note#Heading]], [[Note#^block-1]], [[Note|alias]], [[Folder/Note#H|alias]] and an embed ![[pic.png]] and ![[Other note]].\n',
  mdlinks: 'See [site](https://example.com), [rel](<My Note.md>) and ![img](pics/a.png).\n',
  tags: 'Tagged #alpha and #beta/gamma here.\n',
  specials: 'Tom & Jerry <b>x</b> and 1 < 2 > 0\n',
};

for (const [name, text] of Object.entries(SAMPLES)) {
  test(`loop: ${name} renders, serialises and renders to the same HTML`, () => {
    const h1 = render(text);
    const again = back(h1);
    assert.equal(render(again), h1, `markdown after the loop:\n${again}`);
    // and the loop is a fixed point: a second pass changes nothing
    assert.equal(back(render(again)), again);
  });
}

test('loop: the unchanged markdown is returned for plain, well-formed notes', () => {
  for (const k of ['headings', 'paragraphs', 'tasks', 'quote', 'rule', 'table', 'tags', 'links', 'code']) assert.equal(loop(SAMPLES[k]), SAMPLES[k], k);
});

test('loop: every fixture note of the vault tests round-trips', () => {
  const walk = d => readdirSync(d, { withFileTypes: true }).flatMap(e => (e.isDirectory() ? walk(join(d, e.name)) : [join(d, e.name)]));
  const notes = walk(FIX).filter(f => f.endsWith('.md'));
  assert.ok(notes.length >= 3, 'fixtures found');
  for (const f of notes) {
    const body = md.parseFrontmatter(readFileSync(f, 'utf8')).body;
    const h1 = render(body);
    assert.equal(render(back(h1)), h1, f);
  }
});

test('links keep what the note wrote: block ids, aliases, embeds, markdown destinations', () => {
  const t = 'x [[Note#^block-1]] y [[Note|alias]] z ![[pic.png]] w [a b](<My Note.md>)\n';
  assert.equal(loop(t), t);
});

test('typing over a wikilink label makes it an alias of the same target', () => {
  const tree = parseHtml(render('see [[Note#Heading]]'));
  const a = JSON.stringify(tree).includes('internal-link');
  assert.ok(a);
  const link = tree[0].kids.find(k => k.t === 'el' && k.tag === 'a');
  link.kids = [{ t: 'text', v: 'a new label' }];
  assert.equal(ser.toMarkdown(tree), 'see [[Note#Heading|a new label]]\n');
});

test('what a contenteditable leaves: divs, b/i, nbsp, bare text and a toggled checkbox', () => {
  assert.equal(back('<div>line one</div><div>line two</div>'), 'line one\n\nline two\n');
  assert.equal(back('<div>a <b>bold</b> and <i>it</i></div>'), 'a **bold** and *it*\n');
  assert.equal(back('plain&nbsp;text<br>next line'), 'plain text\nnext line\n');
  assert.equal(back('<ul><li class="task-list-item" data-task=" "><input type="checkbox" checked="true"> now done</li></ul>'), '- [x] now done\n');
  assert.equal(back('<p>a<b> </b>b</p>'), 'a b\n');
  assert.equal(back('<h2></h2><p></p>'), '\n');
  assert.equal(back('<p><b>bold </b>tail</p>'), '**bold** tail\n');
});

test('empty paragraphs and trailing spaces do not leak into the note', () => {
  assert.equal(back('<p>a   </p><p> </p><p>b</p>'), 'a\n\nb\n');
});
