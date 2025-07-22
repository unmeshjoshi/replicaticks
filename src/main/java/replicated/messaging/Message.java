package replicated.messaging;

import replicated.network.id.ProcessId;

import java.util.Arrays;
import java.util.Objects;

public record Message(
        NetworkAddress source,
        NetworkAddress destination,
        MessageType messageType,
        byte[] payload,
        String correlationId,

        //New for migrating to Process Id based routing.
        //while the migration is in progress, this is defaulted to null.
        ProcessId sourceProcess,
        ProcessId destinationProcess

) {

    // FACTORY METHODS - only way to create Message instances
    public static Message networkMessage(ProcessId source, ProcessId destination,
                                         MessageType messageType, byte[] payload, String correlationId) {
        return new Message(null, null, messageType, payload, correlationId, source, destination);
    }

    /**
     * Creates a message with NetworkAddress source and destination.
     * Legacy method for backward compatibility.
     */
    public static Message networkMessage(NetworkAddress source, NetworkAddress destination,
                                         MessageType messageType, byte[] payload, String correlationId) {
        return new Message(source, destination, messageType, payload, correlationId, null, null);
    }
    
    /**
     * Creates a message with a null source address.
     * This is used when the sender doesn't need to bind to a specific address.
     * 
     * @param destination the destination address
     * @param messageType the message type (must be a client or system message)
     * @param payload the message payload
     * @param correlationId the correlation ID
     * @return a new Message with null source address
     * @throws IllegalArgumentException if messageType is not a client or system message
     */
    public static Message unboundMessage(NetworkAddress destination, MessageType messageType, byte[] payload, String correlationId) {
        if (!messageType.isClientMessage() && !messageType.isSystemMessage()) {
            throw new IllegalArgumentException("Message type must be a client or system message, but was: " + messageType);
        }
        return new Message(null, destination, messageType, payload, correlationId, null, null);
    }
    
    /**
     * Creates a client-originated message with a null source address.
     * This is used when clients don't need to bind to a specific address.
     * 
     * @param destination the destination address
     * @param messageType the message type (must be a client message)
     * @param payload the message payload
     * @param correlationId the correlation ID
     * @return a new Message with null source address
     * @throws IllegalArgumentException if messageType is not a client message
     */
    public static Message clientMessage(NetworkAddress destination, MessageType messageType, byte[] payload, String correlationId) {
        if (!messageType.isClientMessage()) {
            throw new IllegalArgumentException("Message type must be a client message, but was: " + messageType);
        }
        return unboundMessage(destination, messageType, payload, correlationId);
    }
    public Message {
        // Validate source address - allow null for client and system messages
        if (source == null && !messageType.isClientMessage() && !messageType.isSystemMessage()) {
            throw new NullPointerException("Source address cannot be null (except for client and system messages)");
        }

        // Validate destination address - allow null for client response messages (uses channel-based routing)
        if (destination == null && !messageType.isClientMessage() || 
            (destination == null && messageType.isClientMessage() && !messageType.isResponse())) {
            throw new NullPointerException("Destination address cannot be null (except for client response messages)");
        }
        
        Objects.requireNonNull(messageType, "Message type cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(correlationId, "Correlation ID cannot be null");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Message message = (Message) obj;
        return Objects.equals(source, message.source) &&
               Objects.equals(destination, message.destination) &&
               messageType == message.messageType &&
               Arrays.equals(payload, message.payload) &&
               Objects.equals(correlationId, message.correlationId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(source, destination, messageType, Arrays.hashCode(payload), correlationId);
    }
} 