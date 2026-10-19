package chronos.analysis;

import chronos.model.Event;
import chronos.model.EventSink;

import java.util.List;

/** The live causal graph as a recorder sink (Section 13: "the graph subscribes to the recorder"). */
public final class GraphSink implements EventSink {
    private final CausalGraph graph;

    public GraphSink(CausalGraph graph) {
        this.graph = graph;
    }

    @Override
    public void append(List<Event> group) {
        for (Event e : group) graph.append(e);
    }
}
