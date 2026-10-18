package ru.akiev.gateway.status;

import org.springframework.data.jpa.repository.JpaRepository;

/** Таблица routing_settings: одна строка с id = {@link RoutingSettingsRecord#SINGLETON_ID}. */
public interface RoutingSettingsRepository extends JpaRepository<RoutingSettingsRecord, Integer> {
}
