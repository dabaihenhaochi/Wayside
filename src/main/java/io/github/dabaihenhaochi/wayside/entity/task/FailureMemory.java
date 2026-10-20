package io.github.dabaihenhaochi.wayside.entity.task;

import java.util.HashMap;
import java.util.Map;

/** Short-lived retry suppression, owned by the task rather than an action. */
public final class FailureMemory<K> {
    private final Map<K, Long> retryAfter = new HashMap<>();

    public void mark(K key, long now, long cooldownTicks) {
        retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
        retryAfter.put(key, now + cooldownTicks);
    }

    public boolean isCoolingDown(K key, long now) {
        Long until = retryAfter.get(key);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            retryAfter.remove(key);
            return false;
        }
        return true;
    }
}
