package replicated.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;

public final class JsonMessageCodec implements MessageCodec {
    
    private final ObjectMapper objectMapper;
    
    public JsonMessageCodec() {
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public byte[] encode(Message message) {
        try {
            // Convert payload to Base64 for JSON compatibility
            String base64Payload = Base64.getEncoder().encodeToString(message.payload());
            
            // Create a JSON-friendly representation
            MessageDto dto = new MessageDto(
                message.source().ipAddress(),
                message.source().port(),
                message.destination().ipAddress(),
                message.destination().port(),
                message.messageType().name(),
                base64Payload
            );
            
            return objectMapper.writeValueAsBytes(dto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode message", e);
        }
    }
    
    @Override
    public Message decode(byte[] data) {
        try {
            MessageDto dto = objectMapper.readValue(data, MessageDto.class);
            
            NetworkAddress source = new NetworkAddress(dto.sourceIp(), dto.sourcePort());
            NetworkAddress destination = new NetworkAddress(dto.destinationIp(), dto.destinationPort());
            MessageType messageType = MessageType.valueOf(dto.messageType());
            byte[] payload = Base64.getDecoder().decode(dto.payload());
            
            return new Message(source, destination, messageType, payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode message", e);
        }
    }
    
    // Internal DTO for JSON serialization
    private record MessageDto(
        String sourceIp,
        int sourcePort,
        String destinationIp,
        int destinationPort,
        String messageType,
        String payload
    ) {}
} 