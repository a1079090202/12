// 通用前端行为（无内联脚本，兼容严格 CSP）：
//  - 表单上加 data-confirm="…"：提交前弹确认框；
//  - 元素上加 data-history-back：点击返回浏览器上一页。
(function () {
  document.addEventListener('submit', function (e) {
    var form = e.target;
    var msg = form.getAttribute && form.getAttribute('data-confirm');
    if (msg && !window.confirm(msg)) {
      e.preventDefault();
    }
  });

  document.addEventListener('click', function (e) {
    var el = e.target.closest && e.target.closest('[data-history-back]');
    if (el) {
      e.preventDefault();
      window.history.back();
    }
  });
})();
