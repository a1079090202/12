// 调度页：已等候分钟数用浏览器当前时间计算（从进场时刻起算，精确到分，30 秒刷新）
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
