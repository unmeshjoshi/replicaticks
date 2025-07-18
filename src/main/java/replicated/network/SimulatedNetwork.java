package replicated.network;

import replicated.messaging.Message;
import replicated.messaging.NetworkAddress;
import replicated.util.DebugConfig;

import java.nio.channels.SocketChannel;
import java.util.*;

/**
 * Simulated network implementation that provides deterministic message delivery
 * with configurable delays and packet loss for testing distributed systems.
 * 
 * This implementation follows the Service Layer reactive tick() pattern:
 * - send() queues messages for future delivery
 * - tick() processes queued messages and makes them available for receive()
 * - receive() retrieves messages that have been delivered
 */
public class SimulatedNetwork implements Network {
    
    private final Random random;
    private final int defaultDelayTicks;
    private final double defaultPacketLossRate;
    
    private final PriorityQueue<QueuedMessage> pendingMessages = new PriorityQueue<>();
    private final Map<NetworkAddress, List<Message>> deliveredMessages = new HashMap<>();
    private final Map<Message, MessageContext> messageContexts = new HashMap<>();
    private MessageCallback messageCallback;
    
    // Internal counter for delivery timing (TigerBeetle pattern)
    private long currentTick = 0;
    
    // Network partitioning state
    private final Set<NetworkLink> partitionedLinks = new HashSet<>();
    
    // Per-link configuration
    private final Map<NetworkLink, Integer> linkDelays = new HashMap<>();
    private final Map<NetworkLink, Double> linkPacketLoss = new HashMap<>();
    
    // Connection establishment state
    private int nextEphemeralPort = 50000; // Start ephemeral ports at 50000
    
    private Message lastDeliveredMessage;
    
    /**
     * Creates a SimulatedNetwork with no delays and no packet loss.
     * 
     * @param random seeded random generator for deterministic behavior
     */
    public SimulatedNetwork(Random random) {
        this(random, 0, 0.0);
    }
    
    /**
     * Creates a SimulatedNetwork with configurable behavior.
     * 
     * @param random seeded random generator for deterministic behavior
     * @param delayTicks number of ticks to delay message delivery (0 = immediate)
     * @param packetLossRate probability [0.0-1.0] that a message will be lost
     */
    public SimulatedNetwork(Random random, int delayTicks, double packetLossRate) {
        if (random == null) {
            throw new IllegalArgumentException("Random cannot be null");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay ticks cannot be negative");
        }
        if (packetLossRate < 0.0 || packetLossRate > 1.0) {
            throw new IllegalArgumentException("Packet loss rate must be between 0.0 and 1.0");
        }
        
        this.random = random;
        this.defaultDelayTicks = delayTicks;
        this.defaultPacketLossRate = packetLossRate;
    }
    
    @Override
    public void send(Message message) {
        validateMessage(message);
        
        NetworkLink link = linkFrom(message);
        
        if (link.isPartitioned(partitionedLinks)) {
            return; // Message dropped due to partition
        }
        
        if (shouldDropPacket(link)) {
            return; // Message lost due to packet loss
        }
        
        // Use internal counter to calculate delivery tick (TigerBeetle pattern)
        long deliveryTick = calculateDeliveryTick(link, currentTick);
        queueForDelivery(message, deliveryTick);
    }
    

    

    
    @Override
    public void sendOnChannel(SocketChannel channel, Message message) {
        // TigerBeetle approach: Simple fallback for simulation
        // In SimulatedNetwork, we don't have real SocketChannels, so we fall back to normal send
        // Correlation IDs handle all the routing complexity - no address manipulation needed
        send(message);
    }
    
    /**
     * Advances the simulation by one tick and processes network operations.
     * 
     * This method implements the reactive Service Layer tick() pattern:
     * - It is called by the simulation loop to advance network state
     * - It processes I/O operations that have just completed (message deliveries)
     * - It does NOT initiate new actions (that's the Application Layer's role)
     * 
     * Timing Mechanics:
     * 1. Increments internal currentTick counter
     * 2. Processes all messages whose deliveryTick <= currentTick
     * 3. Moves processed messages from pendingMessages to deliveredMessages
     * 4. Messages become available via receive() after processing
     * 
     * Deterministic Behavior:
     * - Messages are processed in FIFO order from the pending queue
     * - Delivery timing is exact: message sent at tick T with delay D 
     *   becomes available at tick T+D+1 (after this tick() call)
     * - Multiple calls to tick() with same state produce identical results
     * 
     * Example Timeline:
     * Tick 0: send(message) with 2-tick delay -> queued for delivery at tick 2
     * Tick 1: tick() -> currentTick = 1, message not delivered (1 < 2)  
     * Tick 2: tick() -> currentTick = 2, message delivered (2 <= 2)
     * Tick 2: receive() -> returns the delivered message
     * 
     * This method must be called by the simulation master thread in the correct
     * order relative to other components' tick() methods to maintain determinism.
     */
    @Override
    public void tick() {
        currentTick++; // Increment internal counter (TigerBeetle pattern)
        deliverPendingMessagesFor(currentTick);
    }
    
    // Domain-focused helper methods for message sending
    
    private void validateMessage(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
    }
    
    private NetworkLink linkFrom(Message message) {
        // TigerBeetle approach: Handle null source addresses gracefully
        if (message.source() == null) {
            // For client messages, use destination-only routing
            // This allows network partitioning and packet loss to work correctly
            // without requiring fake client addresses
            return new NetworkLink(message.destination(), message.destination());
        }
        
        // For server messages, use normal source->destination routing
        return new NetworkLink(message.source(), message.destination());
    }
    
    private boolean shouldDropPacket(NetworkLink link) {
        double effectivePacketLoss = linkPacketLoss.getOrDefault(link, defaultPacketLossRate);
        return effectivePacketLoss > 0.0 && random.nextDouble() < effectivePacketLoss;
    }
    
    private long calculateDeliveryTick(NetworkLink link, long currentTick) {
        int effectiveDelay = linkDelays.getOrDefault(link, defaultDelayTicks);
        return currentTick + effectiveDelay;
    }
    
    private void queueForDelivery(Message message, long deliveryTick) {
        pendingMessages.offer(new QueuedMessage(message, deliveryTick, currentTick));
    }
    
    private void deliverPendingMessagesFor(long tickTime) {
        // Process messages whose delivery time has now arrived
        // PriorityQueue ensures we process in delivery time order
        while (!pendingMessages.isEmpty() && 
               pendingMessages.peek().deliveryTick <= tickTime) {
            
            QueuedMessage queuedMessage = pendingMessages.poll();
            Message message = queuedMessage.message;
            MessageContext context = new MessageContext(message);
            
            messageContexts.put(message, context);
            lastDeliveredMessage = message;
            
            // Call registered callback for push-based delivery
            if (messageCallback != null) {
                messageCallback.onMessage(message, context);
            }
            
            if (DebugConfig.ENABLED) {
                System.out.println("SimNet: delivered " + message);
            }
        }
    }
    
    // Network Partitioning Implementation
    
    @Override
    public void partition(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }
        
        // Add both directions of the link
        partitionedLinks.add(new NetworkLink(source, destination));
        partitionedLinks.add(new NetworkLink(destination, source));
    }
    
    @Override
    public void partitionOneWay(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }
        
        // Add only the specified direction
        partitionedLinks.add(new NetworkLink(source, destination));
    }
    
    @Override
    public void healPartition(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }
        
        // Remove both directions
        partitionedLinks.remove(new NetworkLink(source, destination));
        partitionedLinks.remove(new NetworkLink(destination, source));
    }
    
    @Override
    public void setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay ticks cannot be negative");
        }
        
        linkDelays.put(new NetworkLink(source, destination), delayTicks);
    }
    
    @Override
    public void setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("Packet loss rate must be between 0.0 and 1.0");
        }
        
        linkPacketLoss.put(new NetworkLink(source, destination), lossRate);
    }
    

    
    /**
     * Returns the most recently delivered message (for testing).
     */
    public Message getLastDeliveredMessage() {
        return lastDeliveredMessage;
    }
    
    @Override
    public void registerMessageHandler(MessageCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("MessageCallback cannot be null");
        }
        this.messageCallback = callback;
    }
    
    /**
     * Internal record to track messages with their delivery timing.
     * Implements Comparable to allow PriorityQueue ordering by delivery time.
     */
    private record QueuedMessage(Message message, long deliveryTick, long sequenceNumber) 
            implements Comparable<QueuedMessage> {
        
        @Override
        public int compareTo(QueuedMessage other) {
            // Primary ordering: delivery tick (earlier messages first)
            int tickComparison = Long.compare(this.deliveryTick, other.deliveryTick);
            if (tickComparison != 0) {
                return tickComparison;
            }
            
            // Secondary ordering: sequence number for FIFO behavior when delivery ticks are equal
            return Long.compare(this.sequenceNumber, other.sequenceNumber);
        }
    }
    
    /**
     * Internal record to represent a directed network link between two addresses.
     * Used as a key for tracking partitions and per-link configurations.
     */
    private record NetworkLink(NetworkAddress source, NetworkAddress destination) {
        
        /**
         * Checks if this link is partitioned (blocked).
         * A link is partitioned if it exists in the partitioned links set.
         * 
         * @param partitionedLinks the set of currently partitioned links
         * @return true if this link is partitioned, false otherwise
         */
        boolean isPartitioned(Set<NetworkLink> partitionedLinks) {
            return partitionedLinks.contains(this);
        }
    }
} 