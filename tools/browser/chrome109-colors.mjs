import { createRequire } from 'node:module';
const require=createRequire(new URL('../../frontend/package.json',import.meta.url));
const postcss=require('postcss');
const {color,serializeRGB}=require('@csstools/css-color-parser');
const {parseListOfComponentValues}=require('@csstools/css-parser-algorithms');
const {tokenize}=require('@csstools/css-tokenizer');

// Keep the original stylesheet byte-for-byte. Append only color fallbacks,
// inside the original layers/selectors and behind an unsupported-color gate.
export async function addChrome109Colors(css) {
  const original = postcss.parse(css);
  const palette = new Map();
  const ambiguous = new Set();
  original.walkDecls(/^--/, decl => {
    if (![':root,:host', ':root', '.dark', '.contract-light'].includes(decl.parent.selector) || decl.prop.startsWith('--tw-')) return;
    if (palette.has(decl.prop) && palette.get(decl.prop) !== decl.value) ambiguous.add(decl.prop);
    palette.set(decl.prop, decl.value);
  });
  const resolve = (value, seen = new Set()) => value.replace(/var\((--[\w-]+)\)/g, (whole, name) => {
    if (!palette.has(name) || ambiguous.has(name) || seen.has(name)) throw new Error('Dynamic color cannot be frozen: ' + name);
    return resolve(palette.get(name), new Set([...seen, name])) + ' ';
  });
  const fallback = original.clone();
  let changed = 0;
  fallback.walkDecls(decl => {
    if (!/(?:oklch|oklab|color-mix)\(/.test(decl.value)) { decl.remove(); return; }
    if (decl.value.includes('color-mix(')) {
      if (/currentcolor/i.test(decl.value)) {
        const match = /^color-mix\(in oklab,\s*currentcolor\s+([\d.]+)%,\s*transparent\)$/i.exec(decl.value);
        if (!match) throw new Error('Unrecognized currentcolor expression');
        decl.value = 'rgb(var(--audit-currentcolor-rgb) / ' + match[1] + '%)';
      } else decl.value = resolve(decl.value);
    }
    changed++;
  });
  // Empty branches have no effect. No layer flattening, :where rewriting,
  // property registration reset, flex-gap substitution or font edits.
  function prune(parent) {
    for (const node of [...(parent.nodes || [])]) {
      if (node.type === 'decl') continue;
      if (!node.nodes) { node.remove(); continue; }
      prune(node);
      if (!node.nodes.length) node.remove();
    }
  }
  prune(fallback);
  fallback.walkAtRules('supports', rule => {
    if (rule.params === '(color:color-mix(in lab,red,red))') rule.params = '(color:rgb(0,0,0))';
  });
  fallback.walkDecls(decl => {
    if (!/(?:oklch|oklab|color-mix)\(/.test(decl.value)) return;
    const nodes = parseListOfComponentValues(tokenize({css:decl.value.trim()}));
    const data = nodes.length === 1 && color(nodes[0]);
    if (!data) throw new Error('Unresolved color fallback: ' + decl.value);
    // Match the browser's sRGB channel clipping, rather than changing hues
    // with a different gamut-mapping algorithm. Keep alpha and precision.
    decl.value = serializeRGB(data, false).toString();
  });
  return { css: css + '\n@supports not ((color:oklch(50% 0 0)) and (color:color-mix(in oklab,red,transparent))) {\n' + fallback.toString() + '\n}\n', changed };
}
