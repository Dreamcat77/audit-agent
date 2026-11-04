import assert from 'node:assert/strict';
import test from 'node:test';
import {accumulator,browserEngine,userAgentFromLine} from './summarize-browser-access.mjs';

test('reports IE rendering engine even when compatibility mode advertises IE7',()=>{
  assert.deepEqual(browserEngine('Mozilla/5.0 (compatible; MSIE 7.0; Windows NT 6.1; Trident/7.0)'),
    {engine:'IE',major:11,compatibilityMode:true});
  assert.deepEqual(browserEngine('Mozilla/5.0 Trident/7.0; rv:11.0'),{engine:'IE',major:11,compatibilityMode:false});
  assert.equal(browserEngine('MSIE 8.0').major,8);
});
test('groups Chromium by engine version rather than Edge or 360 branding',()=>{
  assert.equal(browserEngine('Chrome/86.0.4240.198 Safari/537.36 QIHU 360SE').major,86);
  assert.deepEqual(browserEngine('Chrome/109.0.0.0 Edg/109.0.0.0'),{engine:'Chromium',major:109,compatibilityMode:false});
  assert.equal(browserEngine('Chrome/42.0.0.0 Edge/18.19041').engine,'EdgeHTML');
  assert.equal(browserEngine('Firefox/115.0').engine,'Firefox');
});
test('recognizes combined and JSON logs without exporting private log fields',()=>{
  const summary=accumulator();
  summary.add('10.1.2.3 - PRIVATE_USER [04/Nov/2026:15:00:00 +0800] "GET /?secret=PRIVATE_QUERY HTTP/1.1" 200 123 "https://PRIVATE_REFERRER/" "Chrome/86.0.0.0"');
  summary.add(JSON.stringify({remote_addr:'10.1.2.3',request:'PRIVATE_REQUEST',http_user_agent:'Chrome/86.0.4240.198'}));
  summary.add('malformed PRIVATE_LOG');
  const result=summary.result();
  assert.equal(result.requests,2);assert.equal(result.unparsedLines,1);
  assert.equal(result.engines.length,1);assert.equal(result.engines[0].requests,2);
  assert.doesNotMatch(JSON.stringify(result),/PRIVATE_|10\.1\.2\.3|4240|HTTP\/1/);
});
test('malformed or missing agents remain visible in counts rather than guessed',()=>{
  assert.equal(userAgentFromLine('{broken'),null);
  assert.equal(userAgentFromLine('{}'),null);
  assert.deepEqual(browserEngine('unrecognized PRIVATE_AGENT'),{engine:'Unknown',major:null,compatibilityMode:false});
});
