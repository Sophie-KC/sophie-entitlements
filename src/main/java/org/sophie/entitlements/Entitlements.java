package org.sophie.entitlements;

import java.util.UUID;

/**
 * The client surface every service should use for entitlement checks against subscription-service.
 * See the module design doc §3.2/§0 rule for the fail-open/fail-closed split this interface encodes:
 * a subscription-service outage must never lock a paying customer out of their own data, but must
 * never silently let a quota-consuming write through either.
 *
 * <p>Phase 3 §1: {@code requireFeature} and {@code requireQuota} replace what used to be a single
 * ambiguous {@code require(orgId, key)} — the caller always knows, at the call site, whether it's
 * checking a boolean plan capability or a numeric limit, and that distinction is exactly what decides
 * the HTTP status (403 vs 409) the Gateway shows the customer. Baking it into two methods instead of
 * one flag makes the wrong choice a compile error at every call site rather than a runtime guess.
 */
public interface Entitlements {

    /**
     * Throws {@link EntitlementDeniedException} ({@link EntitlementDeniedException.Kind#FEATURE}) if
     * the boolean plan capability {@code key} is off for {@code orgId} (e.g. {@code video.enabled}).
     * FAILS CLOSED: if subscription-service can't be reached, this denies rather than silently
     * allowing the feature.
     */
    void requireFeature(UUID orgId, String key);

    /**
     * Throws {@link EntitlementDeniedException} ({@link EntitlementDeniedException.Kind#QUOTA}) if
     * {@code currentUsage + 1} would exceed the resolved limit for the numeric key {@code key} (e.g.
     * {@code seats.max}). The caller supplies {@code currentUsage} — only the caller's own service
     * knows the real current count (member rows, storage bytes, project rows...); this method's job is
     * only to resolve the limit and do the comparison consistently, and to shape the denial the same
     * way every other entitlement denial is shaped. FAILS CLOSED, same reasoning as the outage case
     * everywhere else in this interface.
     */
    void requireQuota(UUID orgId, String key, long currentUsage);

    /**
     * Throws {@link EntitlementDeniedException} ({@link EntitlementDeniedException.Kind#QUOTA}) if
     * {@code totalUsageAfter} exceeds the resolved limit for {@code key}. For quotas whose unit isn't
     * "one item at a time" — {@link #requireQuota}'s implicit {@code currentUsage + 1} fits seats or
     * guests, but not e.g. {@code storage.total.gb}, where a single file can push usage up by a large,
     * variable amount in one step. The caller computes the exact prospective total itself (current
     * usage plus whatever this operation would add) rather than this method inferring "+1". FAILS
     * CLOSED, same reasoning as {@link #requireQuota}.
     */
    void requireQuotaTotal(UUID orgId, String key, long totalUsageAfter);

    /**
     * The resolved numeric limit for {@code key} ({@code Long.MAX_VALUE} when unlimited). Use for
     * read-ish display (a "3 of 5 seats used" progress bar). FAILS OPEN: returns
     * {@code Long.MAX_VALUE} if subscription-service can't be reached — never blocks a plain read on
     * a billing outage.
     */
    long limit(UUID orgId, String key);

    /**
     * Throws {@link EntitlementDeniedException} ({@link EntitlementDeniedException.Kind#ACCESS}) when
     * the org's subscription is {@code READ_ONLY} (suspended/expired). FAILS CLOSED, same reasoning as
     * {@link #requireFeature}/{@link #requireQuota}. For backend paths reachable without going through
     * the Gateway's own suspension filter (Phase 3 §2) — event consumers, jobs, internal RPC — never
     * for command handlers reachable only through the Gateway, which is the one choke point.
     */
    void requireWriteAccess(UUID orgId);

    /**
     * The org's current {@code access_mode} ({@code FULL}/{@code READ_ONLY}), for the Gateway's
     * suspension filter (Phase 3 §2) to read without throwing. FAILS OPEN to {@code FULL} on an
     * outage — matching {@link #limit}'s own reasoning: a subscription-service outage must never turn
     * into every org in the product going read-only at once.
     */
    AccessMode accessMode(UUID orgId);

    enum AccessMode { FULL, READ_ONLY }
}
