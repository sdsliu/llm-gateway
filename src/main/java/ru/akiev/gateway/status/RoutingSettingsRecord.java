package ru.akiev.gateway.status;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Сохранённый режим роутинга — единственная строка таблицы routing_settings (id = 1),
 * схема — db/migration/V3__routing_settings.sql.
 *
 * <p>mode храним строкой, а не enum-ом JPA: если в будущем режим переименуют/удалят, старая строка
 * не уронит старт (неизвестный режим → WARN и настройки из конфига).
 */
@Entity
@Table(name = "routing_settings")
public class RoutingSettingsRecord {

    /** Единственная строка: CHECK (id = 1) в схеме. */
    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id = SINGLETON_ID;
    private String mode;
    @Column(name = "primary_provider")
    private String primaryProvider;
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected RoutingSettingsRecord() {
    }

    public RoutingSettingsRecord(String mode, String primaryProvider, Instant updatedAt) {
        this.mode = mode;
        this.primaryProvider = primaryProvider;
        this.updatedAt = updatedAt;
    }

    public Integer getId() {
        return id;
    }

    public String getMode() {
        return mode;
    }

    public String getPrimaryProvider() {
        return primaryProvider;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
