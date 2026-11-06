package ru.akiev.gateway.provider.mock;

/**
 * Тело ответа, которое нужно отдать клиенту байт-в-байт (сценарий {@code bad-json}).
 * Это не {@link ru.akiev.gateway.provider.ProviderException}: Jackson не должен
 * заворачивать строку в JSON и делать из неё валидный документ.
 */
public final class RawMockBody extends RuntimeException {

    private final int status;
    private final String body;

    public RawMockBody(int status, String body) {
        super("mock scenario returned a raw body");
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
