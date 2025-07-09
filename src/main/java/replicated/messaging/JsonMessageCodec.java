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
    public byte[] encode(Object obj) {
        if (obj == null) {
            throw new RuntimeException("Cannot encode null object");
        }
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode object", e);
        }
    }
    
    @Override
    public <T> T decode(byte[] data, Class<T> type) {
        try {
            return objectMapper.readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode to " + type.getSimpleName(), e);
        }
    }
} 