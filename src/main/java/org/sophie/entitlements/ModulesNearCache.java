package org.sophie.entitlements;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Per-instance cache of each org's DISABLED module keys — same shape and TTL as
 * {@link EntitlementsNearCache}. Most orgs have nothing disabled, so the common entry is an empty set.
 * {@link CacheInvalidationListener} evicts an org on {@code org.modules_changed}, so a switch normally
 * takes effect within the outbox relay's couple of seconds; the TTL is the outer bound without it.
 */
@Component
class ModulesNearCache {

    private final Cache<UUID, Set<String>> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    Set<String> getIfPresent(UUID orgId) {
        return cache.getIfPresent(orgId);
    }

    void put(UUID orgId, Set<String> disabled) {
        cache.put(orgId, disabled);
    }

    void evict(UUID orgId) {
        cache.invalidate(orgId);
    }

    void flushAll() {
        cache.invalidateAll();
    }
}
