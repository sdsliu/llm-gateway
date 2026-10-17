package ru.akiev.gateway.stats;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Справочные цены платных моделей за 1M токенов (gateway.pricing в application.yml).
 * Шлюз работает на бесплатных провайдерах — эти цены нужны только для ОЦЕНКИ «сколько бы это стоило»,
 * они не связаны с реальными счетами. Цены меняются: сверяйте с сайтом провайдера.
 *
 * @param currency валюта (для подписи)
 * @param note     подпись на странице «Эффективность»
 * @param models   список моделей для сравнения
 */
@ConfigurationProperties(prefix = "gateway.pricing")
public record PricingProperties(
        @DefaultValue("USD") String currency,
        @DefaultValue("Оценка по справочным ценам платных моделей, не реальные расходы") String note,
        List<ModelPrice> models) {

    /** Цена одной модели: вход и выход за 1 000 000 токенов. */
    public record ModelPrice(String name, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }

    public List<ModelPrice> modelsOrEmpty() {
        return models == null ? List.of() : models;
    }
}
