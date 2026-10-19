package chronos.sdk;

/** A timer that reached its due time. {@code id} is the id of the TIMER_SET event; {@code name} is the label the node gave it. */
public record TimerFiring(String id, String name) {}
