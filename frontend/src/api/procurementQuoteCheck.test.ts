import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string"
      ? loaded.source
      : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});

const { exportProcurementProject, getProcurementQuoteCheck, uploadProcurementQuoteCheck,
  uploadProcurementMaterialBatch, getProcurementMaterialBatch, confirmProcurementMaterialBidder,
  stageProcurementMaterials, analyzeProcurementMaterials } = await import("./audit.ts");

function envelope<T>(data: T): Response {
  return new Response(JSON.stringify({ code: 200, message: "success", data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

test("saving materials never invokes analysis and explicit analysis freezes the saved source identities", async t => {
  const originalFetch = globalThis.fetch; t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input, init = {}) => {
    const url = String(input); calls.push({ url, init });
    return envelope(url.endsWith('/csrf') ? { headerName: 'X-CSRF-TOKEN', token: 'csrf' }
      : { files: [], quote: null, collusion: null, blacklist: null, notices: [], report: null });
  }) as typeof fetch;
  await stageProcurementMaterials('project/1', [new File(['one'], '招标.docx')]);
  assert.equal(calls.at(-1)?.url, '/api/v1/procurement/projects/project%2F1/review-materials/uploads');
  assert.equal(calls.some(c => c.url.endsWith('/analyze') || c.url.endsWith('/review-report')), false);
  await stageProcurementMaterials('project/1', [new File(['two'], '投标.pdf')]);
  assert.equal(calls.filter(c => c.url.endsWith('/uploads')).length, 2);
  await analyzeProcurementMaterials('project/1', ['source-1', 'source-2']);
  assert.equal(calls.at(-1)?.url, '/api/v1/procurement/projects/project%2F1/review-materials/analyze');
  assert.deepEqual(JSON.parse(calls.at(-1)!.init.body as string), { sourceIds: ['source-1', 'source-2'] });
});

test("one batch request retains every selected material with session and CSRF", async t => {
  const originalFetch=globalThis.fetch; t.after(()=> {globalThis.fetch=originalFetch;});
  const calls: Array<{url:string;init:RequestInit}>=[];
  globalThis.fetch=(async(input,init={})=> {
    calls.push({url:String(input),init});
    return envelope(calls.length===1 ? {headerName:'X-CSRF-TOKEN',token:'batch-csrf'} : {files:[],quote:null,collusion:null,blacklist:null,notices:[]});
  }) as typeof fetch;
  await uploadProcurementMaterialBatch('project/1',[new File(['one'],'甲.docx'),new File(['two'],'乙.pdf')]);
  assert.equal(calls[1].url,'/api/v1/procurement/projects/project%2F1/review-materials/batch');
  assert.equal(calls[1].init.credentials,'include');
  assert.equal(new Headers(calls[1].init.headers).get('X-CSRF-TOKEN'),'batch-csrf');
  const parts=(calls[1].init.body as FormData).getAll('files') as File[];
  assert.deepEqual(parts.map(f=>f.name),['甲.docx','乙.pdf']);
  assert.deepEqual(await Promise.all(parts.map(f=>f.text())),['one','two']);
});

test("reopening is a read and bidder confirmation sends a JSON object rather than double encoding",async t=>{
  const originalFetch=globalThis.fetch;t.after(()=>{globalThis.fetch=originalFetch;});
  const calls:Array<{url:string;init:RequestInit}>=[];
  globalThis.fetch=(async(input,init={})=> {
    const url=String(input);calls.push({url,init});
    return envelope(url.endsWith('/csrf')?{headerName:'X-CSRF-TOKEN',token:'confirm-csrf'}:{files:[],quote:null,collusion:null,blacklist:null,notices:[]});
  }) as typeof fetch;
  await getProcurementMaterialBatch('project');
  assert.equal(calls.length,1);assert.equal(calls[0].init.method,'GET');
  await confirmProcurementMaterialBidder('project','source/1','甲科技');
  assert.equal(calls[2].url,'/api/v1/procurement/projects/project/review-materials/source%2F1/bidder');
  assert.deepEqual(JSON.parse(calls[2].init.body as string),{bidderName:'甲科技'});
});

test("uploadProcurementQuoteCheck sends Excel and project id without budget", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    if (calls.length === 1) {
      return envelope({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-f06" });
    }
    return envelope({
      id: "check-1",
      batchId: "BATCH-F06",
      budgetAmount: 100,
      averageQuote: 105,
      standardDeviation: 5,
      coefficientOfVariation: 0.0476,
      highlyConcentrated: true,
      riskLevel: "WARNING",
      createdTime: "2026-09-10T00:00:00Z",
      quotes: [],
      suspiciousSuppliers: [],
    });
  }) as typeof fetch;

  const file = new Blob(["quotes"], {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  }) as unknown as File;
  const result = await uploadProcurementQuoteCheck(file, "BATCH-F06");
  const form = calls[1]?.init.body as FormData;

  assert.equal(calls[1]?.url, "/api/v1/procurement/quote-checks/upload");
  assert.equal(calls[1]?.init.method, "POST");
  assert.equal(new Headers(calls[1]?.init.headers).get("X-CSRF-TOKEN"), "csrf-f06");
  assert.equal(new Headers(calls[1]?.init.headers).has("Content-Type"), false);
  assert.equal(form.get("batchId"), "BATCH-F06");
  assert.equal(form.has("budgetAmount"), false);
  assert.ok(form.get("file") instanceof Blob);
  assert.equal(result.riskLevel, "WARNING");
});

test("getProcurementQuoteCheck encodes the batch id and reads history", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: string[] = [];
  globalThis.fetch = (async (input: string | URL | Request) => {
    calls.push(String(input));
    return envelope({
      id: "check-2",
      batchId: "BATCH/F06",
      budgetAmount: 100,
      averageQuote: 100,
      standardDeviation: 0,
      coefficientOfVariation: 0,
      highlyConcentrated: true,
      riskLevel: "WARNING",
      createdTime: "2026-09-10T00:00:00Z",
      quotes: [],
      suspiciousSuppliers: [],
    });
  }) as typeof fetch;

  const result = await getProcurementQuoteCheck("BATCH/F06");

  assert.deepEqual(calls, ["/api/v1/procurement/quote-checks/BATCH%2FF06"]);
  assert.equal(result.batchId, "BATCH/F06");
});

test("exportProcurementProject downloads the authenticated Excel response", async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const calls: Array<{ url: string; init: RequestInit }> = [];
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(input), init });
    return new Response(new Blob(["xlsx-content"]), {
      status: 200,
      headers: {
        "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Content-Disposition": "attachment; filename*=UTF-8''%E9%87%87%E8%B4%AD%E9%9C%80%E6%B1%82_BA-2024-001_20260916.xlsx",
      },
    });
  }) as typeof fetch;

  const result = await exportProcurementProject("project/1");

  assert.equal(calls[0]?.url, "/api/v1/procurement/projects/project%2F1/export");
  assert.equal(calls[0]?.init.credentials, "include");
  assert.equal(result.filename, "采购需求_BA-2024-001_20260916.xlsx");
  assert.equal(await result.blob.text(), "xlsx-content");
});
