package ru.akiev.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Раздаёт OpenAPI-контракт как есть: {@code GET /openapi/llm-gateway.yaml}.
 *
 * <p>Файл лежит в {@code src/main/resources/openapi/} (а не в {@code static/}), потому что это исходник сборки —
 * из него openapi-generator генерирует интерфейсы. Чтобы не держать копию, отдаём тот же файл из classpath.
 * Swagger UI ({@code /swagger-ui.html}) настроен в application.yml ({@code springdoc.swagger-ui.url}) показывать
 * именно его — то есть документация = контракт, а не то, что springdoc «угадал» по коду.
 *
 * <p>{@code @Configuration} + {@link WebMvcConfigurer} (без {@code @EnableWebMvc}) только добавляет обработчик,
 * автоконфигурация Spring Boot (static/, index.html на «/») продолжает работать.
 */
@Configuration
public class OpenApiContractConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi/**").addResourceLocations("classpath:/openapi/");
    }
}
