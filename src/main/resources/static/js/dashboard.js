// 首页看板：上海时区时钟 + 30 秒轻量自动刷新（不打断正在填写的表单）
(function () {
  var clockEl = document.getElementById('clock');
  function tick() {
    if (!clockEl) return;
    var s = new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
    }).format(new Date());
    clockEl.textContent = '当前 ' + s.replace(/\//g, '-') + '（北京时间）';
  }
  tick();
  setInterval(tick, 1000);

  var btn = document.getElementById('refreshBtn');
  if (btn) btn.addEventListener('click', function () { location.reload(); });

  setInterval(function () {
    var tag = document.activeElement && document.activeElement.tagName;
    if (tag !== 'INPUT' && tag !== 'SELECT' && tag !== 'TEXTAREA' && tag !== 'BUTTON') {
      location.reload();
    }
  }, 30000);
})();
