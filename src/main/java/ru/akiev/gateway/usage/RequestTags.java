package ru.akiev.gateway.usage;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import ru.akiev.gateway.dto.ChatRequest;
import ru.akiev.gateway.limits.RequestEstimator;

/**
 * Метки клиентского запроса: заголовки {@code x-run-id}, {@code x-issue}, {@code x-client}, {@code x-attempt}
 * и поле тела {@code user}, плюс размеры запроса, посчитанные один раз.
 *
 * <p>Как метки доходят до {@link UsageService}: роутер про них не знает (и не должен), поэтому контроллер
 * кладёт их в ThreadLocal на время обработки запроса ({@link #bind(RequestTags)}), а UsageService читает
 * {@link #current()} в момент записи попытки. Для стрима контроллер привязывает те же метки ещё раз — уже в потоке,
 * который отдаёт чанки (StreamingResponseBody работает в другом потоке). {@link Metrics} — тот же объект,
 * поэтому {@code failover_index} продолжается с попыток, сделанных до начала стрима.
 */
public record RequestTags(String runId, String issue, String client, String user, Metrics metrics) {

    public static final RequestTags NONE = new RequestTags(null, null, null, null);

    private static final ThreadLocal<RequestTags> CURRENT = ThreadLocal.withInitial(() -> NONE);

    /** Метки без размеров (тесты и «запрос без контекста»). */
    public RequestTags(String runId, String issue, String client, String user) {
        this(runId, issue, client, user, null);
    }

    /** Нормализуем: обрезаем пробелы и длину (как в колонках БД), убираем управляющие символы, пустое → null. */
    public RequestTags {
        runId = clean(runId, 128);
        issue = clean(issue, 64);
        client = clean(client, 64);
        user = clean(user, 128);
    }

    /**
     * Метки одного входящего {@code POST /v1/chat/completions}. {@code request_id} новый на каждый вызов,
     * размеры считаются один раз ({@link RequestEstimator#measureInput}).
     *
     * @param attemptHeader сырой {@code x-attempt}; нечисло и пусто → null, запрос не отклоняем
     */
    public static RequestTags capture(String runId, String issue, String client, String user,
            String attemptHeader, ChatRequest request) {
        RequestEstimator.Input in = RequestEstimator.measureInput(request);
        Metrics metrics = new Metrics(parseAttempt(attemptHeader), UUID.randomUUID().toString(),
                in.messages(), in.chars(), saturate(in.tokens()),
                request == null ? null : request.maxTokens(),
                request != null && request.streamRequested());
        return new RequestTags(runId, issue, client, user, metrics);
    }

    /**
     * Целое из заголовка. Пробелы по краям снимаются. Пусто, нечисло и число вне int — null
     * (слишком длинная строка тоже: {@link Integer#valueOf} на ней бросил бы исключение).
     */
    public static Integer parseAttempt(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.strip();
        if (t.isEmpty() || t.length() > 11) {
            return null;
        }
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static RequestTags current() {
        return CURRENT.get();
    }

    /** Привязать метки к текущему потоку. Использовать в try-with-resources: {@code try (var s = RequestTags.bind(t)) {...}}. */
    public static Scope bind(RequestTags tags) {
        RequestTags previous = CURRENT.get();
        CURRENT.set(tags == null ? NONE : tags);
        return () -> CURRENT.set(previous);
    }

    /** Возврат прежних меток при выходе из блока. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Размеры и связь попыток одного входящего запроса. Счётчик failover живёт здесь, а не в record:
     * {@link #NONE} один на все потоки и не должен его крутить, а успешный стрим читает тот же объект
     * уже из другого потока.
     */
    public static final class Metrics {
        private final Integer attempt;
        private final String requestId;
        private final int messagesCount;
        private final long requestChars;
        private final int estPromptTokens;
        private final Integer maxTokens;
        private final boolean stream;
        private final AtomicInteger failover = new AtomicInteger();

        public Metrics(Integer attempt, String requestId, int messagesCount, long requestChars,
                int estPromptTokens, Integer maxTokens, boolean stream) {
            this.attempt = attempt;
            this.requestId = requestId != null && requestId.length() > 64 ? requestId.substring(0, 64) : requestId;
            this.messagesCount = messagesCount;
            this.requestChars = requestChars;
            this.estPromptTokens = estPromptTokens;
            this.maxTokens = maxTokens;
            this.stream = stream;
        }

        /** Следующий failover_index: 0 у первой попытки к провайдеру. */
        public int claimFailoverIndex() {
            return failover.getAndIncrement();
        }

        public Integer attempt() {
            return attempt;
        }

        public String requestId() {
            return requestId;
        }

        public int messagesCount() {
            return messagesCount;
        }

        public long requestChars() {
            return requestChars;
        }

        public int estPromptTokens() {
            return estPromptTokens;
        }

        public Integer maxTokens() {
            return maxTokens;
        }

        public boolean stream() {
            return stream;
        }
    }

    private static int saturate(long v) {
        if (v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (v < 0) {
            return 0;
        }
        return (int) v;
    }

    private static String clean(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\p{Cntrl}", "").strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
