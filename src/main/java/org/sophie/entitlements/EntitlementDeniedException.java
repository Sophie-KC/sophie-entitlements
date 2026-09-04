package org.sophie.entitlements;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;

/**
 * Thrown by {@link Entitlements#requireFeature}, {@link Entitlements#requireQuota} and
 * {@link Entitlements#requireWriteAccess}. {@code limit} and {@code current} are {@code -1} when not
 * meaningful (a boolean feature-flag denial, or the read-only-access-mode denial from {@code
 * requireWriteAccess}) — callers must check for that sentinel before displaying a "3 of 5" message.
 *
 * <p>{@link Kind} is the load-bearing field for Phase 3 §1's error contract: it decides both which
 * gRPC status this becomes on the wire ({@link #toStatusRuntimeException()}) and which HTTP status
 * the Gateway maps it to. {@code FEATURE} and {@code QUOTA} would otherwise be indistinguishable from
 * "an ordinary permission failure" at the Gateway (both can legitimately produce PERMISSION_DENIED or
 * FAILED_PRECONDITION for reasons that have nothing to do with billing) — {@code Kind} is carried as
 * its own gRPC trailer specifically so the Gateway never has to guess intent from the status code
 * alone.
 *
 * <p>Encoding uses plain gRPC {@link Metadata} trailers, not a {@code google.rpc.ErrorInfo}/{@code
 * StatusProto} payload — nothing in this platform uses that convention today, and a handful of ASCII
 * key/value trailers is enough for five scalar fields. {@link #decode(Throwable)} is the exact inverse
 * of {@link #toStatusRuntimeException()}; keep them in sync — this is the one place the wire format is
 * allowed to be defined, so every throwing service and the Gateway share the same contract instead of
 * each inventing their own.
 */
public class EntitlementDeniedException extends RuntimeException {

    /**
     * FEATURE: a boolean plan capability is off (e.g. {@code video.enabled}) — gRPC PERMISSION_DENIED,
     * Gateway HTTP 403 with the entitlement body (an upgrade prompt, not a role problem).
     * QUOTA: a numeric limit is exhausted (e.g. {@code seats.max}) — gRPC FAILED_PRECONDITION, Gateway
     * HTTP 409.
     * ACCESS: the org's subscription is READ_ONLY (suspended/expired) — gRPC FAILED_PRECONDITION,
     * Gateway HTTP 402. Carries no meaningful key/limit/current; the Gateway's suspension filter (Phase
     * 3 §2) is the primary path for this case, but backend services reachable without the Gateway
     * (jobs, consumers, internal RPC) still need to fail closed on their own via
     * {@link Entitlements#requireWriteAccess}.
     */
    public enum Kind { FEATURE, QUOTA, ACCESS }

    private static final Metadata.Key<String> KIND_KEY =
            Metadata.Key.of("entitlement-kind", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> ENTITLEMENT_KEY_KEY =
            Metadata.Key.of("entitlement-key", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> LIMIT_KEY =
            Metadata.Key.of("entitlement-limit", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CURRENT_KEY =
            Metadata.Key.of("entitlement-current", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> UNLIMITED_KEY =
            Metadata.Key.of("entitlement-unlimited", Metadata.ASCII_STRING_MARSHALLER);

    private final Kind kind;
    private final String key;
    private final long limit;
    private final long current;
    private final boolean unlimited;

    public EntitlementDeniedException(Kind kind, String key, long limit, long current, boolean unlimited) {
        super("Entitlement denied for key '" + key + "' (" + kind + ")"
                + (limit >= 0 ? " (limit=" + limit + (current >= 0 ? ", current=" + current : "") + ")" : ""));
        this.kind = kind;
        this.key = key;
        this.limit = limit;
        this.current = current;
        this.unlimited = unlimited;
    }

    public static EntitlementDeniedException feature(String key) {
        return new EntitlementDeniedException(Kind.FEATURE, key, -1, -1, false);
    }

    public static EntitlementDeniedException quota(String key, long limit, long current) {
        return new EntitlementDeniedException(Kind.QUOTA, key, limit, current, false);
    }

    public static EntitlementDeniedException accessDenied() {
        return new EntitlementDeniedException(Kind.ACCESS, "access_mode", -1, -1, false);
    }

    public Kind getKind() {
        return kind;
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

    public boolean isUnlimited() {
        return unlimited;
    }

    /**
     * The gRPC status a throwing service should actually send. {@code FEATURE} -&gt;
     * PERMISSION_DENIED (matches "you may not do this at all"); {@code QUOTA} and {@code ACCESS} -&gt;
     * FAILED_PRECONDITION (matches "the org's current state doesn't allow this right now") — the two
     * are NOT distinguished by status code, deliberately: the Gateway decodes {@code Kind} from the
     * trailers to tell them apart rather than relying on the raw code, so this choice is really just
     * "a reasonable code for a non-Gateway caller/log line to see," not part of the actual contract.
     */
    public StatusRuntimeException toStatusRuntimeException() {
        Status status = kind == Kind.FEATURE
                ? Status.PERMISSION_DENIED.withDescription(getMessage())
                : Status.FAILED_PRECONDITION.withDescription(getMessage());
        Metadata trailers = new Metadata();
        trailers.put(KIND_KEY, kind.name());
        trailers.put(ENTITLEMENT_KEY_KEY, key);
        trailers.put(LIMIT_KEY, Long.toString(limit));
        trailers.put(CURRENT_KEY, Long.toString(current));
        trailers.put(UNLIMITED_KEY, Boolean.toString(unlimited));
        return status.asRuntimeException(trailers);
    }

    /** The exact inverse of {@link #toStatusRuntimeException()}. Empty if {@code error} isn't a
     *  {@link StatusRuntimeException} carrying these trailers at all — an ordinary permission failure
     *  (or any other gRPC error) has none of this metadata, which is precisely how the Gateway tells
     *  "upgrade prompt" apart from "you lack the role." */
    public static Optional<Decoded> decode(Throwable error) {
        if (!(error instanceof StatusRuntimeException sre)) {
            return Optional.empty();
        }
        Metadata trailers = sre.getTrailers();
        if (trailers == null) {
            return Optional.empty();
        }
        String kindValue = trailers.get(KIND_KEY);
        String keyValue = trailers.get(ENTITLEMENT_KEY_KEY);
        if (kindValue == null || keyValue == null) {
            return Optional.empty();
        }
        Kind kind;
        try {
            kind = Kind.valueOf(kindValue);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        long limit = parseLongOr(trailers.get(LIMIT_KEY), -1);
        long current = parseLongOr(trailers.get(CURRENT_KEY), -1);
        boolean unlimited = Boolean.parseBoolean(trailers.get(UNLIMITED_KEY));
        return Optional.of(new Decoded(kind, keyValue, limit, current, unlimited));
    }

    private static long parseLongOr(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public record Decoded(Kind kind, String key, long limit, long current, boolean unlimited) {}
}
