-- Метки запроса для дашборда «Эффективность» (issue #33): к какому прогону/issue/клиенту относится попытка.
--   run_id       — заголовок x-run-id (ai-runner, project-sage, ui-tester, archproger)
--   issue        — заголовок x-issue (номер issue, которую решает прогон)
--   client       — заголовок x-client (кто прислал: ai-runner, project-sage, ...)
--   request_user — поле "user" из тела запроса OpenAI (тоже идентификатор клиента)
-- Старые записи остаются с NULL — это «запросы без метки».
ALTER TABLE usage_record ADD COLUMN run_id VARCHAR(128);
ALTER TABLE usage_record ADD COLUMN issue VARCHAR(64);
ALTER TABLE usage_record ADD COLUMN client VARCHAR(64);
ALTER TABLE usage_record ADD COLUMN request_user VARCHAR(128);

CREATE INDEX idx_usage_record_run_id ON usage_record (run_id);
