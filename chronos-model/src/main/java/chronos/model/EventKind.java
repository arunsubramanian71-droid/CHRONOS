package chronos.model;

/** Every kind of event the recorder can write (Section 10, "Event kinds"). */
public enum EventKind {
    NODE_STARTED, NODE_CRASHED, NODE_RESTARTED, NODE_PAUSED, NODE_RESUMED, NODE_FAILED,
    MESSAGE_SENT, MESSAGE_DELIVERED, MESSAGE_DROPPED, MESSAGE_DUPLICATED,
    TIMER_SET, TIMER_CANCELLED, TIMER_FIRED,
    CLIENT_INPUT, RANDOM_DRAWN,
    FAULT_INJECTED, FAULT_CLEARED,
    INVARIANT_VIOLATED, APP_NOTE, INTERVENTION_APPLIED;

    /** True for kinds that start a step (a delivery, a timer firing, a client input, a start, a restart, a crash). */
    public boolean isTrigger() {
        return switch (this) {
            case NODE_STARTED, NODE_RESTARTED, NODE_CRASHED, MESSAGE_DELIVERED, TIMER_FIRED, CLIENT_INPUT -> true;
            default -> false;
        };
    }
}
