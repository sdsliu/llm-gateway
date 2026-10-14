package ru.akiev.gateway.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Одна попытка обращения к провайдеру. Схема — db/migration/V1__usage_record.sql. */
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

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
