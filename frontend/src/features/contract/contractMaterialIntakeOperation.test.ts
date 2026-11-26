import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.ts`, context);
    return nextResolve(specifier, context);
  },
});
const { createContractMaterialIntakeOperation } =
  await import("./contractMaterialIntakeOperation.ts").finally(() => hooks.deregister());

const project = {
  id: "project-one", name: null, projectNo: null, category: null, versionNo: 0,
  createdAt: "2026-09-28T00:00:00Z", updatedAt: "2026-09-28T00:00:00Z",
};

test("automatic ordinary admission freezes initial file count before any upload",async()=>{
  const events:string[]=[];let count:number|undefined;
  const operation=createContractMaterialIntakeOperation({automaticAnalysis:true,dataSource:source({
    async createBlank(options:any){events.push("case");count=options.automaticAnalysisFileCount;return project;},
    async upload(_p:string,selected:File){events.push(selected.name);return {id:selected.name,projectId:project.id,ingestionStatus:"AVAILABLE"};},
  })},()=>crypto.randomUUID());
  assert.equal((await operation.start([file("one.pdf"),file("two.docx")])).kind,"COMPLETED");
  assert.equal(count,2);assert.deepEqual(events,["case","one.pdf","two.docx"]);
});

test("automatic ZIP admission uses the same independent intake",async()=>{
  let count:number|undefined;
  const operation=createContractMaterialIntakeOperation({automaticAnalysis:true,dataSource:source({
    async createBlank(options:any){count=options.automaticAnalysisFileCount;return project;},
    async upload(){return {id:"zip",projectId:project.id,ingestionStatus:"AVAILABLE"};},
    async getProject(){return {project,files:[{id:"zip",projectId:project.id,ingestionStatus:"AVAILABLE"}]};},
  })},()=>crypto.randomUUID());
  assert.equal((await operation.start([file("package.zip")])).kind,"COMPLETED");assert.equal(count,1);
});

function file(name: string): File {
  return new File([name], name, { type: "application/octet-stream", lastModified: 1 });
}

function source(overrides: Record<string, unknown> = {}) {
  return {
    async createBlank() { return project; },
    async upload(projectId: string, selected: File) {
      return { id: `file-${selected.name}`, projectId, originalFilename: selected.name, ingestionStatus: "AVAILABLE" };
    },
    async getProject() {
      return { project, intakeStatus: "FILES_AVAILABLE", files: [], candidates: [], extractionResults: [],
        associatedContracts: [], failedFileCount: 0, unsupportedFileCount: 0 };
    },
    ...overrides,
  } as any;
}

test("empty picker cancellation performs zero business writes", async () => {
  let calls = 0;
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async createBlank() { calls += 1; return project; },
    async upload() { calls += 1; return {}; },
  }) }, () => "unused");

  const result = await operation.start([]);

  assert.deepEqual(result, { kind: "CANCELLED" });
  assert.equal(calls, 0);
});

test("one ordinary selection admits legacy DOC and uploads every file to one Project", async () => {
  const createKeys: string[] = [];
  const uploads: Array<{ projectId: string; filename: string; key: string }> = [];
  const keys = ["create-key", "file-a-key", "file-b-key"];
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async createBlank(options: { idempotencyKey: string }) { createKeys.push(options.idempotencyKey); return project; },
    async upload(projectId: string, selected: File, options: { idempotencyKey: string }) {
      uploads.push({ projectId, filename: selected.name, key: options.idempotencyKey });
      return { id: `id-${selected.name}`, projectId, originalFilename: selected.name, ingestionStatus: "AVAILABLE" };
    },
  }) }, () => keys.shift()!);

  const result = await operation.start([file("合同.pdf"), file("旧版合同.doc")]);

  assert.equal(result.kind, "COMPLETED");
  assert.equal(result.projectId, "project-one");
  assert.deepEqual(createKeys, ["create-key"]);
  assert.deepEqual(uploads, [
    { projectId: "project-one", filename: "合同.pdf", key: "file-a-key" },
    { projectId: "project-one", filename: "旧版合同.doc", key: "file-b-key" },
  ]);
});

test("one ZIP delegates to secure package intake and never enters supplement upload", async () => {
  const calls: string[] = [];
  const uploaded = { id: "zip-file", projectId: project.id, originalFilename: "资料.zip", ingestionStatus: "AVAILABLE" };
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async createBlank() { calls.push("create"); return project; },
    async upload(_projectId: string, selected: File) { calls.push(`zip:${selected.name}`); return uploaded; },
    async getProject() { calls.push("refresh"); return { project, files: [uploaded], candidates: [], extractionResults: [], associatedContracts: [], failedFileCount: 0, unsupportedFileCount: 0 }; },
  }) }, (() => { let index = 0; return () => `zip-key-${++index}`; })());

  const result = await operation.start([file("资料.zip")]);

  assert.equal(result.kind, "COMPLETED");
  assert.equal(result.projectId, "project-one");
  assert.deepEqual(calls, ["create", "zip:资料.zip", "refresh"]);
});

test("a durably failed ZIP releases the terminal operation so a fresh package can be selected", async () => {
  let createCount = 0;
  const uploaded: Array<{ id: string; projectId: string; originalFilename: string; ingestionStatus: string }> = [];
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async createBlank() {
      createCount += 1;
      return { ...project, id: `project-${createCount}` };
    },
    async upload(projectId: string, selected: File) {
      const value = {
        id: `file-${createCount}`,
        projectId,
        originalFilename: selected.name,
        ingestionStatus: "AVAILABLE",
      };
      uploaded.push(value);
      return value;
    },
    async getProject(projectId: string) {
      const current = uploaded.at(-1)!;
      return {
        project: { ...project, id: projectId },
        files: [{ ...current, ingestionStatus: createCount === 1 ? "UPLOAD_FAILED" : "AVAILABLE" }],
        candidates: [], extractionResults: [], associatedContracts: [], failedFileCount: createCount === 1 ? 1 : 0,
        unsupportedFileCount: 0,
      };
    },
  }) }, (() => { let index = 0; return () => `zip-key-${++index}`; })());

  await assert.rejects(operation.start([file("失败资料.zip")]), /durable project file upload failed/);
  const completed = await operation.start([file("替换资料.zip")]);

  assert.equal(completed.kind, "COMPLETED");
  assert.equal(completed.projectId, "project-2");
  assert.equal(createCount, 2);
});

test("unsupported or mixed input is rejected before Project creation", async () => {
  let createCalls = 0;
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async createBlank() { createCalls += 1; return project; },
  }) });

  await assert.rejects(operation.start([file("合同.pdf"), file("资料.zip")]), /不能与普通材料同时上传/);
  assert.equal(createCalls, 0);
  await assert.rejects(operation.start([file("脚本.exe")]), /不支持的材料格式/);
  assert.equal(createCalls, 0);
});

test("partial failure retains successful files and retries only failed file with the same key", async () => {
  const attempts: Array<{ filename: string; key: string }> = [];
  let failOnce = true;
  const keys = ["create-key", "a-key", "b-key", "c-key"];
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async upload(_projectId: string, selected: File, options: { idempotencyKey: string }) {
      attempts.push({ filename: selected.name, key: options.idempotencyKey });
      if (selected.name === "b.docx" && failOnce) { failOnce = false; throw new TypeError("outcome unknown"); }
      return { id: `id-${selected.name}`, projectId: project.id, originalFilename: selected.name, ingestionStatus: "AVAILABLE" };
    },
  }) }, () => keys.shift()!);

  const partial = await operation.start([file("a.pdf"), file("b.docx"), file("c.jpg")]);
  assert.equal(partial.kind, "PARTIAL");
  assert.deepEqual(partial.succeeded.map((item) => item.file.name), ["a.pdf", "c.jpg"]);
  assert.deepEqual(partial.unresolved.map((item) => item.file.name), ["b.docx"]);

  const completed = await operation.retry();
  assert.equal(completed.kind, "COMPLETED");
  assert.deepEqual(attempts, [
    { filename: "a.pdf", key: "a-key" }, { filename: "b.docx", key: "b-key" },
    { filename: "c.jpg", key: "c-key" }, { filename: "b.docx", key: "b-key" },
  ]);
});

test("double click shares one in-flight operation", async () => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  let creates = 0;
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async createBlank() { creates += 1; await gate; return project; },
  }) }, (() => { let index = 0; return () => `key-${++index}`; })());
  const files = [file("合同.pdf")];

  const first = operation.start(files);
  const second = operation.start(files);
  assert.equal(first, second);
  release();
  await first;
  assert.equal(creates, 1);
});

test("uncertain Project creation keeps the original key and files for retry", async () => {
  const createKeys: string[] = [];
  let attempt = 0;
  const keys = ["retained-create", "retained-file"];
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async createBlank(options: { idempotencyKey: string }) {
      createKeys.push(options.idempotencyKey);
      attempt += 1;
      if (attempt === 1) throw new TypeError("response lost");
      return project;
    },
  }) }, () => keys.shift()!);

  const uncertain = await operation.start([file("合同.pdf")]);
  assert.equal(uncertain.kind, "OUTCOME_UNCERTAIN");
  assert.equal(uncertain.projectId, null);
  const completed = await operation.retry();

  assert.equal(completed.kind, "COMPLETED");
  assert.deepEqual(createKeys, ["retained-create", "retained-create"]);
});

test("a new selection after completion creates a fresh Project", async () => {
  let createCount = 0;
  const uploadedProjectIds: string[] = [];
  const operation = createContractMaterialIntakeOperation({ dataSource: source({
    async createBlank() {
      createCount += 1;
      return { ...project, id: `project-${createCount}` };
    },
    async upload(projectId: string, selected: File) {
      uploadedProjectIds.push(projectId);
      return { id: `id-${selected.name}`, projectId, originalFilename: selected.name, ingestionStatus: "AVAILABLE" };
    },
  }) }, (() => { let index = 0; return () => `key-${++index}`; })());

  const first = await operation.start([file("first.pdf")]);
  const second = await operation.start([file("second.pdf")]);

  assert.equal(first.projectId, "project-1");
  assert.equal(second.projectId, "project-2");
  assert.deepEqual(uploadedProjectIds, ["project-1", "project-2"]);
});

function storage() {
  const values=new Map<string,string>();
  return {get length(){return values.size;},key:(index:number)=>[...values.keys()][index]??null,
    getItem:(key:string)=>values.get(key)??null,setItem:(key:string,value:string)=>{values.set(key,value);},
    removeItem:(key:string)=>{values.delete(key);},values};
}

test("one explicit batch is one project, regardless of filenames or an older uncertain batch",async()=>{
  const saved=storage(),created:Array<{id:string;key:string;count:number}>=[],uploads:Array<{projectId:string;name:string}>=[];
  const dataSource=source({async createBlank(options:any){
    const id=`batch-${created.length+1}`;created.push({id,key:options.idempotencyKey,count:options.automaticAnalysisFileCount});
    return {...project,id};
  },async getProject(id:string){return {project:{...project,id}};},async uploadAsync(projectId:string,selected:File){
    uploads.push({projectId,name:selected.name});
    if(projectId==="batch-1")throw new Error("response lost");
    return {projectId,fileId:selected.name,jobId:`job-${selected.name}`,file:{id:selected.name,projectId},job:{status:"QUEUED"}};
  }});
  let key=0;const options={dataSource,automaticAnalysis:true,newProjectPerBatch:true,retained:{actorId:"actor",storage:saved}};
  const first=createContractMaterialIntakeOperation(options,()=>`command-${++key}`);
  assert.equal((await first.start([file("合同书.pdf")])).kind,"OUTCOME_UNCERTAIN");
  const old=[...saved.values.entries()];
  // Reloading and using the same filename still means a new user-selected batch, not a guessed association.
  const next=createContractMaterialIntakeOperation(options,()=>`command-${++key}`);
  const result=await next.start([file("合同书.pdf"),file("中标通知书.docx"),file("招标文件.pdf")]);
  assert.equal(result.kind,"COMPLETED");assert.equal(result.projectId,"batch-2");
  assert.deepEqual(created.map(row=>row.count),[1,3]);assert.notEqual(created[0].key,created[1].key);
  assert.deepEqual(uploads.slice(1).map(row=>row.projectId),["batch-2","batch-2","batch-2"]);
  assert.deepEqual([...saved.values.entries()],old,"unknown original command must remain unchanged");
  assert.equal((await next.start([file("下一批.doc")])).projectId,"batch-3");
});

test("an unreadable legacy journal cannot block a new batch and is preserved",async()=>{
  const saved=storage();const key="audit:contract-material-intake:v1:actor";
  saved.setItem(key,"corrupt original record");
  const operation=createContractMaterialIntakeOperation({dataSource:source(),newProjectPerBatch:true,
    retained:{actorId:"actor",storage:saved}});
  assert.equal((await operation.start([file("合同.docx")])).kind,"COMPLETED");
  assert.equal(saved.getItem(key),"corrupt original record");
  assert.equal((await operation.start([])).kind,"CANCELLED");
  await assert.rejects(operation.start([file("材料.exe")]),/格式|请选择/);
  assert.equal(saved.values.size,1);
});

test("double selection while sending one batch cannot create a second project",async()=>{
  let release!:(value:any)=>void,creates=0;
  const operation=createContractMaterialIntakeOperation({newProjectPerBatch:true,retained:{actorId:"actor",storage:storage()},
    dataSource:source({async createBlank(){creates++;return new Promise(resolve=>{release=resolve;});}})});
  const first=operation.start([file("合同书.pdf"),file("附件.doc")]);
  const duplicate=operation.start([file("合同书.pdf"),file("附件.doc")]);
  assert.equal(first,duplicate);assert.equal(creates,1);
  release(project);assert.equal((await first).kind,"COMPLETED");assert.equal(creates,1);
});

test("refresh preserves uncertain project and legacy DOC operation keys and reselects original bytes without a second Project",async()=>{
  const saved=storage();const createKeys:string[]=[];const uploadKeys:string[]=[];
  let first=true;
  const dataSource=source({async createBlank(options:{idempotencyKey:string}){
    createKeys.push(options.idempotencyKey);if(first){first=false;throw new Error("response lost");}return project;},
    async getCreationOperation(key:string){assert.equal(key,"original-create");return project;},
    async getUploadOperation(){throw {status:404};},
    async uploadAsync(_p:string,_f:File,options:{idempotencyKey:string}){
      uploadKeys.push(options.idempotencyKey);return {projectId:project.id,fileId:"f1",jobId:"j1",file:{id:"f1",projectId:project.id},job:{status:"QUEUED"}};}});
  const retained={actorId:"actor1",storage:saved};const keys=["original-create","original-upload"];
  const selected=file("original.doc");
  const firstPage=createContractMaterialIntakeOperation({dataSource,retained},()=>keys.shift()!);
  assert.equal((await firstPage.start([selected])).kind,"OUTCOME_UNCERTAIN");
  const refreshed=createContractMaterialIntakeOperation({dataSource,retained},()=>"must-not-renew");
  await assert.rejects(refreshed.start([file("new.pdf")]),/原操作|尚未确认/);
  const restored=await refreshed.restore();
  assert.equal(restored?.projectId,project.id);
  assert.equal((await refreshed.retry([selected])).kind,"COMPLETED");
  assert.deepEqual(createKeys,["original-create"]);
  assert.deepEqual(uploadKeys,["original-upload"]);
  assert.equal(saved.values.size,0);
});

test("lost accepted upload response is reconstructed using server GET and never resubmits bytes",async()=>{
  const saved=storage();let uploads=0;const readKeys:string[]=[];
  const receipt={projectId:project.id,fileId:"f1",jobId:"j1",file:{id:"f1",projectId:project.id},job:{status:"QUEUED"}};
  let published=false;
  const dataSource=source({async uploadAsync(){uploads++;published=true;throw new Error("response lost");},
    async getUploadOperation(_p:string,key:string){readKeys.push(key);if(!published)throw {status:404};return receipt;}});
  const retained={actorId:"actor1",storage:saved};const keys=["create-key","upload-key"];
  const original=createContractMaterialIntakeOperation({dataSource,retained},()=>keys.shift()!);
  await original.start([file("a.pdf")]);
  const reopened=createContractMaterialIntakeOperation({dataSource,retained},()=>"never-new");
  assert.equal((await reopened.restore())?.kind,"COMPLETED");
  assert.equal(uploads,1);assert.equal(readKeys.at(-1),"upload-key");
});

test("unproven byte publication keeps original identity and blocks a new selection after refresh",async()=>{
  const saved=storage();let published=false;let posts=0;
  const dataSource=source({async uploadAsync(){posts++;published=true;throw new Error("response lost");},
    async getUploadOperation(){if(!published)throw {status:404};return {projectId:project.id,fileId:"f1",jobId:null,file:{id:"f1",projectId:project.id},job:null};}});
  const retained={actorId:"actor1",storage:saved};const keys=["create","upload"];
  await createContractMaterialIntakeOperation({dataSource,retained},()=>keys.shift()!).start([file("a.pdf")]);
  const reopened=createContractMaterialIntakeOperation({dataSource,retained},()=>"never-new");
  assert.equal((await reopened.restore())?.kind,"OUTCOME_UNCERTAIN");
  assert.equal((await reopened.retry([file("a.pdf")])).kind,"OUTCOME_UNCERTAIN");
  assert.equal(posts,1);assert.equal(saved.values.size,1);
});

test("late response from an unmounted upload cannot overwrite the next operation's retained identity",async()=>{
  const saved=storage();let releaseA!:(value:any)=>void;let releaseB!:(value:any)=>void;let acceptedA=false;
  const pendingA=new Promise(resolve=>{releaseA=resolve;});const pendingB=new Promise(resolve=>{releaseB=resolve;});
  const receipt=(id:string)=>({projectId:project.id,fileId:id,jobId:`job-${id}`,file:{id,projectId:project.id},job:{status:"QUEUED"}});
  const dataSource=source({async getUploadOperation(_p:string,key:string){if(key==="a-upload"&&acceptedA)return receipt("a");throw {status:404};},
    async uploadAsync(_p:string,f:File){if(f.name==="a.pdf"){acceptedA=true;return pendingA;}return pendingB;}});
  const retained={actorId:"actor1",storage:saved};const keys=["a-create","a-upload","b-create","b-upload"];
  const original=createContractMaterialIntakeOperation({dataSource,retained},()=>keys.shift()!);
  const first=original.start([file("a.pdf")]);
  await new Promise(resolve=>setImmediate(resolve));
  const nextPage=createContractMaterialIntakeOperation({dataSource,retained},()=>keys.shift()!);
  assert.equal((await nextPage.restore())?.kind,"COMPLETED");
  const second=nextPage.start([file("b.pdf")]);await new Promise(resolve=>setImmediate(resolve));
  const expected=[...saved.values.values()][0];
  releaseA(receipt("a"));await first.catch(()=>{});
  assert.equal([...saved.values.values()][0],expected);
  releaseB(receipt("b"));await second;
});

test("interleaved tab initialization preserves both immutable commands instead of overwriting one actor journal",async()=>{
  const saved=storage();let interleave:()=>void=()=>{};let firstWrite=true;
  const rawSet=saved.setItem;saved.setItem=(key,value)=>{if(firstWrite){firstWrite=false;interleave();}rawSet(key,value);};
  let releases:Array<(value:any)=>void>=[];
  const dataSource=source({async createBlank(){return new Promise(resolve=>{releases.push(resolve);});},
    async getCreationOperation(){throw {status:404};},async getUploadOperation(){throw {status:404};},
    async uploadAsync(_p:string,f:File){return {projectId:project.id,fileId:f.name,jobId:`j-${f.name}`,
      file:{id:f.name,projectId:project.id},job:{status:"QUEUED"}};}});
  const retained={actorId:"actor",storage:saved};
  const keysA=["create-a","upload-a"],keysB=["create-b","upload-b"];
  const tabA=createContractMaterialIntakeOperation({dataSource,retained},()=>keysA.shift()!);
  const tabB=createContractMaterialIntakeOperation({dataSource,retained},()=>keysB.shift()!);
  let second!:Promise<any>;interleave=()=>{second=tabB.start([file("b.pdf")]);};
  const first=tabA.start([file("a.pdf")]);
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(saved.values.size,2,"individual command rows must survive interleaved actor-scoped initialization");
  for(const release of releases)release(project);
  await Promise.allSettled([first,second]);
});
