package ru.akiev.gateway.usage;

/** Spring-событие «записали usage» — на него подписан SSE-рассыльщик дашборда. */
public record UsageRecordedEvent(UsageRecord record) {
}
