/** A failed or uncertain file never prevents subsequent independent uploads. */
export async function uploadFilesIndependently<T>(files: readonly File[], upload: (file: File) => Promise<T>,
  onResult: (file: File, result: T) => void, shouldContinue: () => boolean = () => true) {
  const failed: { file: File; cause: unknown }[] = [];
  for (const file of files) {
    if (!shouldContinue()) break;
    try {
      const result = await upload(file);
      if (!shouldContinue()) break;
      onResult(file, result);
    } catch (cause) {
      if (!shouldContinue()) break;
      failed.push({file, cause});
    }
  }
  return failed;
}
export const PROCUREMENT_MAX_UPLOAD_BYTES = 500 * 1024 * 1024;
export function otherPendingFiles(pending: readonly File[], selected: readonly File[]): File[] {
  return pending.filter(file => !selected.some(other => other.name === file.name
    && other.size === file.size && other.lastModified === file.lastModified));
}
export function procurementFileProblem(file: Pick<File, 'name' | 'size'>, extensions: RegExp): string | null {
  if (!extensions.test(file.name)) return '文件格式不支持';
  if (file.size <= 0) return '文件为空';
  if (file.size > PROCUREMENT_MAX_UPLOAD_BYTES) return '文件超过 500 MB';
  return null;
}
