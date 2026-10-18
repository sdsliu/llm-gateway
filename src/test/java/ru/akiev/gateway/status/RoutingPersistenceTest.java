package ru.akiev.gateway.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import ru.akiev.gateway.LlmGatewayApplication;
import ru.akiev.gateway.router.ProviderRouter;
import ru.akiev.gateway.router.RoutingMode;
import ru.akiev.gateway.router.RoutingSettings;

/**
 * Режим роутинга переживает рестарт: каждый «запуск» — отдельный Spring-контекст приложения
 * поверх одной и той же БД (H2 in-memory с DB_CLOSE_DELAY=-1 живёт, пока жива JVM — как Postgres
 * между рестартами шлюза). Своё имя БД — чтобы не пересекаться с остальными тестами.
 */
@ExtendWith(OutputCaptureExtension.class)
class RoutingPersistenceTest {

    final String dbUrl = "jdbc:h2:mem:routing-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** Запуск шлюза: alpha, beta, gamma с ключами; extra переопределяет свойства (например, убрать ключ). */
    ConfigurableApplicationContext start(String... extra) {
        List<String> props = new ArrayList<>(List.of(
                "server.port=0",
                "spring.datasource.url=" + dbUrl,
                "gateway.routing.mode=priority",
                "gateway.routing.primary=beta",
                "gateway.providers[0].name=alpha", "gateway.providers[0].base-url=http://localhost:1/v1",
                "gateway.providers[0].api-key=k", "gateway.providers[0].model=m", "gateway.providers[0].priority=1",
                "gateway.providers[1].name=beta", "gateway.providers[1].base-url=http://localhost:1/v1",
                "gateway.providers[1].api-key=k", "gateway.providers[1].model=m", "gateway.providers[1].priority=2",
                "gateway.providers[2].name=gamma", "gateway.providers[2].base-url=http://localhost:1/v1",
                "gateway.providers[2].api-key=k", "gateway.providers[2].model=m", "gateway.providers[2].priority=3"));
        // extra переопределяет одноимённые ключи (дубли в командной строке не годятся)
        var keys = List.of(extra).stream().map(e -> e.substring(0, e.indexOf('=') + 1)).toList();
        props.removeIf(p -> keys.stream().anyMatch(p::startsWith));
        props.addAll(List.of(extra));
        // Аргументы командной строки (а не builder.properties — те ниже application.yml по приоритету),
        // поэтому список gateway.providers целиком заменяет список из application.yml.
        return new SpringApplicationBuilder(LlmGatewayApplication.class)
                .run(props.stream().map(p -> "--" + p).toArray(String[]::new));
    }

    static List<String> order(ConfigurableApplicationContext ctx) {
        return ctx.getBean(StatusService.class).routing().order();
    }

    @Test
    void savedSettingsSurviveRestart() {
        try (var ctx = start()) {
            // пусто в БД — конфиг (primary=beta)
            assertThat(ctx.getBean(ProviderRouter.class).routing())
                    .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "beta"));
            assertThat(ctx.getBean(RoutingSettingsService.class).source()).isEqualTo(RoutingSettingsService.Source.CONFIG);
            ctx.getBean(RoutingSettingsService.class).update(new RoutingSettings(RoutingMode.PRIORITY, "gamma"));
        }
        try (var ctx = start()) {
            assertThat(ctx.getBean(ProviderRouter.class).routing())
                    .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "gamma"));
            assertThat(ctx.getBean(RoutingSettingsService.class).source()).isEqualTo(RoutingSettingsService.Source.DB);
            assertThat(order(ctx)).containsExactly("gamma", "alpha", "beta");
            ctx.getBean(RoutingSettingsService.class).update(new RoutingSettings(RoutingMode.ROUND_ROBIN, "gamma"));
        }
        try (var ctx = start()) {
            assertThat(ctx.getBean(ProviderRouter.class).routing())
                    .isEqualTo(new RoutingSettings(RoutingMode.ROUND_ROBIN, "gamma"));
            // DELETE → удалили из БД; следующий старт — снова конфиг
            ctx.getBean(RoutingSettingsService.class).reset();
            assertThat(ctx.getBean(ProviderRouter.class).routing())
                    .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "beta"));
        }
        try (var ctx = start()) {
            assertThat(ctx.getBean(ProviderRouter.class).routing())
                    .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "beta"));
            assertThat(ctx.getBean(RoutingSettingsRepository.class).count()).isZero();
        }
    }

    @Test
    void savedPrimaryWithoutKeyFallsBackToConfigOrderWithWarning(CapturedOutput output) {
        try (var ctx = start()) {
            ctx.getBean(RoutingSettingsService.class).update(new RoutingSettings(RoutingMode.PRIORITY, "gamma"));
        }
        // у gamma пропал ключ
        try (var ctx = start("gateway.providers[2].api-key=")) {
            assertThat(ctx.getBean(ProviderRouter.class).routing())
                    .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "beta"));
            assertThat(order(ctx)).containsExactly("beta", "alpha");
            var info = ctx.getBean(StatusService.class).routing();
            assertThat(info.warning()).contains("gamma");
            assertThat(info.options()).filteredOn(o -> o.name().equals("gamma")).singleElement()
                    .satisfies(o -> assertThat(o.selectable()).isFalse())
                    .satisfies(o -> assertThat(o.state()).isEqualTo("no_key"));
            // строку в БД не трогаем: вернётся ключ — вернётся и выбор
            assertThat(ctx.getBean(RoutingSettingsRepository.class).findAll()).singleElement()
                    .satisfies(r -> assertThat(r.getPrimaryProvider()).isEqualTo("gamma"));
        }
        assertThat(output).contains("WARN").contains("Сохранённый primary=gamma недоступен");
        try (var ctx = start()) {
            assertThat(ctx.getBean(ProviderRouter.class).routing())
                    .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "gamma"));
        }
    }

    @Test
    void savedPrimaryRemovedFromConfigFallsBackToConfig(CapturedOutput output) {
        try (var ctx = start()) {
            ctx.getBean(RoutingSettingsService.class).update(new RoutingSettings(RoutingMode.PRIORITY, "gamma"));
        }
        // gamma вообще убрали из конфига (переименовали в delta)
        try (var ctx = start("gateway.providers[2].name=delta")) {
            assertThat(ctx.getBean(ProviderRouter.class).routing())
                    .isEqualTo(new RoutingSettings(RoutingMode.PRIORITY, "beta"));
        }
        assertThat(output).contains("Сохранённый primary=gamma недоступен");
    }
}
