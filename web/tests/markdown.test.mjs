// Forum markdown never lets anything executable or foreign through. Run with: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parse, inline } from '../src/lib/markdown.js';

test('html stays text and dangerous links are dropped', () => {
  const nodes = inline('<img src=x onerror=alert(1)> [x](javascript:alert(1)) ![b](https://evil.example/x.png)');
  assert.ok(nodes.every((n) => n.t === 'text'));
  assert.ok(!nodes.map((n) => n.v).join('').includes('javascript:'));
});

test('formatting, mentions, own images and http links', () => {
  const nodes = inline('**hola** *tú* @beto ![a](/api/forum/images/3) [web](https://ok.example/a) `**no**`');
  const types = nodes.filter((n) => n.t !== 'text').map((n) => n.t);
  assert.deepEqual(types, ['b', 'i', 'mention', 'img', 'a', 'code']);
  assert.equal(nodes.find((n) => n.t === 'code').v, '**no**');
  assert.equal(nodes.find((n) => n.t === 'a').href, 'https://ok.example/a');
});

test('blocks: quotes, lists and code', () => {
  const blocks = parse('> cita\n> dos\n\n- uno\n- **dos**\n\n```\n<b>code</b>\n```\nfin');
  assert.deepEqual(blocks.map((b) => b.t), ['quote', 'ul', 'pre', 'p']);
  assert.equal(blocks[2].v, '<b>code</b>');
  assert.equal(blocks[1].items.length, 2);
});
