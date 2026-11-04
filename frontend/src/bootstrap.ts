import type {} from './browser/support';

// Keep the application and its dependencies out of browsers that cannot run it.
if (!window.auditBrowserSupport || window.auditBrowserSupport.supported) {
  void import('./main.tsx').catch(() => window.auditBrowserSupport?.fail('LOAD_FAILED'));
}
