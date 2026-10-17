package ru.akiev.gateway.router;

/**
 * Неизменяемая пара «режим + primary». Роутер хранит её в volatile-поле и подменяет целиком,
 * поэтому запрос всегда видит согласованные mode и primary.
 *
 * @param primary имя провайдера, который в режиме PRIORITY идёт первым; null — чистый приоритет.
 *                В ROUND_ROBIN игнорируется (но сохраняется до возврата в PRIORITY).
 */
public record RoutingSettings(RoutingMode mode, String primary) {

    public static final RoutingSettings DEFAULT = new RoutingSettings(RoutingMode.PRIORITY, null);

    public RoutingSettings {
        mode = mode == null ? RoutingMode.PRIORITY : mode;
        primary = primary == null || primary.isBlank() ? null : primary.trim();
    }

    @Override
    public String toString() {
        return mode + (primary != null ? " (primary=" + primary + ")" : "");
    }
}
