/* The old deployed frontend has no dedicated table class. Mark only this
   exact list so the offline typography patch cannot affect other tables. */
(function () {
  'use strict';
  var labels = ['需求编号', '项目名称', '申请部门', '采购类型', '预算金额',
    '当前状态', '智能体预审', '询比价报警', '创建时间', '操作'];
  var scheduled = false;
  function apply() {
    scheduled = false;
    var tables = document.querySelectorAll('table');
    for (var i = 0; i < tables.length; i++) {
      var cells = tables[i].querySelectorAll('thead th');
      if (cells.length !== labels.length) continue;
      var same = true;
      for (var j = 0; j < labels.length; j++) {
        if (cells[j].textContent.trim() !== labels[j]) { same = false; break; }
      }
      if (same) tables[i].classList.add('procurement-demand-table');
    }
  }
  function schedule() {
    if (!scheduled) { scheduled = true; requestAnimationFrame(apply); }
  }
  new MutationObserver(schedule).observe(document.documentElement, {childList: true, subtree: true});
  apply();
})();
