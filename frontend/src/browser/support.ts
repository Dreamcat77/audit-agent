export interface BrowserSupportController {
  supported: boolean;
  ready(): void;
  fail(code: 'LOAD_FAILED' | 'RENDER_FAILED'): void;
  report(): string;
}

declare global {
  interface Window { auditBrowserSupport?: BrowserSupportController }
}
