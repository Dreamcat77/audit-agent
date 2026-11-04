const SAFE_INLINE_IMAGE_TYPES = new Set([
  "image/avif",
  "image/bmp",
  "image/gif",
  "image/jpeg",
  "image/png",
  "image/webp",
]);

function baseMediaType(value: string): string {
  return value.split(";", 1)[0].trim().toLowerCase();
}

export function authenticatedInlinePreviewKind(
  declaredContentType: string,
  responseContentType: string,
): "PDF" | "IMAGE" | null {
  const declared = baseMediaType(declaredContentType);
  const response = baseMediaType(responseContentType);
  if (declared === "application/pdf" && response === "application/pdf") return "PDF";
  if (declared.startsWith("image/") && SAFE_INLINE_IMAGE_TYPES.has(response)) return "IMAGE";
  return null;
}
