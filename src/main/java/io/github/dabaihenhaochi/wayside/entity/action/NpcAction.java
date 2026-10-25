package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;

// 可复用的逐 tick 动作，后续任务由上层安排。
public interface NpcAction {
    ActionResult tick(NpcEntity npc);

    /** 完成、失败或中断时清理临时状态。 */
    default void stop(NpcEntity npc) {
    }
}
