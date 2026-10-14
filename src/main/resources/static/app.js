// Дашборд LLM Gateway. Без сборки и фреймворков: vanilla JS + Chart.js из CDN.
//
// Обновления приходят через Server-Sent Events (SSE): браузерный EventSource держит одно
// HTTP-соединение к /status/stream, сервер дописывает в него события:
//   event: snapshot  — полный срез состояния (при подключении, после каждого запроса,
//                      при смене состояния провайдера);
//   event: heartbeat — раз в ~15 с, чтобы соединение не считалось «мёртвым».
// Если соединение рвётся (перезапуск сервера, сеть), EventSource САМ переподключается
// через retry-интервал (сервер присылает retry: 3000), и мы снова получаем snapshot.
// Поэтому никакого setInterval/polling тут нет.

const STATE_RU = { healthy: 'работает', cooldown: 'cooldown', limit: 'лимит', skipped: 'пропущен' };
const COLORS = ['#22c55e', '#3b82f6', '#a855f7', '#f59e0b', '#ef4444', '#14b8a6'];
const fmt = n => (n ?? 0).toLocaleString('ru-RU');
const time = iso => iso ? new Date(iso).toLocaleTimeString('ru-RU') : '—';
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

let chart;
let lastRecentKey = null;
const colorOf = {};

function connect() {
  const conn = document.getElementById('conn');
  const es = new EventSource('/status/stream');
  es.onopen = () => { conn.textContent = '● онлайн'; conn.className = 'conn on'; };
  es.onerror = () => { conn.textContent = '● переподключение…'; conn.className = 'conn off'; };
  es.addEventListener('snapshot', e => render(JSON.parse(e.data)));
  es.addEventListener('heartbeat', () => { conn.textContent = '● онлайн'; conn.className = 'conn on'; });
}

function render(s) {
  s.providers.forEach((p, i) => { colorOf[p.name] ??= COLORS[i % COLORS.length]; });
  document.getElementById('t-req').textContent = fmt(s.today.requests);
  document.getElementById('t-tok').textContent = fmt(s.today.tokens);
  document.getElementById('t-err').textContent = fmt(s.today.errors);
  document.getElementById('t-upd').textContent = time(s.generated_at);
  renderProviders(s.providers);
  renderChart(s);
  renderRecent(s.recent);
}

function limitRow(label, used, limit) {
  if (!limit) return `<span>${label}</span><span>${fmt(used)} / ∞</span>`;
  const pct = Math.min(100, Math.round(100 * (used ?? 0) / limit));
  const color = pct >= 90 ? 'var(--bad)' : pct >= 70 ? 'var(--warn)' : 'var(--ok)';
  return `<span>${label}</span><span>${fmt(used)} / ${fmt(limit)}</span>
          <div class="bar"><i style="width:${pct}%;background:${color}"></i></div>`;
}

function renderProviders(list) {
  document.getElementById('providers').innerHTML = list.map(p => {
    const key = p.key_info?.data;
    return `<div class="card ${p.state}">
      <h3>${esc(p.name)} <span class="badge ${p.state}">${STATE_RU[p.state] ?? p.state}</span></h3>
      <div class="model">${esc(p.model)} · приоритет ${p.priority}</div>
      <div class="kv">
        ${p.state === 'skipped' ? `<span>Причина</span><span>${esc(p.cooldown_reason)}</span>` : `
        ${limitRow('RPM (минута)', p.rpm_used, p.rpm_limit)}
        ${limitRow('RPD (сутки)', p.rpd_used, p.rpd_limit)}
        <span>Остаток запросов (заголовки)</span><span>${p.remaining_requests != null ? fmt(p.remaining_requests) : '—'}</span>
        <span>Остаток токенов (заголовки)</span><span>${p.remaining_tokens != null ? fmt(p.remaining_tokens) : '—'}</span>
        <span>Запросов сегодня</span><span>${fmt(p.requests_today)}</span>
        <span>Токенов сегодня</span><span>${fmt(p.tokens_today)}</span>
        <span>Ошибок сегодня</span><span>${fmt(p.errors_today)}</span>
        <span>Средняя задержка</span><span>${p.avg_latency_ms != null ? fmt(p.avg_latency_ms) + ' мс' : '—'}</span>
        ${key ? `<span>OpenRouter: остаток кредитов</span><span>${key.limit_remaining ?? '∞'}</span>` : ''}`}
      </div>
      ${p.state === 'cooldown' ? `<div class="note">Пауза до ${time(p.cooldown_until)} (${esc(p.cooldown_reason)})</div>` : ''}
      ${p.last_error ? `<div class="note">Последняя ошибка: ${esc(p.last_error)} в ${time(p.last_error_at)}</div>` : ''}
    </div>`;
  }).join('');
}

function renderChart(s) {
  const labels = s.hourly.map(b => new Date(b.hour).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }));
  const names = s.providers.filter(p => p.state !== 'skipped').map(p => p.name);
  const datasets = names.map(n => ({
    label: n, backgroundColor: colorOf[n], borderRadius: 4,
    data: s.hourly.map(b => b.requests[n] ?? 0),
  }));
  if (typeof Chart === 'undefined') return; // CDN недоступен — остальной дашборд работает
  if (!chart) {
    chart = new Chart(document.getElementById('chart'), {
      type: 'bar',
      data: { labels, datasets },
      options: {
        responsive: true, maintainAspectRatio: false, animation: { duration: 300 },
        scales: { x: { stacked: true, ticks: { color: '#94a3b8' }, grid: { color: '#334155' } },
                  y: { stacked: true, beginAtZero: true, ticks: { color: '#94a3b8', precision: 0 }, grid: { color: '#334155' } } },
        plugins: { legend: { labels: { color: '#e2e8f0' } } },
      },
    });
  } else {
    chart.data.labels = labels;
    chart.data.datasets = datasets;
    chart.update();
  }
}

function renderRecent(rows) {
  const tbody = document.getElementById('recent');
  if (!rows.length) { tbody.innerHTML = '<tr><td colspan="9" class="muted">Пока нет запросов</td></tr>'; return; }
  const newestKey = rows[0].created_at + rows[0].provider;
  const isNew = lastRecentKey !== null && newestKey !== lastRecentKey;
  lastRecentKey = newestKey;
  tbody.innerHTML = rows.map((r, i) => `<tr class="${isNew && i === 0 ? 'new' : ''}">
    <td>${time(r.created_at)}</td><td>${esc(r.provider)}</td><td>${esc(r.model)}</td>
    <td class="${r.status < 300 ? 's-ok' : 's-bad'}">${r.status}</td><td>${fmt(r.latency_ms)} мс</td>
    <td>${r.prompt_tokens ?? '—'}</td><td>${r.completion_tokens ?? '—'}</td><td>${r.total_tokens ?? '—'}</td>
    <td class="err" title="${esc(r.error)}">${esc(r.error)}</td></tr>`).join('');
}

connect();
