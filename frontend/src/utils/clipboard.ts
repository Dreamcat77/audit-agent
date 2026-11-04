/** Clipboard support for HTTPS and the existing intranet HTTP deployment. */
export async function writeClipboardText(text: string): Promise<void> {
  if (!text.trim()) throw new Error("empty clipboard text");
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(text); return; } catch { /* Use the browser fallback. */ }
  }
  const active = document.activeElement as HTMLElement | null;
  const selection = window.getSelection();
  const ranges = selection ? Array.from({length: selection.rangeCount}, (_, i) => selection.getRangeAt(i).cloneRange()) : [];
  const input = document.createElement("textarea");
  input.value = text; input.setAttribute("readonly", "");
  input.style.position = "fixed"; input.style.left = "-9999px";
  document.body.append(input);
  try { input.focus(); input.select(); if (!document.execCommand("copy")) throw new Error("copy unavailable"); }
  finally {
    input.remove(); active?.focus({preventScroll: true}); selection?.removeAllRanges();
    for (const range of ranges) selection?.addRange(range);
  }
}
