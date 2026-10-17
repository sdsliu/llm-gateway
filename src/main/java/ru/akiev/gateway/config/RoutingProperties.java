package ru.akiev.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import ru.akiev.gateway.router.RoutingMode;

/**
 * Режим роутинга по умолчанию (gateway.routing в application.yml). В runtime меняется через
 * PUT /status/routing и живёт только в памяти: после рестарта снова берётся отсюда.
 *
 * @param mode    priority | round-robin (регистр и «-»/«_» не важны)
 * @param primary имя провайдера, который в режиме priority идёт первым; пусто — чистый приоритет
 */
@ConfigurationProperties(prefix = "gateway.routing")
public record RoutingProperties(@DefaultValue("priority") RoutingMode mode, String primary) {
}
