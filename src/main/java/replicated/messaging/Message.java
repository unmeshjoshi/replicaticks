package replicated.messaging;

import java.util.Arrays;
import java.util.Objects;

public record Message(
    NetworkAddress source,
    NetworkAddress destination,
    MessageType messageType,
    byte[] payload,
    String correlationId
) {
    public Message {
        Objects.requireNonNull(source, "Source address cannot be null");
        Objects.requireNonNull(destination, "Destination address cannot be null");
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