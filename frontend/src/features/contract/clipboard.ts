import { writeClipboardText } from "../../utils/clipboard.ts";

export interface ClipboardPort {
  writeText(value: string): Promise<void>;
}

export const browserClipboard: ClipboardPort = {
  async writeText(value: string): Promise<void> {
    await writeClipboardText(value);
  },
};

export async function copyText(clipboard: ClipboardPort, value: string): Promise<boolean> {
  try {
    await clipboard.writeText(value);
    return true;
  } catch {
    return false;
  }
}
