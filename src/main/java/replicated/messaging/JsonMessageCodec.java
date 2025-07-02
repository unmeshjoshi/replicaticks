package replicated.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonMessageCodec implements MessageCodec {
    
    private final ObjectMapper objectMapper;
    
    public JsonMessageCodec() {
        this.objectMapper = createConfiguredObjectMapper();
    }
    
    /**
     * Creates an ObjectMapper for serializing payload records.
     * Jackson automatically handles byte[] fields as Base64 in JSON.
     */
    public static ObjectMapper createConfiguredObjectMapper() {
        return new ObjectMapper();
    }
    
    @Override
    public byte[] encode(Message message) {
        if (message == null) {
            throw new RuntimeException("Cannot encode null message");
        }
        try {
            return objectMapper.writeValueAsBytes(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode message", e);
        }
    }
    
    @Override
    public Message decode(byte[] data) {
        try {
            return objectMapper.readValue(data, Message.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode message", e);
        }
    }
} 