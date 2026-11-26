import 'core-js/modules/es.global-this';
import 'core-js/modules/es.object.from-entries';
import 'core-js/modules/es.promise.all-settled';
import 'core-js/modules/es.string.replace-all';
import ResizeObserver from 'resize-observer-polyfill';

// Libraries such as charts/dialogs use ResizeObserver, absent in Chromium 69.
if (typeof window.ResizeObserver === 'undefined') {
  window.ResizeObserver = ResizeObserver;
}

// CSS.supports('gap') also returns true for browsers supporting grid only.
const probe = document.createElement('div');
probe.style.cssText = 'position:absolute;visibility:hidden;display:flex;flex-direction:column;row-gap:1px';
probe.appendChild(document.createElement('div'));
probe.appendChild(document.createElement('div'));
document.body.appendChild(probe);
if (probe.scrollHeight !== 1) document.documentElement.classList.add('flex-gap-not-supported');
probe.parentNode!.removeChild(probe);
