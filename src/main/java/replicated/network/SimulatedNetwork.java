package replicated.network;

import replicated.messaging.NetworkAddress;
import replicated.messaging.Message;
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
    private final int delayTicks;
    private final double packetLossRate;
    
    private final Queue<QueuedMessage> pendingMessages = new LinkedList<>();
    private final Map<NetworkAddress, List<Message>> deliveredMessages = new HashMap<>();
    private long currentTick = 0;
    
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
        this.delayTicks = delayTicks;
        this.packetLossRate = packetLossRate;
    }
    
    @Override
    public void send(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        
        // Check for packet loss before queuing
        if (packetLossRate > 0.0 && random.nextDouble() < packetLossRate) {
            // Message is lost - don't queue it
            return;
        }
        
        long deliveryTick = currentTick + delayTicks;
        pendingMessages.offer(new QueuedMessage(message, deliveryTick));
    }
    
    @Override
    public List<Message> receive(NetworkAddress address) {
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }
        
        List<Message> messages = deliveredMessages.remove(address);
        return messages == null ? List.of() : new ArrayList<>(messages);
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
     * 1. Increments currentTick to advance simulation time
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
        currentTick++;
        
        // Process messages whose delivery time has now arrived
        while (!pendingMessages.isEmpty() && 
               pendingMessages.peek().deliveryTick <= currentTick) {
            
            QueuedMessage queuedMessage = pendingMessages.poll();
            Message message = queuedMessage.message;
            NetworkAddress destination = message.destination();
            
            deliveredMessages.computeIfAbsent(destination, k -> new ArrayList<>()).add(message);
        }
    }
    
    /**
     * Internal record to track messages with their delivery timing.
     */
    private record QueuedMessage(Message message, long deliveryTick) {}
} 