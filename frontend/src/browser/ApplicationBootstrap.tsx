import { Component, useEffect, type ReactNode } from 'react';
import type {} from './support';

class ApplicationErrorBoundary extends Component<{children: ReactNode}, {failed: boolean}> {
  state = {failed: false};
  static getDerivedStateFromError() { return {failed: true}; }
  componentDidCatch() { window.auditBrowserSupport?.fail('RENDER_FAILED'); }
  render() { return this.state.failed ? null : this.props.children; }
}

function ApplicationReady({children}: {children: ReactNode}) {
  useEffect(() => {
    if (window.auditBrowserSupport) window.auditBrowserSupport.ready();
    else {
      // A failed classic-script request should not hide an otherwise working app.
      const root = document.getElementById('root');
      const notice = document.getElementById('audit-browser-support');
      if (root) root.style.display = '';
      if (notice) notice.style.display = 'none';
    }
  }, []);
  return children;
}

export function ApplicationBootstrap({children}: {children: ReactNode}) {
  return <ApplicationErrorBoundary><ApplicationReady>{children}</ApplicationReady></ApplicationErrorBoundary>;
}
