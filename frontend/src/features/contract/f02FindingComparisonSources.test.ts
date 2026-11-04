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

const [React, { renderToStaticMarkup }, { FindingComparisonSources }] = await Promise.all([
  import("react"),
  import("react-dom/server"),
  import("./components/FindingComparisonSources.ts"),
]).finally(() => hooks.deregister());
import type { FindingSourceAnchor, FindingSourceProjection } from "./types.ts";

function buttons(node: unknown): Array<{ props: { onClick: () => void } }> {
  if (!React.isValidElement(node)) return [];
  const current = node.type === "button" ? [node as { props: { onClick: () => void } }] : [];
  return current.concat(React.Children.toArray(node.props.children).flatMap(buttons));
}

test("rendered F02 comparison buttons focus contract and award anchors independently", () => {
  const contract: FindingSourceAnchor = {
    role: "CONTRACT", projectId: "project-1", fileId: "contract-file", fileName: "合同正文.docx",
    pageNumber: 2, paragraphIndex: null, locator: "docx:paragraph:8", excerpt: "合同金额67000元",
  };
  const award: FindingSourceAnchor = {
    role: "AWARD_NOTICE", projectId: "project-1", fileId: "award-file", fileName: "中标通知书.png",
    pageNumber: 1, paragraphIndex: null, locator: "image:text:1", excerpt: "中标金额67000元",
  };
  const source: FindingSourceProjection = {
    available: true, reason: null, comparisonMode: "CROSS_FILE", sources: [contract, award],
  };
  const focused: FindingSourceAnchor[] = [];
  const props = { source, onFocus: (anchor: FindingSourceAnchor) => focused.push(anchor) };

  const markup = renderToStaticMarkup(React.createElement(FindingComparisonSources, props));
  assert.match(markup, /合同正文\.docx/);
  assert.match(markup, /中标通知书\.png/);
  assert.match(markup, /↔/);

  const renderedButtons = buttons(FindingComparisonSources(props));
  assert.equal(renderedButtons.length, 2);
  renderedButtons[0]?.props.onClick();
  renderedButtons[1]?.props.onClick();
  assert.deepEqual(focused, [contract, award]);
  assert.notEqual(focused[0]?.fileId, focused[1]?.fileId);
  assert.notEqual(focused[0]?.locator, focused[1]?.locator);
});
