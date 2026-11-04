const MAX_FOLDER_DEPTH = 16;
const MAX_SELECTED_FILES = 1000;
const droppedPaths = new WeakMap<File, string>();

export function droppedFilePath(file: File): string {
  return droppedPaths.get(file) ?? file.webkitRelativePath ?? '';
}

export interface DroppedEntry {
  name: string;
  fullPath?: string;
  isFile: boolean;
  isDirectory: boolean;
  file?: (success: (file: File) => void, failure: (error: Error) => void) => void;
  createReader?: () => {
    readEntries: (success: (entries: DroppedEntry[]) => void, failure: (error: Error) => void) => void;
  };
}

export async function collectProcurementDrop(entries: readonly (DroppedEntry | null)[], fallback: readonly File[]) {
  const files: File[] = [];
  const problems: string[] = [];
  const visit = async (entry: DroppedEntry, depth: number): Promise<void> => {
    if (files.length >= MAX_SELECTED_FILES) { if (!problems.includes('文件过多，请分次拖入')) problems.push('文件过多，请分次拖入'); return; }
    if (depth > MAX_FOLDER_DEPTH) { problems.push(`${entry.name}：文件夹层级过深`); return; }
    try {
      if (entry.isFile && entry.file) {
        const file = await new Promise<File>((resolve, reject) => entry.file!(resolve, reject));
        if (entry.fullPath) droppedPaths.set(file, entry.fullPath);
        files.push(file);
      } else if (entry.isDirectory && entry.createReader) {
        const reader = entry.createReader();
        while (true) {
          // Chromium returns directory contents in batches; the first read is not necessarily complete.
          const batch = await new Promise<DroppedEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
          if (!batch.length) break;
          for (const child of batch) await visit(child, depth + 1);
          if (files.length >= MAX_SELECTED_FILES) break;
        }
      } else problems.push(`${entry.name}：无法读取`);
    } catch { problems.push(`${entry.name}：读取失败，其他文件仍可上传`); }
  };
  if (entries.some(Boolean)) {
    for (const entry of entries) if (entry) await visit(entry, 0);
  } else {
    for (const file of fallback) {
      if (files.length >= MAX_SELECTED_FILES) { problems.push('文件过多，请分次拖入'); break; }
      files.push(file);
    }
  }
  return {files, problems};
}
