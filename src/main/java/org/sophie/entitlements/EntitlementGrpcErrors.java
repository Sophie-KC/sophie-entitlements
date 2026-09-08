package org.sophie.entitlements;

import io.grpc.StatusRuntimeException;
import java.util.Optional;

/**
 * Phase 3 closeout §1: the one place any service's own gRPC exception mapper should check for an
 * {@link EntitlementDeniedException} before falling through to its own service-specific exceptions.
 * This exact one-line check — {@code instanceof EntitlementDeniedException, then
 * ede.toStatusRuntimeException()} — was independently hand-written in file-service, doc-service,
 * task-service, and chat-service's own {@code GrpcErrors} (and the frontend's API client had the
 * analogous gap in its own error normalization). Six independent rediscoveries of one bug is the
 * signal that the FIX belongs in the shared library, not in a fifth, sixth, seventh copy.
 *
 * <p>Deliberately a plain static delegate, not a gRPC {@code ServerInterceptor} or
 * {@code @GrpcAdvice}/{@code @GrpcExceptionHandler} global handler: every service in this codebase
 * already catches the broad {@code RuntimeException} inside each RPC handler method and converts it via
 * its own {@code GrpcErrors.toStatusException(e)} before ever calling {@code responseObserver.onError}
 * — by the time any exception reaches that point, it has already been caught and is not going to
 * propagate further, so a cross-cutting interceptor sitting "above" the handler would never see it. A
 * plain delegate called from inside the existing, ubiquitous catch block requires no new step for a
 * future handler to get right: it only has to keep doing what every handler in this codebase already
 * does (call {@code GrpcErrors.toStatusException(e)} in its catch clause), and that method's first line
 * is now this call.
 *
 * <p>Each service's own {@code GrpcErrors.toStatusException} should read:
 * <pre>{@code
 * static StatusRuntimeException toStatusException(RuntimeException e) {
 *     return EntitlementGrpcErrors.tryMap(e).orElseGet(() -> mapServiceSpecific(e));
 * }
 * }</pre>
 * with no {@code instanceof EntitlementDeniedException} branch of its own — each service's own test
 * asserts that structurally (grep the source), not just behaviorally (behavior alone doesn't prove the
 * next service copied the delegate rather than reimplementing the branch).
 */
public final class EntitlementGrpcErrors {

    private EntitlementGrpcErrors() {}

    /** Empty if {@code e} isn't an {@link EntitlementDeniedException} — the caller falls through to its
     *  own mapping for everything else. */
    public static Optional<StatusRuntimeException> tryMap(RuntimeException e) {
        if (e instanceof EntitlementDeniedException ede) {
            return Optional.of(ede.toStatusRuntimeException());
        }
        return Optional.empty();
    }
}
