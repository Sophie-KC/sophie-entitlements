package org.sophie.entitlements;

import java.util.UUID;

/**
 * The client surface every service should use for entitlement checks against subscription-service.
 * See the module design doc §3.2/§0 rule for the fail-open/fail-closed split this interface encodes:
 * a subscription-service outage must never lock a paying customer out of their own data, but must
 * never silently let a quota-consuming write through either.
 */
public interface Entitlements {

    /**
     * Throws {@link EntitlementDeniedException} if {@code key} is not allowed for {@code orgId}.
     * Use on a quota-consuming write (e.g. "about to insert the 6th member"). FAILS CLOSED: if
     * subscription-service can't be reached, this denies rather than silently allowing the write.
     */
    void require(UUID orgId, String key);

    /**
     * The resolved numeric limit for {@code key} ({@code Long.MAX_VALUE} when unlimited). Use for
     * read-ish display (a "3 of 5 seats used" progress bar). FAILS OPEN: returns
     * {@code Long.MAX_VALUE} if subscription-service can't be reached — never blocks a plain read on
     * a billing outage.
     */
    long limit(UUID orgId, String key);

    /**
     * Throws {@link EntitlementDeniedException} when the org's subscription is {@code READ_ONLY}
     * (suspended/expired). FAILS CLOSED, same reasoning as {@link #require}.
     */
    void requireWriteAccess(UUID orgId);
}
