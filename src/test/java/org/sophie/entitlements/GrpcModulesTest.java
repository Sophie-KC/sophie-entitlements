package org.sophie.entitlements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sophie.orgservice.grpc.ModuleState;
import org.sophie.orgservice.grpc.OrgModules;
import org.sophie.orgservice.grpc.OrgServiceGrpc;

@ExtendWith(MockitoExtension.class)
class GrpcModulesTest {

    @Mock
    private OrgServiceGrpc.OrgServiceBlockingStub stub;

    private ModulesNearCache cache;
    private GrpcModules modules;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        cache = new ModulesNearCache();
        modules = new GrpcModules(stub, new EntitlementsProperties(), cache);
        orgId = UUID.randomUUID();
    }

    private void stubTasksOff() {
        when(stub.getOrgModules(any())).thenReturn(OrgModules.newBuilder()
                .addModules(ModuleState.newBuilder().setKey("chat").setEnabled(true))
                .addModules(ModuleState.newBuilder().setKey("tasks").setEnabled(false))
                .build());
    }

    @Test
    void disabledModuleIsRefusedWithTheModuleKind() {
        stubTasksOff();

        assertThatThrownBy(() -> modules.requireEnabled(orgId, Modules.TASKS))
                .isInstanceOf(EntitlementDeniedException.class)
                .satisfies(e -> {
                    EntitlementDeniedException ede = (EntitlementDeniedException) e;
                    assertThat(ede.getKind()).isEqualTo(EntitlementDeniedException.Kind.MODULE);
                    assertThat(ede.getKey()).isEqualTo("tasks");
                });
        modules.requireEnabled(orgId, Modules.CHAT); // must not throw
    }

    @Test
    void moduleWithNoRowIsOn() {
        stubTasksOff();

        assertThat(modules.isEnabled(orgId, Modules.CALENDAR)).isTrue();
    }

    @Test
    void failsOpenWhenOrgServiceIsDown() {
        when(stub.getOrgModules(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThat(modules.isEnabled(orgId, Modules.TASKS)).isTrue();
        modules.requireEnabled(orgId, Modules.TASKS); // must not throw
    }

    @Test
    void nullOrgIsANoOpWithoutACall() {
        modules.requireEnabled(null, Modules.TASKS);

        verify(stub, never()).getOrgModules(any());
    }

    @Test
    void cachesPerOrgUntilEvicted() {
        stubTasksOff();

        modules.isEnabled(orgId, Modules.TASKS);
        modules.isEnabled(orgId, Modules.DOCS);
        verify(stub, times(1)).getOrgModules(any());

        cache.evict(orgId);
        modules.isEnabled(orgId, Modules.TASKS);
        verify(stub, times(2)).getOrgModules(any());
    }

    @Test
    void moduleDenialRoundTripsThroughTheWireFormat() {
        StatusRuntimeException wire = EntitlementDeniedException.moduleDisabled("docs").toStatusRuntimeException();

        assertThat(wire.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(EntitlementDeniedException.decode(wire)).hasValueSatisfying(d -> {
            assertThat(d.kind()).isEqualTo(EntitlementDeniedException.Kind.MODULE);
            assertThat(d.key()).isEqualTo("docs");
        });
    }
}
