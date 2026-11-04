import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});

const { acceptedSupplementFiles, uploadProjectSupplements } =
  await import("./projectSupplementUpload.ts").finally(() => hooks.deregister());

function namedFile(name: string): File {
  return { name, size: 12, lastModified: 1 } as File;
}

test("ordinary supplement selection accepts supported documents and images but never ZIP", () => {
  const selection = acceptedSupplementFiles([
    namedFile("合同.pdf"), namedFile("清单.docx"), namedFile("旧版.DOC"), namedFile("扫描件.PNG"),
    namedFile("照片.jpeg"), namedFile("资料.zip"), namedFile("脚本.exe"),
  ]);
  assert.deepEqual(selection.accepted.map((file) => file.name),
    ["合同.pdf", "清单.docx", "旧版.DOC", "扫描件.PNG", "照片.jpeg"]);
  assert.deepEqual(selection.rejected.map((file) => file.name), ["资料.zip", "脚本.exe"]);
});

test("three supplements upload sequentially and one failure does not discard two successes", async () => {
  const files = [namedFile("a.pdf"), namedFile("b.docx"), namedFile("c.jpg")];
  const active: string[] = [];
  const completed: string[] = [];
  const operations = files.map((file, index) => ({ file, idempotencyKey: `key-${index + 1}` }));
  const dataSource = {
    async upload(_projectId: string, file: File, options: { idempotencyKey: string }) {
      assert.equal(active.length, 0, "uploads must not overlap");
      active.push(file.name);
      assert.equal(options.idempotencyKey, `key-${files.indexOf(file) + 1}`);
      active.pop();
      if (file.name === "b.docx") throw new Error("one file failed");
      completed.push(file.name);
      return {};
    },
  };

  const result = await uploadProjectSupplements(dataSource, "project-1", operations);

  assert.deepEqual(completed, ["a.pdf", "c.jpg"]);
  assert.deepEqual(result.succeeded.map((item) => item.file.name), ["a.pdf", "c.jpg"]);
  assert.deepEqual(result.failed.map((item) => item.file.name), ["b.docx"]);
  assert.equal(result.failed[0].idempotencyKey, "key-2",
    "an uncertain file keeps its immutable operation identity for retry");
});

test("supplement and metadata notices use customer business language", () => {
  const workspace = readFileSync(new URL("./useProjectWorkspace.ts", import.meta.url), "utf8");
  const page = readFileSync(new URL("./components/ProjectDetailPage.tsx", import.meta.url), "utf8");
  const candidatePanel = readFileSync(new URL("./components/CandidateConfirmationPanel.tsx", import.meta.url), "utf8");
  const metadataPanel = readFileSync(new URL("./components/ProjectMetadataConfirmationPanel.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(
    `${workspace}\n${page}\n${candidatePanel}\n${metadataPanel}`,
    /操作标识|服务端|幂等|Request ID|运行配置|存储异常|持久化/,
  );
  assert.match(workspace, /setUploadNotice\(`已忽略不支持的文件/);
  assert.match(page, /state\.uploadNotice/);
});

test("async admission accepts the next file while parsing is queued and never calls synchronous OCR upload", async () => {
  const calls: string[]=[];
  const result=await uploadProjectSupplements({
    async upload() { throw new Error("synchronous parser must not run"); },
    async uploadAsync(projectId: string,file: File,options: {idempotencyKey:string}) {
      calls.push(`${projectId}:${file.name}:${options.idempotencyKey}`);
      return {projectId,fileId:`file-${file.name}`,jobId:`job-${file.name}`,
        file:{id:`file-${file.name}`,projectId},job:{status:"QUEUED"}};
    },
  },"p1",[{file:namedFile("a.pdf"),idempotencyKey:"a"},{file:namedFile("b.pdf"),idempotencyKey:"b"}]);
  assert.equal(result.succeeded.length,2);
  assert.deepEqual(calls,["p1:a.pdf:a","p1:b.pdf:b"]);
});
