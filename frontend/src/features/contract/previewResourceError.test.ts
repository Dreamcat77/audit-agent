import assert from 'node:assert/strict';
import test from 'node:test';
import {isPreviewResourceError} from './previewResourceError.ts';

test('recognizes module transport errors in supported browsers',()=>{
  for(const message of ['Failed to fetch dynamically imported module: /assets/jszip-old.js',
    'error loading dynamically imported module','Importing a module script failed.',
    'Unable to preload CSS for /assets/old.css'])assert(isPreviewResourceError(new TypeError(message)));
});
test('does not label missing files, authentication errors or corrupt Office files as a version mismatch',()=>{
  for(const error of [new Error('原文件暂时无法读取。'),new Error('Corrupted zip: missing end of central directory'),
    new Error('HTTP 403'),new Error('Failed to fetch'),null])assert(!isPreviewResourceError(error));
});
