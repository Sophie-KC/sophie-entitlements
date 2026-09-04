package org.sophie.entitlements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sophie.subscriptionservice.grpc.AccessMode;
import org.sophie.subscriptionservice.grpc.EntitlementMap;
import org.sophie.subscriptionservice.grpc.EntitlementServiceGrpc;
import org.sophie.subscriptionservice.grpc.EntitlementValue;

/** Phase 3 §1: requireFeature/requireQuota/accessMode are the methods the Gateway's whole error
 *  contract and the suspension filter are built on — worth testing directly, not just through the
 *  services that call them. */
@ExtendWith(MockitoExtension.class)
class GrpcEntitlementsTest {

    @Mock
    private EntitlementServiceGrpc.EntitlementServiceBlockingStub stub;

    private GrpcEntitlements entitlements;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        entitlements = new GrpcEntitlements(stub, new EntitlementsProperties(), new EntitlementsNearCache());
        orgId = UUID.randomUUID();
    }

    private void stubMap(EntitlementMap map) {
        when(stub.getEntitlements(any())).thenReturn(map);
    }

    @Test
    void requireFeatureAllowsWhenBoolValueTrue() {
        stubMap(EntitlementMap.newBuilder()
                .putEntitlements("video.enabled", EntitlementValue.newBuilder().setBoolValue(true).build())
                .build());

        entitlements.requireFeature(orgId, "video.enabled"); // must not throw
    }

    @Test
    void requireFeatureDeniesWhenBoolValueFalse() {
        stubMap(EntitlementMap.newBuilder()
                .putEntitlements("video.enabled", EntitlementValue.newBuilder().setBoolValue(false).build())
                .build());

        assertThatThrownBy(() -> entitlements.requireFeature(orgId, "video.enabled"))
                .isInstanceOf(EntitlementDeniedException.class)
                .satisfies(e -> assertThat(((EntitlementDeniedException) e).getKind())
                        .isEqualTo(EntitlementDeniedException.Kind.FEATURE));
    }

    @Test
    void requireQuotaAllowsWhenUnderLimit() {
        stubMap(EntitlementMap.newBuilder()
                .putEntitlements("seats.max", EntitlementValue.newBuilder().setNumberValue(5).build())
                .build());

        entitlements.requireQuota(orgId, "seats.max", 3); // 3 existing + 1 new = 4 <= 5, must not throw
    }

    @Test
    void requireQuotaDeniesAtExactLimit() {
        stubMap(EntitlementMap.newBuilder()
                .putEntitlements("seats.max", EntitlementValue.newBuilder().setNumberValue(5).build())
                .build());

        assertThatThrownBy(() -> entitlements.requireQuota(orgId, "seats.max", 5))
                .isInstanceOf(EntitlementDeniedException.class)
                .satisfies(e -> {
                    EntitlementDeniedException ede = (EntitlementDeniedException) e;
                    assertThat(ede.getKind()).isEqualTo(EntitlementDeniedException.Kind.QUOTA);
                    assertThat(ede.getLimit()).isEqualTo(5);
                    assertThat(ede.getCurrent()).isEqualTo(5);
                });
    }

    @Test
    void requireQuotaAllowsUnlimitedRegardlessOfUsage() {
        stubMap(EntitlementMap.newBuilder()
                .putEntitlements("seats.max", EntitlementValue.newBuilder().setIsUnlimited(true).build())
                .build());

        entitlements.requireQuota(orgId, "seats.max", 999_999); // must not throw
    }

    @Test
    void requireQuotaTotalAllowsWhenProspectiveTotalIsAtTheLimit() {
        stubMap(EntitlementMap.newBuilder()
                .putEntitlements("storage.total.gb", EntitlementValue.newBuilder().setNumberValue(100).build())
                .build());

        entitlements.requireQuotaTotal(orgId, "storage.total.gb", 100); // exactly at the limit, must not throw
    }

    @Test
    void requireQuotaTotalDeniesWhenProspectiveTotalExceedsTheLimit() {
        stubMap(EntitlementMap.newBuilder()
                .putEntitlements("storage.total.gb", EntitlementValue.newBuilder().setNumberValue(100).build())
                .build());

        // A single large file can push usage up by far more than "1" — requireQuota's implicit +1
        // wouldn't catch this; requireQuotaTotal takes the exact prospective total instead.
        assertThatThrownBy(() -> entitlements.requireQuotaTotal(orgId, "storage.total.gb", 137))
                .isInstanceOf(EntitlementDeniedException.class)
                .satisfies(e -> {
                    EntitlementDeniedException ede = (EntitlementDeniedException) e;
                    assertThat(ede.getKind()).isEqualTo(EntitlementDeniedException.Kind.QUOTA);
                    assertThat(ede.getLimit()).isEqualTo(100);
                    assertThat(ede.getCurrent()).isEqualTo(137);
                });
    }

    @Test
    void requireQuotaTotalAllowsUnlimitedRegardlessOfUsage() {
        stubMap(EntitlementMap.newBuilder()
                .putEntitlements("storage.total.gb", EntitlementValue.newBuilder().setIsUnlimited(true).build())
                .build());

        entitlements.requireQuotaTotal(orgId, "storage.total.gb", 999_999); // must not throw
    }

    @Test
    void accessModeReadsFullByDefault() {
        stubMap(EntitlementMap.newBuilder().setAccessMode(AccessMode.FULL).build());

        assertThat(entitlements.accessMode(orgId)).isEqualTo(Entitlements.AccessMode.FULL);
    }

    @Test
    void accessModeReadsReadOnlyWhenSuspended() {
        stubMap(EntitlementMap.newBuilder().setAccessMode(AccessMode.READ_ONLY).build());

        assertThat(entitlements.accessMode(orgId)).isEqualTo(Entitlements.AccessMode.READ_ONLY);
    }

    @Test
    void accessModeFailsOpenToFullOnOutage() {
        when(stub.getEntitlements(any())).thenThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.UNAVAILABLE));

        assertThat(entitlements.accessMode(orgId)).isEqualTo(Entitlements.AccessMode.FULL);
    }

    @Test
    void requireWriteAccessFailsClosedOnOutage() {
        when(stub.getEntitlements(any())).thenThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.UNAVAILABLE));

        assertThatThrownBy(() -> entitlements.requireWriteAccess(orgId))
                .isInstanceOf(EntitlementDeniedException.class)
                .satisfies(e -> assertThat(((EntitlementDeniedException) e).getKind())
                        .isEqualTo(EntitlementDeniedException.Kind.ACCESS));
    }
}
