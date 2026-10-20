package io.github.dabaihenhaochi.wayside.entity.action;

import io.github.dabaihenhaochi.wayside.entity.NpcEntity;

/** A reusable, tick-driven operation. Actions do not select the next task. */
public interface NpcAction {
    ActionResult tick(NpcEntity npc);

    /** Called on completion, failure and interruption to release transient state. */
    default void stop(NpcEntity npc) {
    }
}
