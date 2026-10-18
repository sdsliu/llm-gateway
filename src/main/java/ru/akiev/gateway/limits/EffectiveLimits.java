package ru.akiev.gateway.limits;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Действующие лимиты провайдера: по каждому полю — значение и источник.
 * Правило: заголовки &gt; API &gt; конфиг (см. {@link LimitSource}). Если никто не сообщил — значение null
 * (без ограничения), источник null.
 */
public record EffectiveLimits(LimitValue rpm, LimitValue rpd, LimitValue tpm,
        @JsonProperty("tokens_per_day") LimitValue tokensPerDay,
        @JsonProperty("tokens_per_month") LimitValue tokensPerMonth) {

    /** Значение лимита и откуда оно. value == null — лимита нет. */
    public record LimitValue(Long value, LimitSource source) {
        static final LimitValue NONE = new LimitValue(null, null);

        @Override
        public String toString() {
            return value == null ? "—" : value + " (" + source.json() + ")";
        }
    }

    public static final EffectiveLimits NONE = resolve(null, null, null);

    public static EffectiveLimits resolve(DiscoveredLimits config, DiscoveredLimits api, DiscoveredLimits headers) {
        DiscoveredLimits c = config != null ? config : DiscoveredLimits.NONE;
        DiscoveredLimits a = api != null ? api : DiscoveredLimits.NONE;
        DiscoveredLimits h = headers != null ? headers : DiscoveredLimits.NONE;
        return new EffectiveLimits(
                pick(h.rpm(), a.rpm(), c.rpm()),
                pick(h.rpd(), a.rpd(), c.rpd()),
                pick(h.tpm(), a.tpm(), c.tpm()),
                pick(h.tokensPerDay(), a.tokensPerDay(), c.tokensPerDay()),
                pick(h.tokensPerMonth(), a.tokensPerMonth(), c.tokensPerMonth()));
    }

    private static LimitValue pick(Long headers, Long api, Long config) {
        if (headers != null) {
            return new LimitValue(headers, LimitSource.HEADERS);
        }
        if (api != null) {
            return new LimitValue(api, LimitSource.API);
        }
        if (config != null) {
            return new LimitValue(config, LimitSource.CONFIG);
        }
        return LimitValue.NONE;
    }

    /** Для логов: «rpm=30 (config), rpd=1000 (headers), tpm=8000 (headers), tokensPerDay=—, tokensPerMonth=—». */
    @Override
    public String toString() {
        return "rpm=" + rpm + ", rpd=" + rpd + ", tpm=" + tpm + ", tokensPerDay=" + tokensPerDay
                + ", tokensPerMonth=" + tokensPerMonth;
    }
}
