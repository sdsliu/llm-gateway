package ru.akiev.gateway.router;

import java.util.Arrays;
import java.util.Locale;

/**
 * Режим выбора провайдера.
 * <ul>
 *   <li>{@link #PRIORITY} — по приоритету (меньше = раньше); опционально выбранный primary идёт первым;</li>
 *   <li>{@link #ROUND_ROBIN} — по кругу среди доступных сейчас провайдеров (без cooldown/лимита);
 *       при ошибке — failover на следующего доступного;</li>
 *   <li>{@link #HEADROOM} — к тому, у кого больше всего запаса по лимитам (RPM, RPD, TPM, сутки, месяц) с учётом
 *       размера этого запроса; при равенстве — по приоритету (issue #34).</li>
 * </ul>
 * Во всех режимах провайдер без места под запрос пропускается, а если места нет ни у кого — запрос ждёт
 * (gateway.limits.max-wait) и получает 429.
 */
public enum RoutingMode {
    PRIORITY,
    ROUND_ROBIN,
    HEADROOM;

    /** Разбор из JSON/UI: регистр не важен, «round-robin» = «round_robin». null — если не распознано. */
    public static RoutingMode parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values()).filter(m -> m.name().equals(v)).findFirst().orElse(null);
    }
}
