import assert from "node:assert/strict";
import {createRequire} from "node:module";
import test from "node:test";
import {build} from "esbuild";
const bundled=await build({bundle:true,write:false,platform:"node",format:"cjs",jsx:"automatic",
  stdin:{contents:`import React from "react";import {renderToStaticMarkup} from "react-dom/server";
    import {ProjectFileProcessingPanel} from "./src/features/project/components/ProjectFileProcessingPanel.tsx";
    export function render(props){return renderToStaticMarkup(React.createElement(ProjectFileProcessingPanel,props));}`,
    loader:"tsx",resolveDir:process.cwd()}});
const compiled={exports:{} as any};new Function("require","module","exports",bundled.outputFiles[0]!.text)(createRequire(import.meta.url),compiled,compiled.exports);
function renderFile(category:string,parseStatus:string,status="SUCCEEDED") {
  const job={jobId:"j",projectId:"p",fileId:"f",status,attemptNo:1,reasonCode:null,parseResultId:null,createdAt:"now",updatedAt:"now"};
  return compiled.exports.render({jobs:{projectId:"p",workerEnabled:true,items:[job],total:1,completed:status==="SUCCEEDED"?1:0,
    failed:status==="FAILED"?1:0,unknown:status==="UNKNOWN"?1:0},
    files:[{id:"f",originalFilename:"材料",category,parseStatus,mediaType:category==="PACKAGE"?"application/zip":"application/msword"}],
    error:null,onReload(){},onRetry(){}});
}
test("successful processing of an unsupported file never claims successful text parsing",()=>{
  const html=renderFile("OTHER","UNSUPPORTED");
  assert.match(html,/格式暂不支持/);assert.doesNotMatch(html,/解析完成/);
});
test("ZIP job completion describes expansion and leaves child processing independent",()=>{
  const html=renderFile("PACKAGE","NOT_APPLICABLE");
  assert.match(html,/资料包展开完成/);assert.doesNotMatch(html,/解析完成/);
});
test("UNKNOWN is readable and has no ordinary retry control",()=>{
  const html=renderFile("OTHER","PENDING","UNKNOWN");
  assert.match(html,/本次处理暂未取得结果/);assert.doesNotMatch(html,/重试该文件|审核完成|正在|核验中|[0-9]+%/);
});

test("a completed durable job does not claim continuing analysis or formal review completion",()=>{
  const html=renderFile("CONTRACT","PARSED");
  assert.match(html,/材料处理完成/);assert.doesNotMatch(html,/正在整理|OCR处理中|审核完成/);
});
