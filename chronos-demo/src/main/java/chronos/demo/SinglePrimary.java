package chronos.demo;

import chronos.sdk.Invariant;
import chronos.sdk.WorldView;

import java.util.List;
import java.util.Optional;

/** "At most one primary at any time" (Section 6). Reads volatile.role of every listed node through the recording accessor. */
public final class SinglePrimary implements Invariant {
    private final List<String> roleNodes;

    public SinglePrimary(List<String> roleNodes) {
        this.roleNodes = List.copyOf(roleNodes);
    }

    @Override
    public String name() {
        return "single-primary";
    }

    @Override
    public Optional<String> check(WorldView view) {
        int primaries = 0;
        StringBuilder who = new StringBuilder();
        for (String n : roleNodes) {
            if ("PRIMARY".equals(view.read(n, "volatile.role"))) {
                primaries++;
                who.append(who.length() == 0 ? "" : ", ").append(n);
            }
        }
        return primaries > 1 ? Optional.of("split brain: " + primaries + " primaries (" + who + ")") : Optional.empty();
    }
}
