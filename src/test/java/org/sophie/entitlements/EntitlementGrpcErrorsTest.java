package org.sophie.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

/** Phase 3 closeout §1: the shared delegate every service's own GrpcErrors should call first. */
class EntitlementGrpcErrorsTest {

    @Test
    void mapsAnEntitlementDeniedExceptionWithFullStructuredDetail() {
        StatusRuntimeException wire = EntitlementGrpcErrors.tryMap(EntitlementDeniedException.quota("seats.max", 5, 5))
                .orElseThrow();

        assertThat(wire.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(EntitlementDeniedException.decode(wire)).isPresent().hasValueSatisfying(decoded -> {
            assertThat(decoded.kind()).isEqualTo(EntitlementDeniedException.Kind.QUOTA);
            assertThat(decoded.key()).isEqualTo("seats.max");
            assertThat(decoded.limit()).isEqualTo(5);
            assertThat(decoded.current()).isEqualTo(5);
        });
    }

    @Test
    void leavesAnyOtherExceptionUnmappedForTheCallerToHandleItself() {
        assertThat(EntitlementGrpcErrors.tryMap(new IllegalArgumentException("not an entitlement thing"))).isEmpty();
        assertThat(EntitlementGrpcErrors.tryMap(new IllegalStateException("also not one"))).isEmpty();
    }
}
