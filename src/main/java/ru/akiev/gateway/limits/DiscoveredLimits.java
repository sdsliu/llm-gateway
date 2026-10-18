package ru.akiev.gateway.limits;

/**
 * Лимиты, которые сообщил провайдер (или конфиг). Любое поле может быть null — «не сообщил».
 *
 * @param rpm            запросов в минуту
 * @param rpd            запросов в сутки
 * @param tpm            токенов в минуту
 * @param tokensPerDay   токенов в сутки
 * @param tokensPerMonth токенов в месяц
 */
public record DiscoveredLimits(Long rpm, Long rpd, Long tpm, Long tokensPerDay, Long tokensPerMonth) {

    public static final DiscoveredLimits NONE = new DiscoveredLimits(null, null, null, null, null);

    /** Хоть одно поле известно. */
    public boolean any() {
        return rpm != null || rpd != null || tpm != null || tokensPerDay != null || tokensPerMonth != null;
    }

    /** Поля other поверх этих (null в other не затирает). */
    public DiscoveredLimits overlay(DiscoveredLimits other) {
        if (other == null) {
            return this;
        }
        return new DiscoveredLimits(other.rpm != null ? other.rpm : rpm, other.rpd != null ? other.rpd : rpd,
                other.tpm != null ? other.tpm : tpm,
                other.tokensPerDay != null ? other.tokensPerDay : tokensPerDay,
                other.tokensPerMonth != null ? other.tokensPerMonth : tokensPerMonth);
    }

    /** Из блока limits конфига; 0 и отрицательные = «без ограничения» = null. */
    public static DiscoveredLimits fromConfig(ru.akiev.gateway.config.ProviderProperties.Limits l) {
        if (l == null) {
            return NONE;
        }
        return new DiscoveredLimits(pos(l.rpm()), pos(l.rpd()), pos(l.tpm()), pos(l.tokensPerDay()), pos(l.tokensPerMonth()));
    }

    private static Long pos(Number n) {
        return n == null || n.longValue() <= 0 ? null : n.longValue();
    }
}
