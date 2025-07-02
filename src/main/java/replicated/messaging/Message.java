package replicated.messaging;

import java.util.Objects;

public record Message(
    NetworkAddress source,
    NetworkAddress destination,
    MessageType messageType,
    byte[] payload
) {
    public Message {
        Objects.requireNonNull(source, "Source address cannot be null");
        Objects.requireNonNull(destination, "Destination address cannot be null");
        Objects.requireNonNull(messageType, "Message type cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
    }
} 