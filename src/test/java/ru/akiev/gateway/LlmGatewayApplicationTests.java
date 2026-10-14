package ru.akiev.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "gateway.provider.api-key=test")
class LlmGatewayApplicationTests {
    @Test
    void contextLoads() {
    }
}
