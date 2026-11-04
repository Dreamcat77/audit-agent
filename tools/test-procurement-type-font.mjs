import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const css = readFileSync(path.join(root, 'frontend/src/styles/procurement-table-header.css'), 'utf8');
const font = readFileSync(path.join(root, 'frontend/src/assets/fonts/ui-cjk.woff2'));
const selector = '.procurement-demand-table > tbody > tr > td:nth-child(4) > span.font-mono';

assert.match(css, /@font-face\s*\{[^}]*font-family:\s*"Audit UI CJK";[^}]*url\('\.\.\/assets\/fonts\/ui-cjk\.woff2'\)[^}]*unicode-range:\s*U\+0080-10FFFF;/s);
assert.ok(css.includes(`${selector} {`), 'The procurement type selector is missing');
const selectorStart = css.indexOf(`${selector} {`);
const selectorBlock = css.slice(selectorStart, css.indexOf('}', selectorStart));
assert.match(selectorBlock, /font-family:\s*"Audit UI CJK"/);
assert.match(css, /\.procurement-demand-table\s*>\s*thead\s*>\s*tr\s*>\s*th\s*\{/);
assert.doesNotMatch(css, /https?:\/\//i);
assert.equal(createHash('sha256').update(font).digest('hex'), '5bf611f708444e807adeb44dfa7f3e0d4c8a4493def9b39b1d76ab38c516fc81');

console.log('PROCUREMENT_TYPE_FONT_SCOPE=PASS; LOCAL_FONT_HASH=PASS; HEADER_RULE_PRESERVED=PASS; REMOTE_FONT=NONE');
