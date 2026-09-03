package org.sophie.entitlements;

import org.sophie.subscriptionservice.grpc.EntitlementServiceGrpc;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Add {@code implementation 'org.sophie:sophie-entitlements'} plus a
 * {@code grpc.client.subscription-service.address} property and a consuming service gets an
 * {@link Entitlements} bean for free — no other wiring needed, matching every other *-client
 * convention in this codebase (this one just ships as a separate published artifact instead of
 * living inside one service, since every service needs it).
 */
@AutoConfiguration
@ConditionalOnClass(EntitlementServiceGrpc.class)
@EnableConfigurationProperties(EntitlementsProperties.class)
@Import(GrpcEntitlements.class)
public class EntitlementsAutoConfiguration {
}
