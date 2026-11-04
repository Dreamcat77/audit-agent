import assert from "node:assert/strict";
import test from "node:test";
import {writeClipboardText} from "./clipboard.ts";

test("HTTP clipboard fallback copies plain content and restores focus and selection",async()=>{
  let appended=false,removed=false,focused=false,selected=false,restored=false;let command="",value="";
  const saved={window:Object.getOwnPropertyDescriptor(globalThis,"window"),document:Object.getOwnPropertyDescriptor(globalThis,"document"),navigator:Object.getOwnPropertyDescriptor(globalThis,"navigator")};
  const element={set value(v:string){value=v;},setAttribute(){},style:{},focus(){},select(){selected=true;},remove(){removed=true;}};
  Object.defineProperty(globalThis,"window",{configurable:true,value:{isSecureContext:false,getSelection:()=>({rangeCount:1,getRangeAt:()=>({cloneRange:()=>({})}),removeAllRanges(){},addRange(){restored=true;}})}});
  Object.defineProperty(globalThis,"navigator",{configurable:true,value:{}});
  Object.defineProperty(globalThis,"document",{configurable:true,value:{activeElement:{focus(){focused=true;}},createElement:()=>element,body:{append(){appended=true;}},execCommand(cmd:string){command=cmd;return true;}}});
  try{await writeClipboardText("付款以验收合格为前提。");assert.equal(value,"付款以验收合格为前提。");assert.equal(command,"copy");assert.ok(appended&&removed&&focused&&selected&&restored);}
  finally{for(const [key,descriptor] of Object.entries(saved)){if(descriptor)Object.defineProperty(globalThis,key,descriptor);else Reflect.deleteProperty(globalThis,key);}}
});
