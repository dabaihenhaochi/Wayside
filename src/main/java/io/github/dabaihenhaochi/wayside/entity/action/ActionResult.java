package io.github.dabaihenhaochi.wayside.entity.action;

// 表示动作或任务本次更新的状态及失败原因。
public record ActionResult(Status status, Failure failure) {
    public enum Status { RUNNING, SUCCESS, FAILED }

    public enum Failure {
        NONE, UNKNOWN, INVALID_TARGET, UNREACHABLE, TIMED_OUT,
        FORBIDDEN, NO_SPACE, NO_RECIPE, WORLD_CHANGED,
        MISSING_INGREDIENT, MISSING_WORKSTATION
    }

    private static final ActionResult RUNNING = new ActionResult(Status.RUNNING, Failure.NONE);
    private static final ActionResult SUCCESS = new ActionResult(Status.SUCCESS, Failure.NONE);

    public static ActionResult running() { return RUNNING; }
    public static ActionResult success() { return SUCCESS; }
    public static ActionResult failed(Failure failure) {
        if (failure == Failure.NONE) {
            throw new IllegalArgumentException("Failure reason is required");
        }
        return new ActionResult(Status.FAILED, failure);
    }

    public boolean isRunning() { return status == Status.RUNNING; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isFailed() { return status == Status.FAILED; }
}
