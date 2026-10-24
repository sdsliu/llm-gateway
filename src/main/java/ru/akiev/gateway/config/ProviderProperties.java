package ru.akiev.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpHeaders;

/**
 * Настройки одного провайдера (элемент списка gateway.providers).
 *
 * @param priority чем МЕНЬШЕ число, тем выше приоритет (1 — пробуем первым)
 * @param limits   лимиты провайдера: rpm, rpd, tpm, tokens-per-day, tokens-per-month. Это FALLBACK: если провайдер
 *                 сам сообщил лимит (API при старте или заголовки x-ratelimit-* в ответах), берётся его значение
 *                 (см. {@code ru.akiev.gateway.limits.EffectiveLimits}). 0 или null = без ограничения
 * @param streamUsage слать ли провайдеру stream_options.include_usage в режиме stream
 *                    (Groq, OpenAI, OpenRouter поддерживают; для «самописных» серверов
 *                    можно выключить, если они отвечают 400 на неизвестное поле)
 * @param pingPath    путь для ping дашборда относительно base-url (по умолчанию /models)
 * @param fallbackModels запасные модели, которые провайдер сам перебирает по порядку, если основная
 *                    недоступна. Отправляется в теле как {@code "models": [...]} — это нативный
 *                    параметр OpenRouter (https://openrouter.ai/docs/guides/routing/model-fallbacks).
 *                    Задавайте только для провайдеров, которые его поддерживают; пусто — не отправляем.
 * @param timeout     таймаут запроса к ЭТОМУ провайдеру (non-stream — весь ответ, stream — пауза до/между
 *                    чанками). Истёк — 504, cooldown и failover на следующего провайдера.
 * @param extraBody   дополнительные поля верхнего уровня в теле /chat/completions, специфичные для
 *                    провайдера (например Z.ai {@code thinking: {type: disabled}}). Поля, которые
 *                    формирует сам шлюз (model, messages, stream, stream_options, models), не переопределяются.
 * @param apiKeyOptional провайдер работает и без ключа (keyless/anonymous режим, например Kilo Gateway или
 *                    OVHcloud AI Endpoints): при пустом api-key он НЕ пропускается, а запросы уходят без
 *                    заголовка Authorization. Ключ, если задан, отправляется как обычно (выше лимиты).
 * @param models      дополнительные модели, которые провайдер обслуживает (кроме {@code model}). Если клиент
 *                    прислал одну из них — уходит она, иначе — {@code model} (как раньше). Роутер выбирает только
 *                    среди провайдеров, у которых запрошенная модель есть (если её нет ни у кого — среди всех),
 *                    либо среди участников группы моделей (см. {@code gateway.model-groups}).
 * @param limitsProbe как узнавать лимиты у провайдера: auto (по base-url), groq (заголовки крошечного запроса),
 *                    openrouter (GET /key), gonka (GET /test-auth → maxRpm), models (GET ping-path, заголовки ответа),
 *                    none (не опрашивать: провайдер всегда в ротации, лимиты из конфига)
 * @param limitsProbeUrl полный URL для опроса лимитов, если он не {base-url}/key или {base-url}/test-auth
 * @param maxConcurrency сколько запросов может быть в полёте одновременно. null или неположительное — без ограничения.
 *                    Сверх лимита провайдер пропускается, всплеск уходит к остальным.
 */
public record ProviderProperties(
        @NotBlank String name,
        @NotBlank String baseUrl,
        String apiKey,
        @NotBlank String model,
        @DefaultValue("100") int priority,
        @DefaultValue("120s") Duration timeout,
        @Valid Limits limits,
        @DefaultValue("true") boolean streamUsage,
        @DefaultValue("/models") String pingPath,
        List<String> fallbackModels,
        Map<String, Object> extraBody,
        @DefaultValue("false") boolean apiKeyOptional,
        List<String> models,
        @DefaultValue("auto") String limitsProbe,
        String limitsProbeUrl,
        Integer maxConcurrency) {

    /** Канонический конструктор — по нему Spring Boot биндит application.yml (их у record теперь два). */
    @ConstructorBinding
    public ProviderProperties {
        fallbackModels = fallbackModels == null ? List.of() : List.copyOf(fallbackModels);
        extraBody = extraBody == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extraBody));
        models = models == null ? List.of() : List.copyOf(models);
        limitsProbe = limitsProbe == null || limitsProbe.isBlank() ? "auto" : limitsProbe.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Без extra-body (для кода и тестов, написанных до его появления). */
    public ProviderProperties(String name, String baseUrl, String apiKey, String model, int priority,
            Duration timeout, Limits limits, boolean streamUsage, String pingPath, List<String> fallbackModels) {
        this(name, baseUrl, apiKey, model, priority, timeout, limits, streamUsage, pingPath, fallbackModels, null);
    }

    /** Без api-key-optional (ключ обязателен). */
    public ProviderProperties(String name, String baseUrl, String apiKey, String model, int priority,
            Duration timeout, Limits limits, boolean streamUsage, String pingPath, List<String> fallbackModels,
            Map<String, Object> extraBody) {
        this(name, baseUrl, apiKey, model, priority, timeout, limits, streamUsage, pingPath, fallbackModels,
                extraBody, false);
    }

    /** Без models / limits-probe (код и тесты до #34). */
    public ProviderProperties(String name, String baseUrl, String apiKey, String model, int priority,
            Duration timeout, Limits limits, boolean streamUsage, String pingPath, List<String> fallbackModels,
            Map<String, Object> extraBody, boolean apiKeyOptional) {
        this(name, baseUrl, apiKey, model, priority, timeout, limits, streamUsage, pingPath, fallbackModels,
                extraBody, apiKeyOptional, null, "auto", null, null);
    }

    /** Без max-concurrency (код и тесты до распределения in-flight). */
    public ProviderProperties(String name, String baseUrl, String apiKey, String model, int priority,
            Duration timeout, Limits limits, boolean streamUsage, String pingPath, List<String> fallbackModels,
            Map<String, Object> extraBody, boolean apiKeyOptional, List<String> models, String limitsProbe,
            String limitsProbeUrl) {
        this(name, baseUrl, apiKey, model, priority, timeout, limits, streamUsage, pingPath, fallbackModels,
                extraBody, apiKeyOptional, models, limitsProbe, limitsProbeUrl, null);
    }

    /**
     * Лимиты из конфига (fallback). Все поля необязательны.
     *
     * @param rpm            запросов в минуту
     * @param rpd            запросов в сутки
     * @param tpm            токенов в минуту (вход + выход)
     * @param tokensPerDay   токенов в сутки (сутки — по UTC)
     * @param tokensPerMonth токенов в месяц (календарный месяц по UTC)
     */
    public record Limits(Integer rpm, Integer rpd, Integer tpm, Long tokensPerDay, Long tokensPerMonth) {

        @ConstructorBinding
        public Limits {
        }

        /** Только запросы (как до #34). */
        public Limits(Integer rpm, Integer rpd) {
            this(rpm, rpd, null, null, null);
        }
    }

    /** Основная модель + дополнительные из {@code models}, без повторов. */
    public List<String> allModels() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        if (model != null && !model.isBlank()) {
            all.add(model);
        }
        all.addAll(models);
        return List.copyOf(all);
    }

    /** Задан ли ключ. Только тогда шлём заголовок Authorization: Bearer. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Включён ли провайдер: есть ключ или ключ необязателен (api-key-optional: true).
     * Провайдер без ключа и без api-key-optional пропускаем (например, ключ Z.ai не задан в окружении).
     */
    public boolean enabled() {
        return hasApiKey() || apiKeyOptional;
    }

    /** Добавляет Authorization: Bearer, только если ключ задан (keyless-провайдерам заголовок не шлём). */
    public void applyAuth(HttpHeaders headers) {
        if (hasApiKey()) {
            headers.setBearerAuth(apiKey.trim());
        }
    }

    /**
     * Почему провайдера нельзя включать. {@code null} — конфиг годный.
     * Пустой ключ (и он обязателен) или битый {@code base-url}: пустой сегмент пути
     * (не задан {@code CLOUDFLARE_ACCOUNT_ID} → {@code /accounts//ai/v1}), нет схемы/хоста,
     * нераскрытый placeholder.
     */
    public String disabledReason() {
        if (!enabled()) {
            return "api-key не задан";
        }
        String url = baseUrlProblem(baseUrl);
        if (url != null) {
            return url;
        }
        if (model == null || model.isBlank()) {
            return "model пустой";
        }
        return null;
    }

    /** Можно создать клиента и слать запросы: есть ключ (или он необязателен) и base-url разбирается. */
    public boolean usable() {
        return disabledReason() == null;
    }

    /**
     * Копия с дополнительными id моделей (участник группы шлёт свой model id, а не model по умолчанию).
     * Уже известные id не дублируются.
     */
    public ProviderProperties withExtraModels(List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(models);
        for (String m : extra) {
            if (m != null && !m.isBlank() && !m.equals(model)) {
                merged.add(m);
            }
        }
        if (merged.size() == models.size()) {
            return this;
        }
        return new ProviderProperties(name, baseUrl, apiKey, model, priority, timeout, limits, streamUsage, pingPath,
                fallbackModels, extraBody, apiKeyOptional, List.copyOf(merged), limitsProbe, limitsProbeUrl,
                maxConcurrency);
    }

    static String baseUrlProblem(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "base-url пустой";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.contains("${")) {
            return "base-url содержит нераскрытый placeholder: " + trimmed;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return "base-url не является корректным URL: " + trimmed;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "base-url без схемы http(s): " + trimmed;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "base-url без хоста: " + trimmed;
        }
        String path = uri.getRawPath();
        if (path != null && path.contains("//")) {
            return "base-url с пустым сегментом пути (не задана переменная, например account id): " + trimmed;
        }
        return null;
    }
}
