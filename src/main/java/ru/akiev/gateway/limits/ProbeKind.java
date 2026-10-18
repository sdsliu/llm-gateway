package ru.akiev.gateway.limits;

import java.util.Locale;
import ru.akiev.gateway.config.ProviderProperties;

/**
 * Как узнать лимиты провайдера (gateway.providers[].limits-probe). При {@code auto} — по base-url.
 */
public enum ProbeKind {
    /** GET /models (проверка ключа) + крошечный chat (max_tokens=1): лимиты в x-ratelimit-* заголовках ответа. */
    GROQ,
    /** GET {base-url}/key: rate_limit, is_free_tier, кредиты. */
    OPENROUTER,
    /** Gonka Broker: GET /test-auth → maxRpm. */
    GONKA,
    /** Обычный ping GET {ping-path}; лимиты — если провайдер присылает x-ratelimit-limit-* в этом ответе. */
    MODELS,
    /** Не опрашивать: провайдер всегда в роутинге, лимиты только из конфига. */
    NONE;

    public static ProbeKind of(ProviderProperties p) {
        String v = p.limitsProbe() == null ? "auto" : p.limitsProbe();
        if (!"auto".equals(v)) {
            return valueOf(v.toUpperCase(Locale.ROOT));
        }
        String url = p.baseUrl() == null ? "" : p.baseUrl().toLowerCase(Locale.ROOT);
        if (url.contains("groq.com")) {
            return GROQ;
        }
        if (url.contains("openrouter.ai")) {
            return OPENROUTER;
        }
        if (url.contains("gonka")) {
            return GONKA;
        }
        return MODELS;
    }

    /**
     * У Groq {@code x-ratelimit-limit-requests} — это запросы в СУТКИ (RPD), а у OpenAI и большинства
     * совместимых — в минуту. Поэтому смысл заголовка зависит от провайдера.
     */
    public boolean requestsHeaderIsPerDay() {
        return this == GROQ;
    }
}
