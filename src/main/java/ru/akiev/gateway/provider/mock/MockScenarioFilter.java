package ru.akiev.gateway.provider.mock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Кладёт {@code x-mock-scenario} и {@code x-run-id} в ThreadLocal на время запроса.
 * {@link MockProvider} читает их в потоке Tomcat (и для стрима сценарий уже выбран
 * до перехода в поток {@code StreamingResponseBody}).
 */
@Component
@Profile({"mock", "test"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MockScenarioFilter extends OncePerRequestFilter {

    public static final String SCENARIO_HEADER = "x-mock-scenario";

    private static final ThreadLocal<String> SCENARIO = new ThreadLocal<>();
    private static final ThreadLocal<String> RUN_ID = new ThreadLocal<>();

    public static String scenarioHeader() {
        return SCENARIO.get();
    }

    public static String runIdHeader() {
        return RUN_ID.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SCENARIO.set(blankToNull(request.getHeader(SCENARIO_HEADER)));
        RUN_ID.set(blankToNull(request.getHeader("x-run-id")));
        try {
            chain.doFilter(request, response);
        } finally {
            SCENARIO.remove();
            RUN_ID.remove();
        }
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
