// 已等候分钟数：用浏览器当前时间（展示口径同样以进场时刻起算，精确到分）
(function () {
  function render() {
    document.querySelectorAll('.waiting-min').forEach(function (el) {
      var since = parseInt(el.getAttribute('data-since'), 10);
      var mins = Math.max(0, Math.floor((Date.now() - since * 1000) / 60000));
      el.textContent = mins + ' 分钟';
    });
  }
  render();
  setInterval(render, 30000);
})();
