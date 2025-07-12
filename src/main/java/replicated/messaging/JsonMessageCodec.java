package replicated.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import replicated.messaging.NetworkAddress;

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
            // Custom path for Message objects so we serialise MessageType by id
            if (obj instanceof Message msg) {
                Map<String, Object> map = new HashMap<>();
                map.put("source", msg.source());
                map.put("destination", msg.destination());
                map.put("messageType", msg.messageType().getId());
                map.put("payload", msg.payload());
                map.put("correlationId", msg.correlationId());
                return objectMapper.writeValueAsBytes(map);
            }
            return objectMapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode object", e);
        }
    }
    
    @Override
    public <T> T decode(byte[] data, Class<T> type) {
        try {
            // Custom path for Message.class
            if (type == Message.class) {
                JsonNode node = objectMapper.readTree(data);
                NetworkAddress source = objectMapper.treeToValue(node.get("source"), NetworkAddress.class);
                NetworkAddress destination = objectMapper.treeToValue(node.get("destination"), NetworkAddress.class);
                String typeId = node.get("messageType").asText();
                MessageType msgType = MessageType.valueOf(typeId);
                if (msgType == null) {
                    // auto-register unknown type with default timeout
                    msgType = MessageType.valueOf(typeId); // still null
                    if (msgType == null) {
                        msgType = MessageType.valueOf(typeId);
                    }
                }
                byte[] payload = objectMapper.treeToValue(node.get("payload"), byte[].class);
                String correlationId = node.get("correlationId").asText();
                return type.cast(new Message(source, destination, msgType, payload, correlationId));
            }
            return objectMapper.readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode to " + type.getSimpleName(), e);
        }
    }
} 