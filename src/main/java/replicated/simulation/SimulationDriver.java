package replicated.simulation;

import replicated.client.QuorumClient;
import replicated.messaging.MessageBus;
import replicated.network.Network;
import replicated.replica.Replica;
import replicated.storage.Storage;

import java.util.List;

/**
 * SimulationDriver orchestrates ticking all simulation components in deterministic order.
 * It advances the simulation clock and ensures all components progress together.
 */
public class SimulationDriver {
    private final List<Network> networks;
    private final List<Storage> storages;
    private final List<Replica> replicas;
    private final List<QuorumClient> quorumClients;
    private final List<MessageBus> messageBuses;

    /**
     * Constructs a SimulationDriver with the given component lists.
     * @param networks List of network components
     * @param storages List of storage components
     * @param replicas List of replica components
     * @param quorumClients List of client components
     * @param messageBuses List of message bus components
     */
    public SimulationDriver(List<Network> networks, List<Storage> storages, List<Replica> replicas, List<QuorumClient> quorumClients, List<MessageBus> messageBuses) {
        this.networks = networks;
        this.storages = storages;
        this.replicas = replicas;
        this.quorumClients = quorumClients;
        this.messageBuses = messageBuses;
    }

    /**
     * Advances the simulation by one tick, calling tick() on all components in deterministic order.
     * 
     * Order follows TigerBeetle pattern:
     * 1. Clients (application layer - proactive work)
     * 2. Replicas (application layer - proactive work) 
     * 3. Networks (service layer - deliver messages from previous sends)
     * 4. MessageBuses (service layer - route delivered messages to handlers)
     * 5. Storage (service layer - reactive I/O processing)
     */
    public void tick() {
        // 1. Application Layer - Proactive work (clients and replicas)
        quorumClients.forEach(QuorumClient::tick);
        replicas.forEach(Replica::tick);
        
        // 2. Service Layer - Deliver messages, then route them, then process storage I/O
        networks.forEach(Network::tick);
        messageBuses.forEach(MessageBus::tick);
        storages.forEach(Storage::tick);
    }

    /**
     * Runs the simulation for the specified number of ticks.
     * @param maxTicks Number of ticks to run
     */
    public void runSimulation(int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            ticks++;
            tick();
        }
    }

    long ticks = 0;
    public long getTicks() {
        return ticks;
    }
}