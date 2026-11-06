package ru.akiev.gateway.provider.mock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import ru.akiev.gateway.config.MockProperties;
import ru.akiev.gateway.dto.Usage;
import ru.akiev.gateway.provider.ProviderException;

/**
 * Сценарии mock-провайдера. Имя — имя файла без {@code .json}.
 * Сначала classpath {@code mock-scenarios/} (в тестах это {@code src/test/resources/mock-scenarios/}),
 * затем каталог {@code gateway.mock.scenarios-dir}: одноимённый файл его перекрывает.
 */
@Component
@Profile({"mock", "test"})
public class MockScenarioCatalog {

    private static final Logger log = LoggerFactory.getLogger(MockScenarioCatalog.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Сценарии, без которых профиль mock не стартует. */
    public static final List<String> REQUIRED = List.of(
            "diff-ok", "diff-broken", "edit-test", "edit-pom", "empty",
            "err-429", "err-503", "err-413", "timeout", "stream-cut", "bad-json");

    private final Map<String, MockScenario> scenarios;
    private final String defaultScenario;

    @Autowired
    public MockScenarioCatalog(MockProperties props) {
        this(props, new PathMatchingResourcePatternResolver());
    }

    MockScenarioCatalog(MockProperties props, ResourcePatternResolver resolver) {
        this.defaultScenario = props.defaultScenario();
        Map<String, MockScenario> loaded = new LinkedHashMap<>();
        loadClasspath(resolver, loaded);
        loadDirectory(props.scenariosDir(), loaded);
        for (String id : REQUIRED) {
            if (!loaded.containsKey(id)) {
                throw new IllegalStateException("Нет сценария '" + id
                        + "' (classpath mock-scenarios/ или gateway.mock.scenarios-dir)");
            }
        }
        if (!loaded.containsKey(defaultScenario)) {
            throw new IllegalStateException("gateway.mock.default-scenario=" + defaultScenario + " не найден");
        }
        this.scenarios = Map.copyOf(loaded);
        log.info("Mock-сценарии: {} (по умолчанию {})", String.join(", ", new TreeSet<>(scenarios.keySet())),
                defaultScenario);
    }

    public Set<String> ids() {
        return scenarios.keySet();
    }

    /**
     * Заголовок {@code x-mock-scenario} важнее {@code run_id}. Иначе ищем известный id
     * как отдельный кусок {@code run_id} ({@code diff-ok}, {@code 20261106-101500-diff-ok},
     * {@code abc:err-429}). Ничего не совпало — сценарий по умолчанию.
     */
    public MockScenario resolve(String header, String runId) {
        if (header != null && !header.isBlank()) {
            String id = header.trim().toLowerCase(Locale.ROOT);
            MockScenario found = scenarios.get(id);
            if (found == null) {
                throw new ProviderException(400, "Unknown mock scenario '" + id + "'. Known: " + known(), null);
            }
            return found;
        }
        String fromRun = matchRunId(runId, scenarios.keySet());
        if (fromRun != null) {
            return scenarios.get(fromRun);
        }
        return scenarios.get(defaultScenario);
    }

    /**
     * Совпадение: сегмент (режется по {@code : / | ,} и пробелам) равен id или заканчивается на {@code -id}.
     * Из нескольких берётся самый длинный id, чтобы {@code edit-test} не проигрывал более короткому имени.
     */
    public static String matchRunId(String runId, Collection<String> known) {
        if (runId == null || runId.isBlank() || known == null || known.isEmpty()) {
            return null;
        }
        String best = null;
        for (String part : runId.split("[:/|,\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            for (String id : known) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                if (part.equals(id) || part.endsWith("-" + id)) {
                    if (best == null || id.length() > best.length()) {
                        best = id;
                    }
                }
            }
        }
        return best;
    }

    private String known() {
        return String.join(", ", new TreeSet<>(scenarios.keySet()));
    }

    private static void loadClasspath(ResourcePatternResolver resolver, Map<String, MockScenario> into) {
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath*:mock-scenarios/*.json");
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать classpath:mock-scenarios/", e);
        }
        for (Resource resource : resources) {
            String name = resource.getFilename();
            if (name == null || !name.endsWith(".json")) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                put(into, name, in.readAllBytes(), "classpath:" + name);
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось прочитать " + name, e);
            }
        }
    }

    private static void loadDirectory(String dir, Map<String, MockScenario> into) {
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path path = Path.of(dir);
        if (!Files.isDirectory(path)) {
            log.warn("gateway.mock.scenarios-dir {} не каталог — оставляю сценарии с classpath", dir);
            return;
        }
        try (var files = Files.list(path)) {
            for (Path file : files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".json") && !n.startsWith(".");
            }).sorted().toList()) {
                put(into, file.getFileName().toString(), Files.readAllBytes(file), file.toString());
                log.info("Сценарий {} из {}", idOf(file.getFileName().toString()), file);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать каталог сценариев " + dir, e);
        }
    }

    private static void put(Map<String, MockScenario> into, String filename, byte[] bytes, String source) {
        String id = idOf(filename);
        if (!id.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalStateException("Имя сценария '" + id + "' (" + source
                    + ") должно быть вида diff-ok");
        }
        try {
            FileDto dto = JSON.readValue(bytes, FileDto.class);
            into.put(id, toScenario(id, dto, source));
        } catch (IOException e) {
            throw new IllegalStateException("Сценарий " + id + " (" + source + ") не JSON: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Сценарий " + id + " (" + source + "): " + e.getMessage(), e);
        }
    }

    private static String idOf(String filename) {
        return filename.substring(0, filename.length() - ".json".length()).toLowerCase(Locale.ROOT);
    }

    private static MockScenario toScenario(String id, FileDto dto, String source) {
        String mode = dto.mode == null ? "" : dto.mode.trim().toLowerCase(Locale.ROOT);
        String content = dto.content == null ? "" : dto.content;
        String message = dto.message == null || dto.message.isBlank() ? "mock scenario " + id : dto.message;
        Duration retry = dto.retryAfterSeconds != null && dto.retryAfterSeconds > 0
                ? Duration.ofSeconds(dto.retryAfterSeconds) : null;
        return switch (mode) {
            case "completion" -> new MockScenario(id, MockScenario.Mode.COMPLETION, content, content, null, 200,
                    null, message, requireUsage(dto.usage, id));
            case "error" -> {
                if (dto.status == null) {
                    throw new IllegalArgumentException("у error нет status");
                }
                yield new MockScenario(id, MockScenario.Mode.ERROR, "", "", null, dto.status, retry, message, null);
            }
            case "timeout" -> new MockScenario(id, MockScenario.Mode.TIMEOUT, "", "", null, 504, null, message, null);
            case "stream-cut" -> {
                String first = dto.firstChunk == null || dto.firstChunk.isBlank() ? firstLine(content) : dto.firstChunk;
                yield new MockScenario(id, MockScenario.Mode.STREAM_CUT, content, first, null, 200, null, message,
                        requireUsage(dto.usage, id));
            }
            case "raw" -> {
                if (dto.body == null || dto.body.isBlank()) {
                    throw new IllegalArgumentException("у raw нет body");
                }
                if (dto.body.indexOf('\n') >= 0 || dto.body.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("body сценария raw должен быть одной строкой (это SSE/HTTP-тело)");
                }
                int status = dto.status == null ? 200 : dto.status;
                yield new MockScenario(id, MockScenario.Mode.RAW, "", "", dto.body, status, null, message,
                        requireUsage(dto.usage, id));
            }
            default -> throw new IllegalArgumentException("неизвестный mode '" + dto.mode + "' в " + source);
        };
    }

    private static Usage requireUsage(Usage usage, String id) {
        if (usage == null || usage.totalTokens() == null) {
            throw new IllegalArgumentException("сценарию " + id + " нужен usage.total_tokens");
        }
        return usage;
    }

    private static String firstLine(String content) {
        int n = content.indexOf('\n');
        return n < 0 ? content : content.substring(0, n);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FileDto {
        public String mode;
        public String content;
        public String firstChunk;
        public String body;
        public Integer status;
        public Integer retryAfterSeconds;
        public String message;
        public Usage usage;
    }
}
