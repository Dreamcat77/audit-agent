import assert from "node:assert/strict";
import test from "node:test";

const selection = await import("./projectSourceSelection.ts").catch(() => ({}));

const pdf = {
  id: "file-1", projectId: "project-1", originalFilename: "contract.pdf", mediaType: "application/pdf",
  parsedText: "real extracted text",
};

test("candidate and projected available sources produce the same Project-owned navigation target", () => {
  assert.equal(typeof selection.candidateSourceTarget, "function");
  assert.equal(typeof selection.projectedSourceTarget, "function");
  const candidate = selection.candidateSourceTarget("project-1", {
    fileId: "file-1", pageNumber: 2, paragraphIndex: null,
    locator: "pdf:page:2", excerpt: "bounded source",
  }, [pdf]);
  const projected = selection.projectedSourceTarget({
    role: "CONTRACT", projectId: "project-1", fileId: "file-1", pageNumber: 2,
    paragraphIndex: null, locator: "pdf:page:2", excerpt: "bounded source",
  }, [pdf]);
  assert.deepEqual(candidate, projected);
  assert.deepEqual(projected, {
    projectId: "project-1", fileId: "file-1", pageNumber: 2, paragraphIndex: null,
    locator: "pdf:page:2", excerpt: "bounded source",
  });
});

test("incomplete or foreign projected sources cannot navigate", () => {
  assert.equal(typeof selection.projectedSourceTarget, "function");
  const source = { projectId: "project-1", fileId: "file-1", pageNumber: null,
    paragraphIndex: null, locator: "pdf:page:1", excerpt: "source" };
  assert.equal(selection.projectedSourceTarget({ ...source, role: "CONTRACT", locator: null }, [pdf]), null);
  assert.equal(selection.projectedSourceTarget({ ...source, role: "CONTRACT", projectId: "project-2" }, [pdf]), null);
});

test("valid source selection updates the SelectedProjectSource", () => {
  assert.equal(typeof selection.selectProjectSource, "function");
  const target = selection.candidateSourceTarget("project-1", {
    fileId: "file-1", pageNumber: 2, paragraphIndex: null,
    locator: "pdf:page:2", excerpt: "bounded source",
  }, [pdf]);
  let selected = null;

  const changed = selection.selectProjectSource(target, (next: unknown) => { selected = next; });

  assert.equal(changed, true);
  assert.deepEqual(selected, target);
});

test("rendered evidence target scrolls into view and receives programmatic focus", () => {
  assert.equal(typeof selection.navigateToProjectEvidence, "function");
  let options = null;
  let focusCount = 0;

  const navigated = selection.navigateToProjectEvidence({
    scrollIntoView(next: unknown) { options = next; },
    focus() { focusCount += 1; },
  });

  assert.equal(navigated, true);
  assert.deepEqual(options, { behavior: "smooth", block: "start" });
  assert.equal(focusCount, 1);
});

test("unavailable source does not select or navigate", () => {
  assert.equal(typeof selection.selectProjectSource, "function");
  assert.equal(typeof selection.navigateToProjectEvidence, "function");
  let selectionCount = 0;

  assert.equal(selection.selectProjectSource(null, () => { selectionCount += 1; }), false);
  assert.equal(selection.navigateToProjectEvidence(null), false);
  assert.equal(selectionCount, 0);
});

test("Project Evidence panel contract remains programmatically focusable and labelled", () => {
  assert.deepEqual(selection.PROJECT_EVIDENCE_PANEL_ACCESSIBILITY, {
    tabIndex: -1,
    headingId: "project-evidence-heading",
  });
});

test("source view models inline only PDF/images and bound the proven excerpt", () => {
  assert.equal(typeof selection.projectSourceView, "function");
  const selected = { projectId: "project-1", fileId: "file-1", pageNumber: 2,
    paragraphIndex: 4, locator: "pdf:page:2", excerpt: "x".repeat(700) };
  const pdfView = selection.projectSourceView(selected, pdf);
  assert.equal(pdfView.previewMode, "INLINE");
  assert.equal(pdfView.fileName, "contract.pdf");
  assert.equal(pdfView.pageNumber, 2);
  assert.equal(pdfView.paragraphIndex, 4);
  assert.equal(pdfView.excerpt.length, 501);
  assert.equal(pdfView.excerpt.endsWith("…"), true);
  assert.equal(pdfView.parsedText, "real extracted text");
  assert.equal(selection.projectSourceView(selected, {
    ...pdf, originalFilename: "contract.docx",
    mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  }).previewMode, "DOWNLOAD");
});

test("authenticated inline preview rejects response MIME mismatches and active image content", () => {
  assert.equal(selection.authenticatedInlinePreviewKind("application/pdf", "application/pdf"), "PDF");
  assert.equal(selection.authenticatedInlinePreviewKind("application/pdf", "text/html"), null);
  assert.equal(selection.authenticatedInlinePreviewKind("image/png", "image/png"), "IMAGE");
  assert.equal(selection.authenticatedInlinePreviewKind("image/svg+xml", "image/svg+xml"), null);
});
