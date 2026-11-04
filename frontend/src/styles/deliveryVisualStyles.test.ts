import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("the authenticated portal stylesheet is included by the global style entry", async () => {
  const styles = await readFile(new URL("./index.css", import.meta.url), "utf8");
  assert.match(styles, /@import ['"]\.\/delivery-visual-v2\.css['"]/);
});
