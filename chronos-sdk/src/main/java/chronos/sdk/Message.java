package chronos.sdk;

import java.util.Map;

/** A message as a node's handler sees it. {@code id} is the id of the send event (Section 10 rule 2). */
public record Message(String id, String from, String type, Map<String, Object> body) {}
