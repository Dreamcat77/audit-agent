import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {addChrome109Colors} from './chrome109-colors.mjs';

const root=path.resolve(process.argv[2]||'dist');
const indexPath=path.join(root,'index.html');
let index=await fs.readFile(indexPath,'utf8');
let converted=0;
for(const match of [...index.matchAll(/href="(\/[^"?]+\.css)"/g)]) {
  const source=path.resolve(root,'.'+match[1]);
  if(!source.startsWith(root+path.sep))throw Error('Asset outside build');
  const css=await fs.readFile(source,'utf8');
  if(css.includes('@supports not ((color:oklch'))continue;
  const result=await addChrome109Colors(css);
  const name='chrome109-colors-'+createHash('sha256').update(result.css).digest('hex').slice(0,20)+'.css';
  const target=path.join(path.dirname(source),name);
  await fs.writeFile(target,result.css);
  index=index.replaceAll(match[1],'/'+path.relative(root,target).split(path.sep).join('/'));
  converted+=result.changed;
}
if(!index.includes('chrome109-currentcolor-')) {
  const script=await fs.readFile(new URL('./chrome109-currentcolor.js',import.meta.url));
  const name='chrome109-currentcolor-'+createHash('sha256').update(script).digest('hex').slice(0,20)+'.js';
  const assetDir=path.dirname((index.match(/src="(\/[^"?]+\.js)"/)||[])[1]||'/assets/index.js');
  const relative=path.posix.join(assetDir,name);
  await fs.writeFile(path.join(root,'.'+relative),script);
  index=index.replace('</head>',`<script defer src="${relative}"></script>\n</head>`);
}
await fs.writeFile(indexPath,index);
console.log('CHROME109_COLORS_READY',converted);
