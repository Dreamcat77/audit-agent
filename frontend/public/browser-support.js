/* Classic ES5 startup protection: must work before modules, React or app CSS. */
(function (w, d) {
  'use strict';
  var panel = d.getElementById('audit-browser-support');
  if (!panel) return;
  var root = d.getElementById('root');
  var diagnosticPage = d.body.getAttribute('data-audit-browser-diagnostics') === 'true';
  var mounted = false;
  var state = 'STARTING';
  var tests = [];
  var missing = [];
  var timer;
  function setText(element, value) {
    if ('textContent' in element) element.textContent = value;
    else element.innerText = value;
  }
  function text(id, value) { var element = d.getElementById(id); if (element) setText(element, value); }
  function check(name, available, optional) {
    tests.push(name + ': ' + (available ? '支持' : '不支持'));
    if (!available && !optional) missing.push(name);
  }
  function supports(property, value) {
    try {
      return !!(w.CSS && w.CSS.supports && (arguments.length === 1 ? w.CSS.supports(property) : w.CSS.supports(property, value)));
    } catch (ignore) { return false; }
  }
  check('现代脚本入口', 'noModule' in d.createElement('script'));
  check('Promise', typeof w.Promise === 'function');
  check('fetch', typeof w.fetch === 'function');
  check('URL', typeof w.URL === 'function' && typeof w.URLSearchParams === 'function');
  check('AbortController', typeof w.AbortController === 'function');
  check('TextEncoder', typeof w.TextEncoder === 'function');
  check('安全随机数', !!(w.crypto && typeof w.crypto.getRandomValues === 'function'));
  check('数组操作', typeof Array.prototype.at === 'function');
  check('字符串操作', typeof String.prototype.replaceAll === 'function');
  check('网格布局', supports('display', 'grid'));
  check('CSS 变量', supports('color', 'var(--audit-probe)'));
  check('样式层', typeof w.CSSLayerBlockRule === 'function');
  check('关系选择器', supports('selector(:has(*))'), true);
  var supported = !d.documentMode && missing.length === 0;
  var area = d.createElement('textarea');
  area.readOnly = true;
  area.setAttribute('aria-label', '浏览器检测信息');
  area.style.cssText = 'display:none;box-sizing:border-box;width:100%;height:260px;margin:12px 0;padding:10px;border:1px solid #aab8c8;color:#263c56;background:#fff;font:13px/1.6 Arial,sans-serif;';
  var actions = d.createElement('div');
  var copy = d.createElement('button');
  copy.type = 'button';setText(copy, '复制检测信息');
  copy.style.cssText = 'padding:9px 16px;margin:8px 12px 8px 0;border:1px solid #bdc9d5;background:#fff;color:#263c56;cursor:pointer;font:14px Arial,sans-serif;';
  var reload = copy.cloneNode(false);
  reload.type = 'button';setText(reload, '重新打开');
  var note = d.createElement('p');
  note.style.cssText = 'font-size:13px;color:#5b6b7d;';
  setText(note, '检测信息仅包含浏览器和显示能力，不包含账号、合同内容或访问地址；请手动交给维护人员。');
  function report() {
    return '采购内审浏览器检测 v1\n状态: ' + state + '\n'
      + '浏览器标识: ' + String(w.navigator.userAgent || '').slice(0, 500) + '\n'
      + '文档模式: ' + (d.documentMode || '非 IE 模式') + '\n'
      + '页面模式: ' + d.compatMode + '\n'
      + '窗口: ' + (w.innerWidth || d.documentElement.clientWidth) + 'x' + (w.innerHeight || d.documentElement.clientHeight) + '\n'
      + tests.join('\n');
  }
  function update() { area.value = report(); }
  function show(code, title, message) {
    state = code;
    panel.style.display = 'block';
    if (root) root.style.display = 'none';
    text('audit-browser-title', title);text('audit-browser-message', message);update();
  }
  copy.onclick = function () {
    update();area.style.display = 'block';area.focus();area.select();
    var copied = false;
    try { copied = !!d.execCommand('copy'); } catch (ignore) {}
    setText(copy, copied ? '已复制检测信息' : '请全选下方信息复制');
  };
  reload.onclick = function () { w.location.reload(); };
  actions.appendChild(copy);actions.appendChild(reload);
  panel.appendChild(actions);panel.appendChild(area);panel.appendChild(note);
  w.auditBrowserSupport = {
    supported: supported,
    ready: function () {
      if (!supported || diagnosticPage) return;
      mounted = true;state = 'READY';w.clearTimeout(timer);
      panel.style.display = 'none';if (root) root.style.display = '';update();
    },
    fail: function (code) {
      w.clearTimeout(timer);
      if (!supported) return;
      show(code === 'RENDER_FAILED' ? code : 'LOAD_FAILED', '暂时无法打开系统',
        '页面加载遇到问题，请重新打开；如果仍无法使用，请复制检测信息交给维护人员。');
    },
    report: report
  };
  if (diagnosticPage) {
    state = supported ? 'CAPABILITIES_PRESENT' : 'BROWSER_UNSUPPORTED';
    text('audit-browser-title', '浏览器检测');
    text('audit-browser-message', supported ? '已检测到当前页面需要的基础能力。排版或操作仍有问题时，请复制检测信息交给维护人员。'
      : '当前浏览器缺少部分页面能力，请复制检测信息交给维护人员处理兼容版本。');
    area.style.display = 'block';update();
  } else if (!supported) {
    show('BROWSER_UNSUPPORTED', '当前浏览器暂时无法打开系统',
      '浏览器与当前页面不兼容。请复制检测信息交给维护人员处理，无需自行安装或更改浏览器。');
  } else {
    timer = w.setTimeout(function () {
      if (!mounted) show('STARTUP_SLOW', '页面加载时间较长', '请稍候；如果长时间没有打开，请复制检测信息交给维护人员。');
    }, 20000);
    w.addEventListener('error', function () { if (!mounted) w.auditBrowserSupport.fail('LOAD_FAILED'); });
    w.addEventListener('unhandledrejection', function () { if (!mounted) w.auditBrowserSupport.fail('LOAD_FAILED'); });
    w.addEventListener('vite:preloadError', function () { w.auditBrowserSupport.fail('LOAD_FAILED'); });
  }
})(window, document);
