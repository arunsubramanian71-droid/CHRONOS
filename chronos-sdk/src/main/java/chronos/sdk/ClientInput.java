package chronos.sdk;

import java.util.Map;

/** One scripted external workload item: delivered to {@code node} at virtual time {@code at} as a CLIENT_INPUT event. */
public record ClientInput(long at, String node, Map<String, Object> body) {}
