package ru.akiev.gateway.usage;

/**
 * Метки клиентского запроса: заголовки {@code x-run-id}, {@code x-issue}, {@code x-client} и поле тела {@code user}.
 *
 * <p>Как метки доходят до {@link UsageService}: роутер про них не знает (и не должен), поэтому контроллер
 * кладёт их в ThreadLocal на время обработки запроса ({@link #bind(RequestTags)}), а UsageService читает
 * {@link #current()} в момент записи попытки. Для стрима контроллер привязывает метки ещё раз — уже в потоке,
 * который отдаёт чанки (StreamingResponseBody работает в другом потоке).
 */
public record RequestTags(String runId, String issue, String client, String user) {

    public static final RequestTags NONE = new RequestTags(null, null, null, null);

    private static final ThreadLocal<RequestTags> CURRENT = ThreadLocal.withInitial(() -> NONE);

    /** Нормализуем: обрезаем пробелы и длину (как в колонках БД), убираем управляющие символы, пустое → null. */
    public RequestTags {
        runId = clean(runId, 128);
        issue = clean(issue, 64);
        client = clean(client, 64);
        user = clean(user, 128);
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

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}", "").strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
