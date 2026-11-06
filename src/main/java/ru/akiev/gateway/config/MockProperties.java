package ru.akiev.gateway.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки mock-провайдера ({@code gateway.mock}). Сам провайдер включается только в профилях
 * {@code mock} и {@code test}; в профиле по умолчанию эти поля ни на что не влияют.
 *
 * @param scenariosDir     каталог с JSON-файлами сценариев ({@code diff-ok.json} и т.д.).
 *                         Пусто — только classpath {@code mock-scenarios/}. Файл из каталога
 *                         перекрывает одноимённый файл с classpath.
 * @param defaultScenario  сценарий, если нет заголовка {@code x-mock-scenario} и {@code run_id}
 *                         не называет известный сценарий
 * @param exclusive        {@code true} — в роутинге и на дашборде только {@code type: mock}
 *                         (так в {@code application-mock.yml}). Группы моделей при этом не применяются:
 *                         любое имя модели отвечает mock-провайдер.
 */
@ConfigurationProperties(prefix = "gateway.mock")
public record MockProperties(
        String scenariosDir,
        @DefaultValue("diff-ok") String defaultScenario,
        @DefaultValue("false") boolean exclusive) {

    public MockProperties {
        if (scenariosDir != null && scenariosDir.isBlank()) {
            scenariosDir = null;
        }
        if (defaultScenario == null || defaultScenario.isBlank()) {
            defaultScenario = "diff-ok";
        } else {
            defaultScenario = defaultScenario.trim().toLowerCase(Locale.ROOT);
        }
    }
}
