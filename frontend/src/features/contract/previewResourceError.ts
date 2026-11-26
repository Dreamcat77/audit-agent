/** Only module transport failures need a page refresh; parser/file errors keep their own handling. */
export function isPreviewResourceError(error: unknown): boolean {
  const message=error instanceof Error?error.message:String(error??'');
  return /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS|Loading chunk [\w-]+ failed/i.test(message);
}

export const previewResourceMessage='预览资源加载失败，请刷新页面后重试。';
