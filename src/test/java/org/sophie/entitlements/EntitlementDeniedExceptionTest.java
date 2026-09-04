package org.sophie.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Phase 3 §1: {@link EntitlementDeniedException#toStatusRuntimeException()} and {@link
 *  EntitlementDeniedException#decode} must be exact inverses — this is the wire contract every
 *  throwing service and the Gateway share, with nothing else defining or checking the format. */
class EntitlementDeniedExceptionTest {

    @Test
    void featureDenialRoundTripsThroughStatusEncoding() {
        EntitlementDeniedException original = EntitlementDeniedException.feature("video.enabled");

        StatusRuntimeException wire = original.toStatusRuntimeException();
        assertThat(wire.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);

        Optional<EntitlementDeniedException.Decoded> decoded = EntitlementDeniedException.decode(wire);
        assertThat(decoded).isPresent();
        assertThat(decoded.get().kind()).isEqualTo(EntitlementDeniedException.Kind.FEATURE);
        assertThat(decoded.get().key()).isEqualTo("video.enabled");
        assertThat(decoded.get().limit()).isEqualTo(-1);
        assertThat(decoded.get().current()).isEqualTo(-1);
        assertThat(decoded.get().unlimited()).isFalse();
    }

    @Test
    void quotaDenialRoundTripsThroughStatusEncoding() {
        EntitlementDeniedException original = EntitlementDeniedException.quota("seats.max", 5, 5);

        StatusRuntimeException wire = original.toStatusRuntimeException();
        assertThat(wire.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);

        Optional<EntitlementDeniedException.Decoded> decoded = EntitlementDeniedException.decode(wire);
        assertThat(decoded).isPresent();
        assertThat(decoded.get().kind()).isEqualTo(EntitlementDeniedException.Kind.QUOTA);
        assertThat(decoded.get().key()).isEqualTo("seats.max");
        assertThat(decoded.get().limit()).isEqualTo(5);
        assertThat(decoded.get().current()).isEqualTo(5);
    }

    @Test
    void accessDenialRoundTripsThroughStatusEncoding() {
        EntitlementDeniedException original = EntitlementDeniedException.accessDenied();

        StatusRuntimeException wire = original.toStatusRuntimeException();
        assertThat(wire.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);

        Optional<EntitlementDeniedException.Decoded> decoded = EntitlementDeniedException.decode(wire);
        assertThat(decoded).isPresent();
        assertThat(decoded.get().kind()).isEqualTo(EntitlementDeniedException.Kind.ACCESS);
    }

    @Test
    void quotaAndAccessShareTheSameGrpcCodeButDecodeToDifferentKinds() {
        // The exact ambiguity toStatusRuntimeException()'s own doc calls out: QUOTA and ACCESS both
        // send FAILED_PRECONDITION - Kind, not the code, is what the Gateway must actually branch on.
        StatusRuntimeException quotaWire = EntitlementDeniedException.quota("guests.max", 2, 2).toStatusRuntimeException();
        StatusRuntimeException accessWire = EntitlementDeniedException.accessDenied().toStatusRuntimeException();

        assertThat(quotaWire.getStatus().getCode()).isEqualTo(accessWire.getStatus().getCode());
        assertThat(EntitlementDeniedException.decode(quotaWire).orElseThrow().kind())
                .isNotEqualTo(EntitlementDeniedException.decode(accessWire).orElseThrow().kind());
    }

    @Test
    void anOrdinaryGrpcErrorWithNoEntitlementTrailersDecodesToEmpty() {
        StatusRuntimeException ordinary = Status.PERMISSION_DENIED.withDescription("not an org admin").asRuntimeException();

        assertThat(EntitlementDeniedException.decode(ordinary)).isEmpty();
    }

    @Test
    void aNonStatusExceptionDecodesToEmpty() {
        assertThat(EntitlementDeniedException.decode(new IllegalStateException("unrelated"))).isEmpty();
    }
}
