package ru.akiev.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import ru.akiev.gateway.router.RoutingMode;

/**
 * Режим роутинга по умолчанию (gateway.routing в application.yml). В runtime меняется через
 * PUT /status/routing и сохраняется в БД (routing_settings) — после рестарта берётся оттуда;
 * этот конфиг действует, пока в БД ничего не сохранено (или после DELETE /status/routing).
 *
 * @param mode    priority | round-robin (регистр и «-»/«_» не важны)
 * @param primary имя провайдера, который в режиме priority идёт первым; пусто — чистый приоритет
 */
@ConfigurationProperties(prefix = "gateway.routing")
public record RoutingProperties(@DefaultValue("priority") RoutingMode mode, String primary) {
}
