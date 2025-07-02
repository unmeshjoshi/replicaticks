package replicated.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.util.Base64;
import java.io.IOException;

public final class JsonMessageCodec implements MessageCodec {
    
    private final ObjectMapper objectMapper;
    
    public JsonMessageCodec() {
        this.objectMapper = createConfiguredObjectMapper();
    }
    
    /**
     * Creates an ObjectMapper configured to handle byte[] fields as Base64.
     * This can be used for serializing payload records consistently.
     */
    public static ObjectMapper createConfiguredObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Configure ObjectMapper to handle byte[] as Base64
        SimpleModule module = new SimpleModule();
        module.addSerializer(byte[].class, new ByteArraySerializer());
        module.addDeserializer(byte[].class, new ByteArrayDeserializer());
        mapper.registerModule(module);
        
        return mapper;
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
    
    // Custom serializer for byte[] to Base64
    private static class ByteArraySerializer extends JsonSerializer<byte[]> {
        @Override
        public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers) 
                throws IOException {
            gen.writeString(Base64.getEncoder().encodeToString(value));
        }
    }
    
    // Custom deserializer for byte[] from Base64
    private static class ByteArrayDeserializer extends JsonDeserializer<byte[]> {
        @Override
        public byte[] deserialize(JsonParser p, DeserializationContext ctxt) 
                throws IOException {
            return Base64.getDecoder().decode(p.getValueAsString());
        }
    }
} 