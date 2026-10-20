package ru.akiev.gateway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Choice;
import ru.akiev.gateway.dto.ErrorResponse;
import ru.akiev.gateway.dto.Message;
import ru.akiev.gateway.dto.Usage;
import ru.akiev.gateway.stats.EfficiencyMerger;
import ru.akiev.gateway.stats.RunStats;
import ru.akiev.gateway.status.ProviderHealthService;
import ru.akiev.gateway.status.RoutingInfo;
import ru.akiev.gateway.status.StatusSnapshot;

/**
 * Страж «контракт ↔ Java-record'ы».
 *
 * <p>Часть схем контракта не генерируется, а сопоставлена существующим record'ам (schemaMappings в pom.xml):
 * они используются внутри шлюза (роутер, SSE, статистика) и уже дают нужный JSON. Компилятор такие пары
 * не сверяет, поэтому здесь для каждой пары «схема ↔ класс» сравниваются имена JSON-свойств, которые
 * Jackson реально будет писать (с учётом {@code @JsonProperty}), с {@code properties} схемы в YAML.
 * Добавили поле в record и забыли контракт (или наоборот) — тест падает и показывает разницу.
 */
class ContractSchemaTest {

    private static final OpenApiContract CONTRACT = OpenApiContract.load();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    static Stream<Arguments> mappedSchemas() {
        return Stream.of(
                Arguments.of("StatusSnapshot", StatusSnapshot.class),
                Arguments.of("ProviderStatus", StatusSnapshot.ProviderStatus.class),
                Arguments.of("KeyInfo", StatusSnapshot.KeyInfo.class),
                Arguments.of("Totals", StatusSnapshot.Totals.class),
                Arguments.of("HourBucket", StatusSnapshot.HourBucket.class),
                Arguments.of("RecentRequest", StatusSnapshot.RecentRequest.class),
                Arguments.of("LimitUsage", StatusSnapshot.LimitUsage.class),
                Arguments.of("LimitsView", StatusSnapshot.LimitsView.class),
                Arguments.of("ProbeView", StatusSnapshot.ProbeView.class),
                Arguments.of("RoutingInfo", RoutingInfo.class),
                Arguments.of("ProviderOption", RoutingInfo.ProviderOption.class),
                Arguments.of("PingResult", ProviderHealthService.PingResult.class),
                Arguments.of("ModelsCache", ProviderHealthService.ModelsCache.class),
                Arguments.of("RunStats", RunStats.class),
                Arguments.of("CostEstimate", RunStats.CostEstimate.class),
                Arguments.of("EfficiencyReport", EfficiencyMerger.Report.class),
                Arguments.of("EfficiencyRow", EfficiencyMerger.Row.class),
                Arguments.of("EfficiencySummary", EfficiencyMerger.Summary.class),
                Arguments.of("ChatResponse", ChatResponse.class),
                Arguments.of("Choice", Choice.class),
                Arguments.of("Message", Message.class),
                Arguments.of("Usage", Usage.class),
                Arguments.of("ErrorResponse", ErrorResponse.class));
    }

    @ParameterizedTest(name = "{0} ↔ {1}")
    @MethodSource("mappedSchemas")
    void recordJsonPropertiesMatchContractSchema(String schema, Class<?> type) {
        JavaType javaType = MAPPER.constructType(type);
        Set<String> json = new LinkedHashSet<>();
        for (BeanPropertyDefinition p : MAPPER.getSerializationConfig().introspect(javaType).findProperties()) {
            if (p.couldSerialize()) {
                json.add(p.getName());
            }
        }
        assertThat(json).as("JSON-свойства %s vs properties схемы %s в llm-gateway.yaml", type.getSimpleName(), schema)
                .containsExactlyInAnyOrderElementsOf(CONTRACT.schemaProperties(schema));
    }
}
