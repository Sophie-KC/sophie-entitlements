package org.sophie.entitlements;

/**
 * Thrown by {@link Entitlements#require} and {@link Entitlements#requireWriteAccess}. {@code limit}
 * and {@code current} are {@code -1} when not meaningful (e.g. a boolean feature-flag denial, or the
 * read-only-access-mode denial from {@code requireWriteAccess}) — callers must check for that sentinel
 * before displaying a "3 of 5" style message.
 */
public class EntitlementDeniedException extends RuntimeException {

    private final String key;
    private final long limit;
    private final long current;

    public EntitlementDeniedException(String key, long limit, long current) {
        super("Entitlement denied for key '" + key + "'"
                + (limit >= 0 ? " (limit=" + limit + (current >= 0 ? ", current=" + current : "") + ")" : ""));
        this.key = key;
        this.limit = limit;
        this.current = current;
    }

    public String getKey() {
        return key;
    }

    public long getLimit() {
        return limit;
    }

    public long getCurrent() {
        return current;
    }
}
