package ru.akiev.gateway.contract;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.Yaml;

/**
 * Чтение контракта src/main/resources/openapi/llm-gateway.yaml в тестах (SnakeYAML уже есть в Spring Boot).
 * Ровно тот файл, из которого генерируется код, — берём его из classpath.
 */
record OpenApiContract(Map<String, Object> root) {

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    static OpenApiContract load() {
        try (InputStream in = OpenApiContract.class.getResourceAsStream("/openapi/llm-gateway.yaml")) {
            if (in == null) {
                throw new IllegalStateException("контракт /openapi/llm-gateway.yaml не найден в classpath");
            }
            return new OpenApiContract(new Yaml().load(in));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Операции в виде «GET /status/routing». */
    @SuppressWarnings("unchecked")
    Set<String> operations() {
        Set<String> ops = new TreeSet<>();
        ((Map<String, Map<String, Object>>) root.get("paths")).forEach((path, item) -> item.keySet().stream()
                .filter(HTTP_METHODS::contains)
                .forEach(m -> ops.add(m.toUpperCase() + " " + path)));
        return ops;
    }

    /** Имена свойств схемы components.schemas.{name} (порядок как в YAML). */
    @SuppressWarnings("unchecked")
    Set<String> schemaProperties(String name) {
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) root.get("components")).get("schemas");
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        if (schema == null) {
            throw new IllegalArgumentException("в контракте нет схемы " + name);
        }
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", new LinkedHashMap<>());
        return new java.util.LinkedHashSet<>(props.keySet());
    }
}
