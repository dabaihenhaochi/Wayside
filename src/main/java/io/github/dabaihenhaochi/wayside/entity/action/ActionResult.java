package io.github.dabaihenhaochi.wayside.entity.action;

/** One tick's observable outcome of an action (or task). */
public record ActionResult(Status status, Failure failure) {
    public enum Status { RUNNING, SUCCESS, FAILED }

    public enum Failure {
        NONE, INVALID_TARGET, UNREACHABLE, TIMED_OUT,
        FORBIDDEN, NO_SPACE, NO_RECIPE, WORLD_CHANGED
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
