package replicated.network;

import java.util.List;

/**
 * Immutable snapshot of network metrics
 */
public record Metrics(int inboundConnections, int outboundConnections, int closedConnections,
                      List<ConnectionStats> connections) {
}
