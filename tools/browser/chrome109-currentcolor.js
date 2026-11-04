(function () {
  'use strict';
  if (CSS.supports('color', 'color-mix(in oklab,red,transparent)')) return;
  // Only these original rules mix currentColor with transparency. Compute its
  // channels locally; never replace fonts, layouts, literal colors or content.
  var selector = 'input,textarea,[class~="border-current/10"],[class~="border-current/20"]';
  var queued = false;
  function update() {
    queued = false;
    document.querySelectorAll(selector).forEach(function (element) {
      var match = /^rgba?\(\s*([\d.]+)[, ]+([\d.]+)[, ]+([\d.]+)/.exec(getComputedStyle(element).color);
      if (!match) return;
      var value = match[1] + ' ' + match[2] + ' ' + match[3];
      if (element.style.getPropertyValue('--audit-currentcolor-rgb') !== value) element.style.setProperty('--audit-currentcolor-rgb', value);
    });
  }
  function schedule() {
    if (!queued) { queued = true; requestAnimationFrame(update); }
  }
  function start() {
    update();
    new MutationObserver(schedule).observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style', 'disabled'] });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
