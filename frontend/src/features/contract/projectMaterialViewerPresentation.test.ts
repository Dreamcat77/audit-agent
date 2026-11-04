import assert from "node:assert/strict";
import test from "node:test";
import {
  locateProjectMaterialText,
  projectMaterialFileOnlyNotice,
  projectBackedMaterialControlsPresentation,
  projectMaterialContentPresentation,
  scrollToProjectMaterialLocation,
} from "./projectMaterialViewerPresentation.ts";

const parsedDocx = {
  mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  parsedText: "智慧办公终端采购合同\n合同金额 528000 元\n双方签章",
  provenance: { methodLabel: "Word 文本解析", confidenceLabel: null },
};

test("a parsed Project DOCX has readable in-page content and keeps original download available", () => {
  const presentation = projectMaterialContentPresentation(parsedDocx);
  assert.deepEqual(presentation, {
    kind: "PARSED_TEXT",
    text: parsedDocx.parsedText,
    originalDownloadAvailable: true,
    provenanceNotice: "材料内容已读取 · 原件可下载",
  });
  assert.match(presentation.text ?? "", /合同金额 528000/);
  assert.notEqual(presentation.kind, "DOWNLOAD_ONLY");
});

test("durable image text stays readable without exposing extraction method or confidence", () => {
  const presentation = projectMaterialContentPresentation({
    mediaType: "application/pdf",
    parsedText: "扫描合同正文",
    provenance: { methodLabel: "OCR 文字识别", confidenceLabel: "91.0%" },
  });
  assert.equal(presentation.kind, "PARSED_TEXT");
  assert.equal(presentation.text, "扫描合同正文");
  assert.equal(presentation.provenanceNotice, "材料内容已读取 · 原件可下载");
  assert.doesNotMatch(presentation.provenanceNotice, /OCR|置信度|PaddleOCR|PDFBox|Apache POI/);
});

test("Project-backed Contract reuses materials and has no primary reupload action", () => {
  assert.deepEqual(projectBackedMaterialControlsPresentation(), {
    sourceLabel: "复用项目资料",
    supplementalGuidance: "如需补充，请返回项目详情使用“补充项目资料”",
    primaryUploadAction: null,
  });
});

test("a unique durable excerpt creates an exact mark and centered scroll target", () => {
  const located = locateProjectMaterialText(
    "第一段\n合同金额为528000元\n第三段",
    { excerpt: "合同金额为528000元", paragraphIndex: 2 },
  );
  assert.equal(located.kind, "EXACT");
  assert.equal(located.statusText, "已定位来源");
  assert.equal(located.exactMatch, "合同金额为528000元");
  assert.equal(located.scrollTarget, true);
  assert.deepEqual(located.scrollOptions, { behavior: "smooth", block: "center" });
  let observed: unknown = null;
  assert.equal(scrollToProjectMaterialLocation({
    scrollIntoView(options) { observed = options; },
  }, located), true);
  assert.deepEqual(observed, { behavior: "smooth", block: "center" });
});

test("validated AI excerpt locates across Unicode width and whitespace differences", () => {
  const located = locateProjectMaterialText(
    "中标供应商：\n  测试科技有限公司",
    { excerpt: "中标供应商: 测试科技有限公司", paragraphIndex: null },
  );
  assert.equal(located.kind, "EXACT");
  assert.equal(located.exactMatch, "中标供应商：\n  测试科技有限公司");
});

test("a repeated excerpt never claims character precision and falls back to the proven paragraph", () => {
  const located = locateProjectMaterialText(
    "合同金额为528000元\n第二段\n合同金额为528000元",
    { excerpt: "合同金额为528000元", paragraphIndex: 3 },
  );
  assert.equal(located.kind, "PARAGRAPH");
  assert.equal(located.statusText, "已定位到第 3 段");
  assert.equal(located.highlightedParagraph, "合同金额为528000元");
  assert.equal(located.scrollTarget, true);
});

test("a known file with a missing excerpt is truthful instead of searching a similar first match", () => {
  assert.deepEqual(locateProjectMaterialText(
    "只有相似合同金额内容",
    { excerpt: "不存在的精确摘录", paragraphIndex: null },
  ), {
    kind: "NOT_FOUND",
    statusText: "已打开来源材料，但未找到可唯一定位的原文。",
    scrollTarget: false,
    scrollOptions: null,
  });
});

test("file-only evidence uses the exact truthful locator fallback notice", () => {
  assert.equal(projectMaterialFileOnlyNotice(), "暂无法精确定位原文位置，可查看来源材料。");
});
