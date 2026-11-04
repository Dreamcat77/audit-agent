import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import {fileURLToPath} from 'node:url';

// Engine versions, not browser product names; 360 can expose a Chrome or IE UA.
export function browserEngine(userAgent) {
  const ua=String(userAgent||'');
  const trident=/Trident\/(\d+)/i.exec(ua),msie=/MSIE\s+(\d+)/i.exec(ua);
  if(trident||msie){
    const major=trident?Number(trident[1])+4:Number(msie[1]);
    return {engine:'IE',major,compatibilityMode:Boolean(msie&&Number(msie[1])<major)};
  }
  const edge=/\bEdge\/(\d+)/i.exec(ua);
  if(edge)return {engine:'EdgeHTML',major:Number(edge[1]),compatibilityMode:false};
  const chromium=/(?:Chrome|Chromium|HeadlessChrome)\/(\d+)/i.exec(ua);
  if(chromium)return {engine:'Chromium',major:Number(chromium[1]),compatibilityMode:false};
  const firefox=/Firefox\/(\d+)/i.exec(ua);
  if(firefox)return {engine:'Firefox',major:Number(firefox[1]),compatibilityMode:false};
  const safari=/Version\/(\d+).*Safari\//i.exec(ua);
  if(safari)return {engine:'Safari/WebKit',major:Number(safari[1]),compatibilityMode:false};
  return {engine:'Unknown',major:null,compatibilityMode:false};
}

export function userAgentFromLine(line) {
  if(line.trimStart().startsWith('{')){
    try {
      const record=JSON.parse(line);
      const value=record.http_user_agent??record.user_agent;
      return typeof value==='string'?value:null;
    }catch{return null;}
  }
  // Nginx combined format, including escaped quotes and optional trailing fields.
  const match=/\]\s+"(?:\\.|[^"\\])*"\s+\d{3}\s+(?:\d+|-)\s+"(?:\\.|[^"\\])*"\s+"((?:\\.|[^"\\])*)"(?:\s.*)?$/.exec(line);
  return match?match[1].replace(/\\x([0-9a-f]{2})/gi,(_,hex)=>String.fromCharCode(parseInt(hex,16))).replace(/\\(["\\])/g,'$1'):null;
}

export function accumulator() {
  let requests=0,unparsedLines=0;
  const groups=new Map();
  return {
    add(line){
      const ua=userAgentFromLine(line);
      if(ua===null){unparsedLines++;return;}
      requests++;
      const version=browserEngine(ua),key=JSON.stringify(version);
      const group=groups.get(key)||{...version,requests:0};group.requests++;groups.set(key,group);
    },
    result(){return {schemaVersion:1,metric:'requests, not unique users or devices',requests,unparsedLines,
      engines:[...groups.values()].sort((a,b)=>b.requests-a.requests||a.engine.localeCompare(b.engine)||(a.major??0)-(b.major??0)),
      limitation:'User-Agent may be changed or hidden. Use browser-check.html for actual capabilities. No raw log fields are exported.'};}
  };
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const input=process.argv[2];
  if(!input||input==='--help'){
    console.log('Usage: node tools/browser/summarize-browser-access.mjs <nginx-access.log|->\nOutputs engine/version request counts only; does not export IP, URL, account or raw User-Agent.');
    if(!input)process.exitCode=1;
  }else{
    const summary=accumulator();
    const lines=readline.createInterface({input:input==='-'?process.stdin:fs.createReadStream(input),crlfDelay:Infinity});
    try{for await(const line of lines)summary.add(line);console.log(JSON.stringify(summary.result(),null,2));}
    catch{console.error('Could not read browser access log. Check the supplied path and permissions.');process.exitCode=1;}
  }
}
