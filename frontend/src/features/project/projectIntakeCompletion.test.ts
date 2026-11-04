import assert from "node:assert/strict";
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

const { completedProjectIntake } = await import("./projectIntakeCompletion.ts").finally(() => hooks.deregister());

const project = {
  id: "project-internal-id", name: null, projectNo: null,
  category: "办公设备", metadataConfirmed: false, versionNo: 0, createdAt: "2026-08-31T00:00:00Z",
  updatedAt: "2026-08-31T00:01:00Z",
};

function file(id: string, values: Record<string, unknown>) {
  return {
    id, projectId: project.id, parentArchiveFileId: "package-id", normalizedRelativePath: id,
    originalFilename: `${id}.docx`, mediaType: "application/octet-stream",
    category: "SUPPLEMENT", ingestionStatus: "AVAILABLE", parseStatus: "PARSED",
    sizeBytes: 10, parsedText: "text", parseMethod: "DOCX_TEXT", parseEngine: "engine",
    parseConfidence: null, pageCount: 1, parseDurationMs: 10, safeReasonCode: null,
    createdAt: "2026-08-31T00:00:00Z", updatedAt: "2026-08-31T00:01:00Z", ...values,
  };
}

test("completed intake keeps only durable display facts and factual processing counts", () => {
  const packageFile = file("package-id", {
    parentArchiveFileId: null, originalFilename: "项目资料包.zip", category: "PACKAGE",
    parseStatus: "NOT_APPLICABLE", parsedText: null, parseMethod: null,
  });
  const result = completedProjectIntake({
    project,
    file: packageFile,
    detail: {
      project, intakeStatus: "FILES_AVAILABLE",
      files: [
        packageFile,
        file("word", { parseMethod: "DOC_TEXT", originalFilename: "旧版合同.doc" }),
        file("pdf", { parseMethod: "PDF_TEXT", originalFilename: "采购文件.pdf" }),
        file("ocr", { parseMethod: "OCR", originalFilename: "扫描件.png", parseConfidence: 0.9868 }),
        file("failed", { ingestionStatus: "UPLOAD_FAILED", parseStatus: "FAILED", parsedText: null,
          parseMethod: null, safeReasonCode: "OCR_FAILED" }),
      ],
      candidates: [], extractionResults: [
        { sourceFileId: "word", factType: "PROJECT_NAME", groupKey: null,
          valueText: "数据中心办公设备采购项目", normalizedValue: "数据中心办公设备采购项目",
          valueAmount: null, sourceText: "项目名称", pageNumber: null, paragraphIndex: 1,
          sourceLocator: "document/fact:PROJECT_NAME" },
        { sourceFileId: "word", factType: "PROJECT_NO", groupKey: null,
          valueText: "CG-2026-002", normalizedValue: "CG-2026-002", valueAmount: null,
          sourceText: "项目编号", pageNumber: null, paragraphIndex: 2,
          sourceLocator: "document/fact:PROJECT_NO" },
      ], associatedContracts: [], failedFileCount: 1,
      unsupportedFileCount: 0,
    },
  });

  assert.deepEqual(result, {
    projectId: "project-internal-id",
    projectName: "数据中心办公设备采购项目",
    projectNo: "CG-2026-002",
    packageFilename: "项目资料包.zip",
    processedFileCount: 4,
    textParsedFileCount: 2,
    ocrFileCount: 1,
    failedFileCount: 1,
  });
  assert.equal(Object.values(result).some((value) => value instanceof File), false);
});
