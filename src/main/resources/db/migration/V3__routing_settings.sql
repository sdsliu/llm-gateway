-- Режим роутинга, выбранный на дашборде (PUT /status/routing), чтобы он переживал рестарт.
-- Ровно одна строка (id = 1). Нет строки — берём gateway.routing из application.yml.
--   mode             — PRIORITY | ROUND_ROBIN
--   primary_provider — имя провайдера, который в PRIORITY идёт первым (NULL — по priority).
--                      Колонка не "primary": это зарезервированное слово SQL.
CREATE TABLE routing_settings (
    id               INTEGER      NOT NULL PRIMARY KEY,
    mode             VARCHAR(32)  NOT NULL,
    primary_provider VARCHAR(64),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT routing_settings_single_row CHECK (id = 1)
);
