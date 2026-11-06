-- Метрики входящего запроса для экрана «Задачи» (issue #45).
-- Тело запроса не хранится: только размеры, номер попытки runner и связь failover-попыток.
-- Старые строки остаются с NULL — агрегаты /stats/runs и /stats/efficiency это учитывают.
--   attempt            — заголовок x-attempt (целое; нечисло → NULL)
--   request_id         — UUID одного входящего POST /v1/chat/completions (общий у failover)
--   failover_index     — 0 у первого провайдера, далее 1, 2, …
--   messages_count     — число сообщений
--   request_chars      — сумма длин content
--   est_prompt_tokens  — оценка входа (RequestEstimator), есть и когда провайдер не прислал usage
--   max_tokens         — max_tokens из тела, NULL если клиент не прислал
--   stream             — клиент просил стрим
ALTER TABLE usage_record ADD COLUMN attempt INTEGER;
ALTER TABLE usage_record ADD COLUMN request_id VARCHAR(64);
ALTER TABLE usage_record ADD COLUMN failover_index INTEGER;
ALTER TABLE usage_record ADD COLUMN messages_count INTEGER;
ALTER TABLE usage_record ADD COLUMN request_chars BIGINT;
ALTER TABLE usage_record ADD COLUMN est_prompt_tokens INTEGER;
ALTER TABLE usage_record ADD COLUMN max_tokens INTEGER;
ALTER TABLE usage_record ADD COLUMN stream BOOLEAN;

-- Список запросов прогона: WHERE run_id = ? ORDER BY created_at
CREATE INDEX idx_usage_record_run_id_created_at ON usage_record (run_id, created_at);
