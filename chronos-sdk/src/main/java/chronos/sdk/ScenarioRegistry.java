package chronos.sdk;

import java.util.List;
import java.util.Map;

/**
 * Scenario registration contract (D-22). Scenarios are looked up by name with parameters; the demo module provides the
 * implementation and the CLI wires it into replay, branching and comparison.
 */
public interface ScenarioRegistry {
    List<String> names();

    /** Creates the named scenario; unknown names raise IllegalArgumentException. */
    Scenario create(String name, Map<String, String> params);
}
