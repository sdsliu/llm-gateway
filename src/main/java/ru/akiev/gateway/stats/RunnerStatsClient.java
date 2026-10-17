package ru.akiev.gateway.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Читает итоги прогонов ai-runner: {@code GET {runner-stats-url}} (по умолчанию http://localhost:8085/stats).
 * Необязательная зависимость: если runner не запущен, страница «Эффективность» показывает только данные шлюза.
 * Направление одно — шлюз → runner; runner о шлюзе для этого ничего не знает.
 */
public class RunnerStatsClient {

    /** Ответ: массив runs или текст ошибки. */
    public record Result(JsonNode runs, String error) {
    }

    private final String url;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public RunnerStatsClient(String url, ObjectMapper mapper) {
        this.url = url;
        this.mapper = mapper;
    }

    public Result fetch() {
        if (url == null || url.isBlank()) {
            return new Result(null, "gateway.efficiency.runner-stats-url не задан");
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                return new Result(null, "ai-runner ответил HTTP " + r.statusCode());
            }
            JsonNode runs = mapper.readTree(r.body()).path("runs");
            return runs.isArray() ? new Result(runs, null) : new Result(null, "в ответе ai-runner нет массива runs");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, "прервано");
        } catch (Exception e) {
            return new Result(null, "ai-runner недоступен (" + url + "): " + e.getClass().getSimpleName());
        }
    }
}
