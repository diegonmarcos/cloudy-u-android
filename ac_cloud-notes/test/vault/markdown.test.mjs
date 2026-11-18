import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, src } from './helpers.mjs';

const md = await src('markdown.ts');
const home = fixture('sample', 'Home.md');

test('frontmatter: scalars, inline list, dash list', () => {
  const fm = md.parseFrontmatter(home);
  assert.equal(fm.present, true);
  assert.equal(fm.data.title, 'Home');
  assert.deepEqual(fm.data.tags, ['index', 'project/alpha']);
  assert.deepEqual(fm.data.aliases, ['start']);
  assert.ok(fm.body.startsWith('# Home'));
});

test('wikilinks: alias, heading, embed, and code is skipped', () => {
  const links = md.extractLinks(fm(home));
  const t = links.map(l => `${l.embed ? '!' : ''}${l.target}${l.heading ? '#' + l.heading : ''}`);
  assert.deepEqual(t, ['Ideas#Backlog', 'board.canvas', '!diagram.mmd', 'Does Not Exist']);
  assert.equal(links[0].alias, 'the backlog');
  assert.deepEqual(md.extractLinks('`[[nope]]`\n```\n[[nope2]]\n```'), []);
});

test('tags: body, nested, frontmatter; not in code, not headings', () => {
  const f = md.parseFrontmatter(home);
  const tags = md.extractTags(f.body, f.data);
  assert.deepEqual(tags.sort(), ['inbox', 'index', 'project/alpha']);
  assert.ok(!tags.includes('notatag'));
});

test('render: wikilink, embed, callout, task list, mermaid fence, math, table', () => {
  const html = md.renderMarkdown(home);
  assert.match(html, /<a class="internal-link"[^>]*data-target="Ideas"[^>]*data-heading="Backlog"[^>]*>the backlog<\/a>/);
  assert.match(html, /<span class="embed" data-embed="note" data-target="diagram.mmd" data-raw="!\[\[diagram.mmd\]\]">/);
  assert.match(html, /<div class="callout" data-callout="tip" data-fold=""><div class="callout-title">Remember<\/div>/);
  assert.match(html, /<li class="task-list-item" data-task="x"><input type="checkbox" disabled checked>/);
  assert.match(html, /<pre class="mermaid" data-mermaid="graph TD\n  A\[Home\] --&gt; B\[Ideas\]">/);
  assert.match(html, /<span class="math" data-tex="e\^\{i\\pi\}\+1=0">/);
  assert.match(html, /<a class="tag"[^>]*data-tag="inbox">#inbox<\/a>/);
  assert.match(md.renderMarkdown('| a | b |\n|---|---|\n| 1 | 2 |'), /<table><thead><tr><th>a<\/th><th>b<\/th>/);
});

test('render: raw html and javascript: links never become live', () => {
  const html = md.renderMarkdown('<script>alert(1)</script>\n\n[x](javascript:alert(1)) ![i](data:text/html,x)');
  assert.ok(!/<script/i.test(html));
  assert.ok(!/javascript:/i.test(html));
  assert.ok(!/data:text/i.test(html));
});

test('text is escaped: ampersands and angle brackets in prose and code', () => {
  assert.match(md.renderMarkdown('Tom & Jerry <b>x</b>'), /Tom &amp; Jerry &lt;b&gt;x&lt;\/b&gt;/);
  assert.match(md.renderMarkdown('```\na && b\n```'), /a &amp;&amp; b/);
});

test('outline, tasks, word count', () => {
  const body = md.parseFrontmatter(home).body;
  assert.deepEqual(md.extractHeadings(body).map(h => [h.level, h.text]), [[1, 'Home']]);
  assert.deepEqual(md.extractTasks(body).map(t => t.done), [false, true]);
  assert.ok(md.wordCount(body) > 10);
});

function fm(s) {
  return md.parseFrontmatter(s).body;
}
