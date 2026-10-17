// Страница «Эффективность»: два запроса (GET /stats/efficiency и GET /stats/runs), без SSE и фреймворков.
const fmt = n => (n ?? 0).toLocaleString('ru-RU');
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const dt = iso => iso ? new Date(iso).toLocaleString('ru-RU') : '—';
const dur = ms => ms == null ? '—' : ms < 60000 ? Math.round(ms / 1000) + ' с' : Math.round(ms / 60000) + ' мин';
// Первая справочная модель — основная для таблиц, остальные — во всплывающей подсказке
const cost = (list, cur) => !list || !list.length ? '—'
  : `<span title="${esc(list.map(c => c.model + ': ' + c.amount + ' ' + cur).join('\n'))}">~${list[0].amount} ${esc(cur)}</span>`;
const STATUS_RU = { SUCCESS: 'успех', FAILED: 'провал', NEEDS_SPLIT: 'разбить', REJECTED: 'отклонён', EXPIRED: 'истёк',
  RUNNING: 'идёт', QUEUED: 'в очереди', AWAITING_CONFIRMATION: 'ждёт подтверждения' };

async function load() {
  const days = document.getElementById('days').value;
  const group = document.getElementById('group').value;
  const [eff, groups] = await Promise.all([
    fetch(`/stats/efficiency?days=${days}`).then(r => r.json()),
    fetch(`/stats/runs?groupBy=${group}&days=${days}`).then(r => r.json())]);
  renderSummary(eff);
  renderRuns(eff);
  renderGroups(groups, eff.currency);
}

function renderSummary(e) {
  const s = e.summary;
  document.getElementById('s-runs').textContent = fmt(s.runs);
  document.getElementById('s-rate').textContent = s.success_rate == null ? '—'
    : `${Math.round(s.success_rate * 100)}% (${s.succeeded})`;
  document.getElementById('s-tok').textContent = s.tokens_per_success == null ? '—' : fmt(s.tokens_per_success);
  const c = s.cost && s.cost[0];
  document.getElementById('s-cost').textContent = c ? `~${c.amount} ${e.currency}` : '—';
  document.getElementById('s-cost-label').textContent = c ? `оценка на ${c.model} (все прогоны)` : 'нет цен в gateway.pricing';
  document.getElementById('note').textContent = e.pricing_note || '';
  document.getElementById('runner').textContent = e.runner_available
    ? 'ai-runner: данные получены.' : `ai-runner: ${e.runner_error || 'нет данных'} — показаны только данные шлюза.`;
}

function renderRuns(e) {
  const tb = document.getElementById('runs');
  if (!e.runs.length) { tb.innerHTML = '<tr><td colspan="10" class="muted">Прогонов нет</td></tr>'; return; }
  tb.innerHTML = e.runs.map(r => `<tr>
    <td>${esc(r.run_id)}</td><td>${esc(r.repo || '')}${r.issue ? ' #' + esc(r.issue) : ''}</td>
    <td class="${r.success ? 's-ok' : r.status === 'FAILED' ? 's-bad' : ''}">${esc(STATUS_RU[r.status] || r.status)}${r.category ? ' · ' + esc(r.category) : ''}</td>
    <td>${r.attempts ?? '—'}</td><td>${dur(r.duration_ms)}</td><td>${fmt(r.requests)}</td>
    <td class="${r.errors ? 's-bad' : ''}">${fmt(r.errors)}</td><td>${fmt(r.total_tokens)}</td>
    <td>${cost(r.cost, e.currency)}</td><td>${r.pr_url ? `<a href="${esc(r.pr_url)}" target="_blank">PR</a>` : '—'}</td></tr>`).join('');
}

function renderGroups(gs, cur) {
  const tb = document.getElementById('groups');
  if (!gs.length) { tb.innerHTML = '<tr><td colspan="10" class="muted">Нет запросов за период</td></tr>'; return; }
  tb.innerHTML = gs.map(g => `<tr>
    <td>${g.key == null ? '<span class="muted">(без метки)</span>' : esc(g.key)}</td><td>${esc(g.clients.join(', '))}</td>
    <td>${fmt(g.requests)}</td><td class="${g.errors ? 's-bad' : ''}">${fmt(g.errors)}</td>
    <td>${fmt(g.prompt_tokens)}</td><td>${fmt(g.completion_tokens)}</td>
    <td>${esc(Object.entries(g.providers).map(([p, n]) => p + ' ×' + n).join(', '))}</td>
    <td>${dt(g.first_at)}</td><td>${dt(g.last_at)}</td><td>${cost(g.cost, cur)}</td></tr>`).join('');
}

document.getElementById('refresh').onclick = load;
document.getElementById('days').onchange = load;
document.getElementById('group').onchange = load;
load().catch(err => { document.getElementById('runner').textContent = 'Ошибка загрузки: ' + err; });
