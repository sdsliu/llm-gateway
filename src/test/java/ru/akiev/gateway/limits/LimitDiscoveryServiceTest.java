package ru.akiev.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.config.GatewayProperties;
import ru.akiev.gateway.config.LimitsProperties;
import ru.akiev.gateway.config.ProviderProperties;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.OpenAiCompatibleProvider;
import ru.akiev.gateway.provider.RateLimitHeaders;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.usage.UsageRecordRepository;

/** Опрос провайдеров: исключение при старте, перепинг и возврат, разбор лимитов Groq / OpenRouter / Gonka. */
class LimitDiscoveryServiceTest {

    MockWebServer good;
    MockWebServer broken;
    MockWebServer silent;

    @BeforeEach
    void setUp() throws Exception {
        good = new MockWebServer();
        broken = new MockWebServer();
        silent = new MockWebServer();
        good.start();
        broken.start();
        silent.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        good.shutdown();
        broken.shutdown();
        silent.shutdown();
    }

    static ProviderProperties props(String name, MockWebServer s, int priority, String probe, String model) {
        return new ProviderProperties(name, s.url("/v1").toString(), "k-" + name, model, priority,
                Duration.ofSeconds(5), new ProviderProperties.Limits(30, 1000, 8000, null, null), false, "/models",
                null, null, false, null, probe, null);
    }

    static LimitsProperties limits() {
        return new LimitsProperties(true, Duration.ofMillis(700), Duration.ofMinutes(1), Duration.ofMinutes(30),
                Duration.ZERO, 1024, false, Duration.ofHours(12));
    }

    LimitDiscoveryService service(List<ProviderProperties> list) {
        List<LlmProvider> providers = list.stream()
                .map(p -> (LlmProvider) new OpenAiCompatibleProvider(WebClient.builder(), p)).toList();
        ProviderRouter router = new ProviderRouter(providers, Duration.ofMinutes(5), Clock.systemUTC());
        UsageRecordRepository repo = mock(UsageRecordRepository.class); // пустые списки — счётчики с нуля
        return new LimitDiscoveryService(router, new GatewayProperties(list, Duration.ofMinutes(5)), limits(),
                WebClient.builder(), repo, Clock.systemUTC());
    }

    static LlmProvider byName(LimitDiscoveryService s, String name) throws Exception {
        var f = LimitDiscoveryService.class.getDeclaredField("router");
        f.setAccessible(true);
        return ((ProviderRouter) f.get(s)).providers().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void startupExcludesSilentAndBrokenProvidersAndGroqLimitsComeFromHeaders() throws Exception {
        // groq-подобный: GET /models, потом крошечный chat с заголовками лимитов
        good.enqueue(new MockResponse().setBody("{\"data\":[]}"));
        good.enqueue(new MockResponse().setBody("{\"choices\":[]}")
                .addHeader("x-ratelimit-limit-requests", "1000")
                .addHeader("x-ratelimit-limit-tokens", "8000")
                .addHeader("x-ratelimit-remaining-tokens", "7990")
                .addHeader("x-ratelimit-reset-tokens", "75ms"));
        broken.enqueue(new MockResponse().setResponseCode(500));
        silent.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        var s = service(List.of(props("groq", good, 1, "groq", "openai/gpt-oss-120b"),
                props("down", broken, 2, "models", "m"), props("silent", silent, 3, "models", "m")));
        long start = System.nanoTime();
        s.afterSingletonsInstantiated();
        // таймаут на провайдера 700 мс, опрос параллельный — старт не висит
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5000);

        LlmProvider groq = byName(s, "groq");
        assertThat(groq.state().excluded()).isFalse();
        EffectiveLimits e = groq.state().effectiveLimits();
        // у Groq limit-requests — запросы в сутки, limit-tokens — TPM
        assertThat(e.rpd()).isEqualTo(new EffectiveLimits.LimitValue(1000L, LimitSource.HEADERS));
        assertThat(e.tpm()).isEqualTo(new EffectiveLimits.LimitValue(8000L, LimitSource.HEADERS));
        assertThat(e.rpm()).isEqualTo(new EffectiveLimits.LimitValue(30L, LimitSource.CONFIG));
        assertThat(good.takeRequest().getPath()).isEqualTo("/v1/models");
        RecordedRequest chat = good.takeRequest();
        assertThat(chat.getPath()).isEqualTo("/v1/chat/completions");
        Map<?, ?> body = new ObjectMapper().readValue(chat.getBody().readUtf8(), Map.class);
        assertThat(body.get("max_tokens")).isEqualTo(1);

        assertThat(byName(s, "down").state().excluded()).isTrue();
        assertThat(byName(s, "down").state().excludedReason()).contains("500");
        assertThat(byName(s, "silent").state().excluded()).isTrue();
        assertThat(byName(s, "silent").state().excludedReason()).contains("нет ответа");
        assertThat(s.lastProbe("silent")).get().extracting(ProbeResult::ok).isEqualTo(false);
    }

    @Test
    void excludedProviderReturnsAfterSuccessfulReprobe() throws Exception {
        broken.enqueue(new MockResponse().setResponseCode(503));
        var s = service(List.of(props("p", broken, 1, "models", "m")));
        s.afterSingletonsInstantiated();
        assertThat(byName(s, "p").state().excluded()).isTrue();

        broken.enqueue(new MockResponse().setResponseCode(503));
        s.reprobe();
        assertThat(byName(s, "p").state().excluded()).isTrue();

        broken.enqueue(new MockResponse().setBody("{\"data\":[]}").addHeader("x-ratelimit-limit-requests", "20")
                .addHeader("x-ratelimit-limit-requests-day", "20"));
        s.reprobe();
        LlmProvider p = byName(s, "p");
        assertThat(p.state().excluded()).isFalse();
        assertThat(p.state().isAvailable(java.time.Instant.now())).isTrue();
        assertThat(p.state().effectiveLimits().rpm()).isEqualTo(new EffectiveLimits.LimitValue(20L, LimitSource.HEADERS));
    }

    @Test
    void providerPausedBy429IsReincludedByFirstSuccessfulPing() throws Exception {
        good.enqueue(new MockResponse().setBody("{}"));
        var s = service(List.of(props("p", good, 1, "models", "m")));
        s.afterSingletonsInstantiated();
        LlmProvider p = byName(s, "p");
        java.time.Instant now = java.time.Instant.now();
        // пауза от 429 началась минуту назад и по Retry-After продлилась бы ещё час
        p.state().pauseAfter429(now.minusSeconds(60), now.plusSeconds(3600), "HTTP 429");
        assertThat(p.state().isAvailable(now)).isFalse();
        good.enqueue(new MockResponse().setResponseCode(429));
        s.reprobe();
        assertThat(p.state().excluded()).isTrue(); // ping тоже 429 — исключён до следующего перепинга
        good.enqueue(new MockResponse().setBody("{}"));
        s.reprobe();
        assertThat(p.state().excluded()).isFalse();
        assertThat(p.state().isAvailable(java.time.Instant.now())).isTrue();
    }

    @Test
    void startupProbeCanBeDisabled() throws Exception {
        var list = List.of(props("p", silent, 1, "models", "m"));
        List<LlmProvider> providers = list.stream()
                .map(p -> (LlmProvider) new OpenAiCompatibleProvider(WebClient.builder(), p)).toList();
        var router = new ProviderRouter(providers, Duration.ofMinutes(5), Clock.systemUTC());
        var off = new LimitsProperties(false, Duration.ofMillis(100), Duration.ofMinutes(1), Duration.ofMinutes(30),
                Duration.ZERO, 1024, false, Duration.ofHours(12));
        var s = new LimitDiscoveryService(router, new GatewayProperties(list, Duration.ofMinutes(5)), off,
                WebClient.builder(), mock(UsageRecordRepository.class), Clock.systemUTC());
        s.afterSingletonsInstantiated();
        s.reprobe();
        assertThat(silent.getRequestCount()).isZero();
        assertThat(providers.get(0).state().excluded()).isFalse();
    }

    @Test
    void openRouterKeyAndGonkaParsers() throws Exception {
        var om = new ObjectMapper();
        var key = om.readTree("{\"data\":{\"limit\":null,\"is_free_tier\":true,"
                + "\"rate_limit\":{\"requests\":10,\"interval\":\"10s\"}}}");
        assertThat(ProviderProber.parseOpenRouterKey(key, "x/y:free")).isEqualTo(new DiscoveredLimits(60L, 50L, null, null, null));
        var paid = om.readTree("{\"data\":{\"is_free_tier\":false,\"rate_limit\":{\"requests\":-1,\"interval\":\"10s\"}}}");
        assertThat(ProviderProber.parseOpenRouterKey(paid, "x/y:free")).isEqualTo(new DiscoveredLimits(null, 1000L, null, null, null));
        assertThat(ProviderProber.parseOpenRouterKey(paid, "x/paid")).isEqualTo(DiscoveredLimits.NONE);

        var gonka = om.readTree("{\"ok\":true,\"user\":{\"plan\":{\"maxRpm\":6,\"tokens_per_month\":1000000}}}");
        assertThat(ProviderProber.parseGonka(gonka)).isEqualTo(new DiscoveredLimits(6L, null, null, null, 1_000_000L));
    }

    @Test
    void openRouterProbeCallsKeyEndpoint() throws Exception {
        good.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"is_free_tier\":true}}"));
        var s = service(List.of(props("openrouter", good, 1, "openrouter", "a/b:free")));
        s.afterSingletonsInstantiated();
        assertThat(good.takeRequest().getPath()).isEqualTo("/v1/key");
        var e = byName(s, "openrouter").state().effectiveLimits();
        assertThat(e.rpd()).isEqualTo(new EffectiveLimits.LimitValue(50L, LimitSource.API));
    }

    @Test
    void headerLimitParsing() {
        HttpHeaders groq = new HttpHeaders();
        groq.add("x-ratelimit-limit-requests", "1000");
        groq.add("x-ratelimit-limit-tokens", "8000");
        assertThat(RateLimitHeaders.limits(groq, true)).isEqualTo(new DiscoveredLimits(null, 1000L, 8000L, null, null));
        assertThat(RateLimitHeaders.limits(groq, false)).isEqualTo(new DiscoveredLimits(1000L, null, 8000L, null, null));

        HttpHeaders samba = new HttpHeaders();
        samba.add("x-ratelimit-limit-requests", "20");
        samba.add("x-ratelimit-limit-requests-day", "20");
        assertThat(RateLimitHeaders.limits(samba, false)).isEqualTo(new DiscoveredLimits(20L, 20L, null, null, null));

        HttpHeaders ietf = new HttpHeaders();
        ietf.add("ratelimit-limit", "2");
        ietf.add("ratelimit-policy", "2;w=60");
        assertThat(RateLimitHeaders.limits(ietf, false).rpm()).isEqualTo(2L);
        assertThat(RateLimitHeaders.limits(new HttpHeaders(), false).any()).isFalse();
    }

    @Test
    void probeKindIsDetectedFromBaseUrl() {
        var groq = new ProviderProperties("g", "https://api.groq.com/openai/v1", "k", "m", 1, null, null, false,
                "/models", null, null);
        var or = new ProviderProperties("o", "https://openrouter.ai/api/v1", "k", "m", 1, null, null, false,
                "/models", null, null);
        var other = new ProviderProperties("x", "https://api.z.ai/api/paas/v4", "k", "m", 1, null, null, false,
                "/models", null, null);
        assertThat(ProbeKind.of(groq)).isEqualTo(ProbeKind.GROQ);
        assertThat(ProbeKind.of(or)).isEqualTo(ProbeKind.OPENROUTER);
        assertThat(ProbeKind.of(other)).isEqualTo(ProbeKind.MODELS);
    }
}
