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

const UNKNOWN = '<span class="muted">неизвестно</span>';
const dt = iso => iso ? new Date(iso).toLocaleString('ru-RU') : '—';

function headerVal(p, v) {
  if (v != null) return fmt(v);
  return p.limits_seen_at ? UNKNOWN : '<span class="muted">неизвестно (провайдер не присылает x-ratelimit-*)</span>';
}

function pingRow(p) {
  const r = p.last_ping;
  if (!r) return `<span>Ping</span><span class="muted">ещё не проверяли</span>`;
  const cls = r.state === 'up' ? 's-ok' : 's-bad';
  const txt = r.state === 'up' ? 'доступен' : r.state === 'down' ? 'недоступен' : 'пропущен';
  return `<span>Ping (${time(r.checked_at)})</span><span class="${cls}">${txt}${r.http_status ? ' · HTTP ' + r.http_status : ''}${r.latency_ms != null ? ' · ' + fmt(r.latency_ms) + ' мс' : ''}${r.models_count != null ? ' · моделей: ' + r.models_count : ''}</span>
    ${r.error ? `<span>Ошибка ping</span><span class="err" title="${esc(r.error)}">${esc(r.error)}</span>` : ''}`;
}

function keyRows(k) {
  if (!k) return '';
  if (!k.data) return `<span>OpenRouter: кредиты</span><span>${k.error ? esc(k.error) : UNKNOWN}</span>`;
  const d = k.data;
  return `<span>OpenRouter: остаток кредитов</span><span>${d.limit == null ? 'без лимита' : esc(d.limit_remaining)}</span>
    <span>OpenRouter: потрачено / лимит</span><span>${esc(d.usage ?? '—')} / ${d.limit == null ? '∞' : esc(d.limit)}</span>
    <span>OpenRouter: free tier</span><span>${d.is_free_tier == null ? UNKNOWN : d.is_free_tier ? 'да' : 'нет'}</span>
    <span>Проверено</span><span>${time(k.fetched_at)}</span>`;
}

function renderProviders(list) {
  document.getElementById('providers').innerHTML = list.map(p => {
    return `<div class="card ${p.state}">
      <h3>${esc(p.name)} <span class="badge ${p.state}">${STATE_RU[p.state] ?? p.state}</span>
        ${p.state !== 'skipped' ? `<button class="btn small" data-ping="${esc(p.name)}">Ping</button>` : ''}</h3>
      <div class="model">${esc(p.model)} · приоритет ${p.priority}</div>
      <div class="kv">
        ${p.state === 'skipped' ? `<span>Причина</span><span>${esc(p.cooldown_reason)}</span>` : `
        ${pingRow(p)}
        <b class="sub">Квота</b>
        ${limitRow('RPM шлюза (минута)', p.rpm_used, p.rpm_limit)}
        ${limitRow('RPD шлюза (сутки)', p.rpd_used, p.rpd_limit)}
        <span>Остаток запросов у провайдера</span><span>${headerVal(p, p.remaining_requests)}</span>
        <span>Остаток токенов у провайдера</span><span>${headerVal(p, p.remaining_tokens)}</span>
        <span>Сброс лимита запросов</span><span>${p.reset_requests_at ? time(p.reset_requests_at) : UNKNOWN}</span>
        <span>Сброс лимита токенов</span><span>${p.reset_tokens_at ? time(p.reset_tokens_at) : UNKNOWN}</span>
        <span>Заголовки получены</span><span>${p.limits_seen_at ? time(p.limits_seen_at) : '—'}</span>
        ${keyRows(p.key_info)}
        <b class="sub">Сегодня</b>
        <span>Запросов</span><span>${fmt(p.requests_today)}</span>
        <span>Токенов (usage_record)</span><span>${fmt(p.tokens_today)}</span>
        <span>Ошибок</span><span>${fmt(p.errors_today)}</span>
        <span>Средняя задержка</span><span>${p.avg_latency_ms != null ? fmt(p.avg_latency_ms) + ' мс' : '—'}</span>`}
      </div>
      ${p.state === 'cooldown' ? `<div class="note">Пауза до ${time(p.cooldown_until)} (${esc(p.cooldown_reason)})</div>` : ''}
      ${p.last_error ? `<div class="note">Последняя ошибка: ${esc(p.last_error)} в ${time(p.last_error_at)}</div>` : ''}
    </div>`;
  }).join('');
}

async function post(url) {
  const r = await fetch(url, { method: 'POST' });
  return r.json();
}

document.getElementById('providers').addEventListener('click', async e => {
  const name = e.target.dataset?.ping;
  if (!name) return;
  e.target.disabled = true; e.target.textContent = '…';
  try { await post(`/status/providers/${encodeURIComponent(name)}/ping`); loadModels(false); } finally { e.target.disabled = false; e.target.textContent = 'Ping'; }
});

document.getElementById('ping-all').addEventListener('click', async e => {
  e.target.disabled = true;
  try { await post('/status/providers/ping'); loadModels(false); } finally { e.target.disabled = false; }
});

document.getElementById('models-refresh').addEventListener('click', () => loadModels(true));

async function loadModels(refresh) {
  const box = document.getElementById('models');
  try {
    const m = await (await fetch('/status/models' + (refresh ? '?refresh=true' : ''))).json();
    const gw = m.gateway.map(x => `<li><code>${esc(x.id)}</code> <span class="muted">→ ${esc(x.owned_by)}</span></li>`).join('');
    const prov = Object.entries(m.providers).map(([name, c]) => `<details><summary><b>${esc(name)}</b>: ${c.models.length} моделей
        ${c.fetched_at ? `<span class="muted">(загружено ${time(c.fetched_at)})</span>` : ''}${c.error ? ` <span class="err">${esc(c.error)}</span>` : ''}</summary>
        <ul class="mlist">${c.models.map(id => `<li><code>${esc(id)}</code></li>`).join('')}</ul></details>`).join('');
    box.innerHTML = `<div><b>Шлюз отдаёт в /v1/models:</b><ul>${gw || '<li class="muted">ничего</li>'}</ul></div>
      <div><b>Модели у провайдеров (GET /models, кэш):</b>${prov}</div>`;
  } catch (err) {
    box.innerHTML = `<span class="err">Не удалось загрузить модели: ${esc(err)}</span>`;
  }
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
loadModels(false);
