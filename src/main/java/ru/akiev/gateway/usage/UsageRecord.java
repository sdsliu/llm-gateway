package ru.akiev.gateway.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Одна попытка обращения к провайдеру. Схема — db/migration/V1__usage_record.sql,
 * метки запроса (run_id, issue, client, request_user) — V2__usage_record_tags.sql,
 * размеры входящего запроса — V4__usage_record_request_metrics.sql.
 * Тело запроса сюда не пишется.
 */
@Entity
@Table(name = "usage_record")
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String provider;
    private String model;
    @Column(name = "prompt_tokens")
    private Integer promptTokens;
    @Column(name = "completion_tokens")
    private Integer completionTokens;
    @Column(name = "total_tokens")
    private Integer totalTokens;
    private int status;
    @Column(name = "latency_ms")
    private long latencyMs;
    private String error;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "run_id")
    private String runId;
    private String issue;
    private String client;
    @Column(name = "request_user")
    private String requestUser;
    /** Номер попытки runner (x-attempt). NULL у старых строк и если заголовок был не целым. */
    private Integer attempt;
    @Column(name = "request_id")
    private String requestId;
    @Column(name = "failover_index")
    private Integer failoverIndex;
    @Column(name = "messages_count")
    private Integer messagesCount;
    @Column(name = "request_chars")
    private Long requestChars;
    @Column(name = "est_prompt_tokens")
    private Integer estPromptTokens;
    @Column(name = "max_tokens")
    private Integer maxTokens;
    private Boolean stream;

    protected UsageRecord() {
        // для JPA
    }

    public UsageRecord(String provider, String model, Integer promptTokens, Integer completionTokens,
            Integer totalTokens, int status, long latencyMs, String error, Instant createdAt) {
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.status = status;
        this.latencyMs = latencyMs;
        this.error = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        this.createdAt = createdAt;
    }

    /** То же + метки (x-run-id, x-issue, x-client, user) и метрики запроса, если они есть в tags. */
    public UsageRecord(String provider, String model, Integer promptTokens, Integer completionTokens,
            Integer totalTokens, int status, long latencyMs, String error, Instant createdAt, RequestTags tags) {
        this(provider, model, promptTokens, completionTokens, totalTokens, status, latencyMs, error, createdAt);
        if (tags != null) {
            this.runId = tags.runId();
            this.issue = tags.issue();
            this.client = tags.client();
            this.requestUser = tags.user();
            RequestTags.Metrics m = tags.metrics();
            if (m != null) {
                this.attempt = m.attempt();
                this.requestId = m.requestId();
                this.failoverIndex = m.claimFailoverIndex();
                this.messagesCount = m.messagesCount();
                this.requestChars = m.requestChars();
                this.estPromptTokens = m.estPromptTokens();
                this.maxTokens = m.maxTokens();
                this.stream = m.stream();
            }
        }
    }

    /**
     * Размер контекста для статистики: фактические {@code prompt_tokens}, а если провайдер их не прислал —
     * оценка входа. null — старая строка, где нет ни того, ни другого (агрегаты не должны из-за этого падать).
     */
    public Integer contextTokens() {
        return promptTokens != null ? promptTokens : estPromptTokens;
    }

    public Long getId() { return id; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public int getStatus() { return status; }
    public long getLatencyMs() { return latencyMs; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public String getRunId() { return runId; }
    public String getIssue() { return issue; }
    public String getClient() { return client; }
    public String getRequestUser() { return requestUser; }
    public Integer getAttempt() { return attempt; }
    public String getRequestId() { return requestId; }
    public Integer getFailoverIndex() { return failoverIndex; }
    public Integer getMessagesCount() { return messagesCount; }
    public Long getRequestChars() { return requestChars; }
    public Integer getEstPromptTokens() { return estPromptTokens; }
    public Integer getMaxTokens() { return maxTokens; }
    public Boolean getStream() { return stream; }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
