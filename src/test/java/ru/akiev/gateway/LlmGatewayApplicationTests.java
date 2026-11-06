package ru.akiev.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.akiev.gateway.provider.mock.MockProvider;
import ru.akiev.gateway.router.ProviderRouter;

@SpringBootTest
class LlmGatewayApplicationTests {

    @Autowired
    ProviderRouter router;

    @Test
    void contextLoads() {
    }

    /** Профиль по умолчанию: application-mock.yml не читается, type=mock в роутер не попадает. */
    @Test
    void mockProviderIsAbsentWithoutMockProfile() {
        assertThat(router.names()).doesNotContain("mock");
        assertThat(router.providers()).noneMatch(p -> p instanceof MockProvider);
    }
}
