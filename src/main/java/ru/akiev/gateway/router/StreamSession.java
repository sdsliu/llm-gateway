package ru.akiev.gateway.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import ru.akiev.gateway.dto.ChatResponse;
import ru.akiev.gateway.dto.Usage;
import ru.akiev.gateway.provider.LlmProvider;
import ru.akiev.gateway.provider.ProviderException;

/**
 * Открытый стрим от выбранного провайдера: первый чанк уже получен (значит, провайдер точно
 * отвечает), остальные читаются через итератор.
 *
 * <p>Попутно вытаскиваем usage из чанков (последний чанк при stream_options.include_usage,
 * у Groq также x_groq.usage) и model/id — чтобы записать в usage_record после завершения.
 */
public class StreamSession implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmProvider provider;
    private final Stream<String> stream;
    private final Iterator<String> iterator;
    private final String firstChunk;
    private final long startNanos;
    private final boolean forwardUsageChunks;
    private final RouterListener listener;
    /** Отпустить in-flight слот провайдера, когда стрим дочитан или закрыт. Один раз. */
    private final Runnable onFinished;
    private final AtomicBoolean finishedOnce = new AtomicBoolean();

    private String model;
    private String id;
    private Usage usage;
    private boolean finished;

    StreamSession(LlmProvider provider, Stream<String> stream, Iterator<String> iterator, String firstChunk,
            long startNanos, boolean forwardUsageChunks, RouterListener listener, Runnable onFinished) {
        this.provider = provider;
        this.stream = stream;
        this.iterator = iterator;
        this.firstChunk = firstChunk;
        this.startNanos = startNanos;
        this.forwardUsageChunks = forwardUsageChunks;
        this.listener = listener;
        this.onFinished = onFinished;
    }

    public LlmProvider provider() {
        return provider;
    }

    /** Колбэк для каждого чанка, который нужно отдать клиенту. */
    @FunctionalInterface
    public interface ChunkWriter {
        void write(String json) throws java.io.IOException;
    }

    /**
     * Отдаёт клиенту все чанки (первый + остальные). Блокирует текущий поток — вызывается из
     * асинхронного потока StreamingResponseBody, не из потока Tomcat, принявшего запрос.
     *
     * @throws ProviderException если провайдер упал посреди стрима (failover уже невозможен)
     * @throws java.io.IOException если клиент отключился
     */
    public void drainTo(ChunkWriter writer) throws java.io.IOException {
        try {
            emit(firstChunk, writer);
            while (iterator.hasNext()) {
                emit(iterator.next(), writer);
            }
            finished = true;
            listener.onSuccess(provider, new ChatResponse(id, "chat.completion", null, model, null, usage), elapsedMs());
        } catch (ProviderException e) {
            listener.onFailure(provider, e, elapsedMs());
            throw e;
        } catch (java.io.IOException | RuntimeException e) {
            // Клиент закрыл соединение — фиксируем как 499 (client closed request, термин nginx).
            listener.onFailure(provider, new ProviderException(499, "Client disconnected: " + e.getMessage(), e),
                    elapsedMs());
            throw e;
        } finally {
            finish();
        }
    }

    private void emit(String json, ChunkWriter writer) throws java.io.IOException {
        boolean usageOnly = inspect(json);
        if (usageOnly && !forwardUsageChunks) {
            // Usage-чанк (choices: []) мы запросили сами для учёта — клиенту, который его не просил, не шлём.
            return;
        }
        writer.write(json);
    }

    /** Разбираем чанк, запоминаем model/id/usage. Возвращает true, если это чанк только с usage. */
    private boolean inspect(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n.hasNonNull("model")) {
                model = n.get("model").asText();
            }
            if (n.hasNonNull("id")) {
                id = n.get("id").asText();
            }
            JsonNode u = n.hasNonNull("usage") ? n.get("usage") : n.path("x_groq").path("usage");
            if (!u.isMissingNode() && !u.isNull() && u.has("total_tokens")) {
                usage = new Usage(u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt(),
                        u.path("total_tokens").asInt());
            }
            JsonNode choices = n.path("choices");
            return n.hasNonNull("usage") && choices.isArray() && choices.isEmpty();
        } catch (Exception e) {
            return false; // не JSON — просто пробрасываем как есть
        }
    }

    /** Usage, если провайдер его прислал; иначе null (в usage_record токены будут пустыми = «неизвестно»). */
    public Usage usage() {
        return usage;
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Закрытие Stream отменяет подписку на Flux → WebClient закрывает соединение с провайдером. */
    @Override
    public void close() {
        try {
            stream.close();
        } finally {
            finish();
        }
    }

    private void finish() {
        if (onFinished != null && finishedOnce.compareAndSet(false, true)) {
            onFinished.run();
        }
    }

    public boolean finished() {
        return finished;
    }
}
