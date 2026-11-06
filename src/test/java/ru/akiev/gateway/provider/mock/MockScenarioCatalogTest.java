package ru.akiev.gateway.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.akiev.gateway.config.MockProperties;
import ru.akiev.gateway.provider.ProviderException;

/** Выбор сценария по run_id и перекрытие файла каталогом, без подъёма шлюза. */
class MockScenarioCatalogTest {

    static final Set<String> KNOWN = Set.copyOf(MockScenarioCatalog.REQUIRED);

    @Test
    void classpathHasEveryScenario() {
        var catalog = new MockScenarioCatalog(new MockProperties(null, "diff-ok", false));
        assertThat(catalog.ids()).containsAll(MockScenarioCatalog.REQUIRED);
        assertThat(catalog.resolve(null, null).id()).isEqualTo("diff-ok");
        assertThat(catalog.resolve(null, null).content()).contains("<<<<<<< SEARCH", ">>>>>>> REPLACE")
                .doesNotContain("SCENARIO_FROM_DIRECTORY");
    }

    @Test
    void directoryOverridesSameId() throws Exception {
        Path dir = Files.createTempDirectory("mock-scenarios-unit");
        Files.writeString(dir.resolve("diff-ok.json"), """
                {"mode":"completion","content":"SCENARIO_FROM_DIRECTORY","usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """);
        var catalog = new MockScenarioCatalog(new MockProperties(dir.toString(), "diff-ok", false));
        assertThat(catalog.resolve("diff-ok", null).content()).isEqualTo("SCENARIO_FROM_DIRECTORY");
        assertThat(catalog.resolve(null, "still-has-edit-pom").id()).isEqualTo("edit-pom");
    }

    @Test
    void headerWinsAndUnknownIs400() {
        var catalog = new MockScenarioCatalog(new MockProperties(null, "diff-ok", false));
        assertThat(catalog.resolve("empty", "run-diff-ok").id()).isEqualTo("empty");
        assertThatThrownBy(() -> catalog.resolve("no-such", "diff-ok"))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).status()).isEqualTo(400));
    }

    @Test
    void runIdMatchesAWholeSegmentOrSuffix() {
        assertThat(MockScenarioCatalog.matchRunId("diff-ok", KNOWN)).isEqualTo("diff-ok");
        assertThat(MockScenarioCatalog.matchRunId("20261106-101500-diff-broken", KNOWN)).isEqualTo("diff-broken");
        assertThat(MockScenarioCatalog.matchRunId("abc:err-429", KNOWN)).isEqualTo("err-429");
        assertThat(MockScenarioCatalog.matchRunId("foo-edit-test", KNOWN)).isEqualTo("edit-test");
        assertThat(MockScenarioCatalog.matchRunId("edit-test-extra", KNOWN)).isNull();
        assertThat(MockScenarioCatalog.matchRunId("plain-run", KNOWN)).isNull();
        assertThat(MockScenarioCatalog.matchRunId(null, KNOWN)).isNull();
    }
}
