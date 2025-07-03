package replicated.network;

import replicated.messaging.NetworkAddress;
import replicated.messaging.Message;
import java.util.List;

/**
 * Network interface for sending and receiving messages in the distributed simulation.
 * 
 * This interface follows the deterministic simulation principles:
 * - send() queues messages for delivery (may be delayed/dropped in simulation)
 * - receive() retrieves available messages for a given address
 * - tick() processes pending network operations (reactive Service Layer role)
 */
public interface Network {
    
    /**
     * Sends a message through the network. The message will be delivered to the
     * destination address according to the network's delivery characteristics
     * (delays, failures, etc.).
     * 
     * @param message the message to send (must not be null)
     * @throws IllegalArgumentException if message is null
     */
    void send(Message message);
    
    /**
     * Retrieves all available messages for the given network address.
     * This is a non-blocking operation that returns immediately.
     * 
     * @param address the network address to receive messages for (must not be null)
     * @return list of messages available for this address (never null, may be empty)
     * @throws IllegalArgumentException if address is null
     */
    List<Message> receive(NetworkAddress address);
    
    /**
     * Processes pending network operations in the simulation tick.
     * This is the reactive Service Layer tick() - it processes I/O operations
     * that have just completed (message deliveries, timeouts, etc.).
     * 
     * Should be called by the simulation loop to advance network state.
     */
    void tick();
    
    /**
     * Establishes a connection to the destination and returns the actual local address
     * assigned by the network layer. This simulates the OS assigning an ephemeral port.
     * 
     * Implementation behavior:
     * - SimulatedNetwork: Returns a localhost address with simulated ephemeral port
     * - NioNetwork: Establishes actual socket connection and returns OS-assigned local address
     * 
     * @param destination the destination address to connect to
     * @return the actual local address assigned for this connection
     * @throws IllegalArgumentException if destination is null
     */
    NetworkAddress establishConnection(NetworkAddress destination);
    
    // Network Partitioning Methods
    
    /**
     * Creates a bidirectional partition between two network addresses.
     * After partitioning, messages in both directions (source↔destination) will be dropped.
     * 
     * @param source first network address in the partition
     * @param destination second network address in the partition
     * @throws IllegalArgumentException if either address is null
     */
    void partition(NetworkAddress source, NetworkAddress destination);
    
    /**
     * Creates a unidirectional partition from source to destination.
     * Messages from source to destination will be dropped, but messages from
     * destination to source will continue to work normally.
     * 
     * @param source the network address that cannot send to destination
     * @param destination the network address that source cannot reach
     * @throws IllegalArgumentException if either address is null
     */
    void partitionOneWay(NetworkAddress source, NetworkAddress destination);
    
    /**
     * Heals any partition between two network addresses, restoring connectivity.
     * This heals both bidirectional and unidirectional partitions.
     * 
     * @param source first network address
     * @param destination second network address  
     * @throws IllegalArgumentException if either address is null
     */
    void healPartition(NetworkAddress source, NetworkAddress destination);
    
    // Per-Link Configuration Methods
    
    /**
     * Sets a specific delay for messages from source to destination.
     * This overrides any global delay settings for this specific link.
     * 
     * @param source the sending network address
     * @param destination the receiving network address
     * @param delayTicks number of ticks to delay messages on this link
     * @throws IllegalArgumentException if either address is null or delayTicks is negative
     */
    void setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks);
    
    /**
     * Sets a specific packet loss rate for messages from source to destination.
     * This overrides any global packet loss settings for this specific link.
     * 
     * @param source the sending network address
     * @param destination the receiving network address
     * @param lossRate probability [0.0-1.0] that messages on this link will be lost
     * @throws IllegalArgumentException if either address is null or lossRate is not in [0.0, 1.0]
     */
    void setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate);
} 