package chronos.sdk;

import java.util.function.Supplier;

/** A node as a scenario declares it: a name and a factory. Declared order is the vector-clock slot order (D-07). */
public record NodeSpec(String name, Supplier<Node> factory) {
    public NodeSpec {
        if (name == null || name.isBlank() || name.equals("env") || name.contains("#") || name.contains(":"))
            throw new IllegalArgumentException("invalid node name: " + name);
    }
}
