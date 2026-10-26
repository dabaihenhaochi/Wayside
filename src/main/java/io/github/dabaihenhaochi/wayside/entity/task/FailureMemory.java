package io.github.dabaihenhaochi.wayside.entity.task;

import java.util.HashMap;
import java.util.Map;

// 由任务持有的失败冷却记录，避免反复尝试同一目标。
public final class FailureMemory<K> {
    private final Map<K, Long> retryAfter = new HashMap<>();

    // 清理过期记录，并设置目标下次可重试的时间。
    public void mark(K key, long now, long cooldownTicks) {
        retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
        retryAfter.put(key, now + cooldownTicks);
    }

    // 查询冷却状态，同时移除该目标的过期记录。
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
