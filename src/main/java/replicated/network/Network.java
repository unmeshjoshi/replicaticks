package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.NetworkAddress;

import java.io.IOException;
import java.nio.channels.SocketChannel;

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
     * Processes pending network operations in the simulation tick.
     * This is the reactive Service Layer tick() - it processes I/O operations
     * that have just completed (message deliveries, timeouts, etc.).
     * 
     * Should be called by the simulation loop to advance network state.
     * Network implementations manage their own internal tick counters for timing.
     */
    void tick();
    

    
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
    
    // --- Direct Channel Response Support (Phase 11 Fixes) ---
    /**
     * Sends a message directly on the provided <i>already-connected</i> SocketChannel.
     * This allows servers to route a response back on the exact TCP connection on which
     * the request was received, avoiding the overhead of opening a new connection and
     * preserving message ordering. Default implementation throws {@link
     * UnsupportedOperationException}; network implementations that support real sockets
     * (e.g. {@code NioNetwork}) should override it.
     */
    default void sendOnChannel(SocketChannel channel, Message message) {
        throw new UnsupportedOperationException("Direct channel send is not supported by this Network implementation");
    }
    

    /**
     * Registers a single callback handler for push-based message delivery.
     * 
     * The Network implementation will call the registered callback's onMessage() method
     * during its tick() cycle when messages are ready for delivery. This eliminates
     * the need for polling-based receive() calls.
     * 
     * Only one callback can be registered at a time. Subsequent calls will replace
     * the previous callback.
     * 
     * @param callback The MessageCallback to be invoked when messages are ready
     */
    void registerMessageHandler(MessageCallback callback);
}