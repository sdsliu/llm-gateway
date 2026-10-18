package ru.akiev.gateway.limits;

import java.time.Instant;
import org.springframework.http.HttpHeaders;

/**
 * Итог опроса провайдера.
 *
 * @param ok         провайдер ответил и им можно пользоваться (иначе — исключить из роутинга)
 * @param method     что делали: «GET /key», «GET /models + POST /chat/completions (max_tokens=1)»…
 * @param api        лимиты из API провайдера (источник api), может быть {@link DiscoveredLimits#NONE}
 * @param headers    заголовки ответа, из которых берутся лимиты (источник headers); null — нет
 * @param httpStatus HTTP-статус последнего ответа (null — ответа не было: таймаут, сеть)
 * @param error      почему не ok (или примечание, например «chat 400» при ok=true)
 * @param latencyMs  сколько длился опрос
 * @param checkedAt  когда
 */
public record ProbeResult(boolean ok, String method, DiscoveredLimits api, HttpHeaders headers, Integer httpStatus,
        String error, long latencyMs, Instant checkedAt) {

    static ProbeResult ok(String method, DiscoveredLimits api, HttpHeaders headers, Integer status, String note) {
        return new ProbeResult(true, method, api == null ? DiscoveredLimits.NONE : api, headers, status, note, 0, null);
    }

    static ProbeResult failed(String method, Integer status, String error) {
        return new ProbeResult(false, method, DiscoveredLimits.NONE, null, status, error, 0, null);
    }

    ProbeResult timed(long latencyMs, Instant at) {
        return new ProbeResult(ok, method, api, headers, httpStatus, error, latencyMs, at);
    }
}
