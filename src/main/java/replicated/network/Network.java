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
} 