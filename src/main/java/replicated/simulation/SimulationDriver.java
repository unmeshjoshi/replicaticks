package replicated.simulation;

import replicated.client.Client;
import replicated.messaging.BaseMessageBus;
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
    private final List<Client> clients;
    private final List<BaseMessageBus> messageBuses;

    /**
     * Constructs a SimulationDriver with the given component lists.
     * @param networks List of network components
     * @param storages List of storage components
     * @param replicas List of replica components
     * @param clients List of client components
     * @param messageBuses List of message bus components
     */
    public SimulationDriver(List<Network> networks, List<Storage> storages, List<Replica> replicas, List<Client> clients, List<BaseMessageBus> messageBuses) {
        this.networks = networks;
        this.storages = storages;
        this.replicas = replicas;
        this.clients = clients;
        this.messageBuses = messageBuses;
    }

    /**
     * Advances the simulation by one tick, calling tick() on all components in deterministic order.
     * 
     * Order follows TigerBeetle pattern:
     * 1. Clients (application layer - proactive work)
     * 2. Replicas (application layer - proactive work) 
     * 3. MessageBuses (service layer - message routing)
     * 4. Networks (service layer - reactive I/O processing)
     * 5. Storage (service layer - reactive I/O processing)
     */
    public void tick() {
        // 1. Application Layer - Proactive work (clients and replicas)
        clients.forEach(Client::tick);
        replicas.forEach(Replica::tick);
        
        // 2. Service Layer - Message routing and I/O processing
        messageBuses.forEach(BaseMessageBus::tick);
        networks.forEach(Network::tick);
        storages.forEach(Storage::tick);
    }

    /**
     * Runs the simulation for the specified number of ticks.
     * @param maxTicks Number of ticks to run
     */
    public void runSimulation(int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            tick();
        }
    }
} 