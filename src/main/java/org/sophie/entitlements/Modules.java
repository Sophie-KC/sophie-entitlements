package org.sophie.entitlements;

import java.util.Set;
import java.util.UUID;

/**
 * Org module switches (claude/org-modules-design.md): which of chat, tasks, docs, calendar and apps an
 * org admin has left on. Owned by org-service ({@code GetOrgModules}); this is the client every
 * module-owning service calls at the same entry points where it already resolves a resource's org.
 *
 * <p><b>Fails OPEN</b>, unlike {@link Entitlements#requireFeature}: a module switch is a workspace
 * preference, not something anyone paid for, so an org-service outage must never take every module
 * down with it. Switching a module off hides and refuses it — it never deletes anything — so a brief
 * window where an off module is reachable is harmless.
 *
 * <p>Plan gating stays where it already is: calendar-service keeps its own
 * {@code requireFeature("calendar.enabled")} next to {@code requireEnabled(org, CALENDAR)}.
 */
public interface Modules {

    String CHAT = "chat";
    String TASKS = "tasks";
    String DOCS = "docs";
    String CALENDAR = "calendar";
    String APPS = "apps";

    /** Every switchable module, in display order. */
    Set<String> ALL = Set.of(CHAT, TASKS, DOCS, CALENDAR, APPS);

    /**
     * Throws {@link EntitlementDeniedException} ({@link EntitlementDeniedException.Kind#MODULE}) if the
     * org has switched {@code moduleKey} off. A null {@code orgId} is a no-op: the caller has no org to
     * check against, and refusing would turn a missing-org bug into a module outage.
     */
    void requireEnabled(UUID orgId, String moduleKey);

    /** True unless the org has switched {@code moduleKey} off. Fails open. */
    boolean isEnabled(UUID orgId, String moduleKey);
}
