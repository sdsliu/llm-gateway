package ru.akiev.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.OpenAiCompatibleProvider;

@Configuration
public class ProviderConfig {

    /**
     * WebClient.Builder Spring Boot создаёт сам (с уже настроенным Jackson-кодеком,
     * т.е. наши snake_case-аннотации и non_null будут учтены). Мы лишь передаём его
     * в провайдер, а тот доконфигурирует baseUrl и заголовки.
     */
    @Bean
    public LlmProvider llmProvider(WebClient.Builder builder, ProviderProperties props) {
        return new OpenAiCompatibleProvider(builder, props);
    }
}
