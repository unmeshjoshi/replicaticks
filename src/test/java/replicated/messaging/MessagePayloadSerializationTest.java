package replicated.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import replicated.storage.VersionedValue;

import static org.junit.jupiter.api.Assertions.*;

class MessagePayloadSerializationTest {

    private final MessageCodec messageCodec = new JsonMessageCodec();
    private final ObjectMapper jsonMapper = JsonMessageCodec.createConfiguredObjectMapper();
    private final NetworkAddress client = new NetworkAddress("192.168.1.100", 5000);
    private final NetworkAddress replica = new NetworkAddress("192.168.1.10", 8080);

    @Test
    void shouldSerializeGetRequestAsMessagePayload() throws Exception {
        // Given - Create GetRequest and serialize it as JSON for payload
        GetRequest getRequest = new GetRequest("user:123");
        byte[] payload = jsonMapper.writeValueAsBytes(getRequest);
        Message message = new Message(client, replica, MessageType.CLIENT_GET_REQUEST, payload, "test-correlation-id");
        
        // When - Serialize and deserialize the entire message
        byte[] encodedMessage = messageCodec.encode(message);
        Message decodedMessage = messageCodec.decode(encodedMessage);
        
        // Then - Extract and deserialize the payload back to GetRequest
        GetRequest decodedRequest = jsonMapper.readValue(decodedMessage.payload(), GetRequest.class);
        
        assertEquals(message, decodedMessage);
        assertEquals(getRequest, decodedRequest);
        assertEquals("user:123", decodedRequest.key());
    }
    
    @Test
    void shouldSerializeSetRequestAsMessagePayload() throws Exception {
        // Given
        SetRequest setRequest = new SetRequest("user:123", "John Doe".getBytes());
        byte[] payload = jsonMapper.writeValueAsBytes(setRequest);
        Message message = new Message(client, replica, MessageType.CLIENT_SET_REQUEST, payload, "test-correlation-id-1");
        
        // When
        byte[] encodedMessage = messageCodec.encode(message);
        Message decodedMessage = messageCodec.decode(encodedMessage);
        
        // Then
        SetRequest decodedRequest = jsonMapper.readValue(decodedMessage.payload(), SetRequest.class);
        
        assertEquals(message, decodedMessage);
        assertEquals(setRequest, decodedRequest);
        assertEquals("user:123", decodedRequest.key());
        assertArrayEquals("John Doe".getBytes(), decodedRequest.value());
    }
    
    @Test
    void shouldSerializeGetResponseAsMessagePayload() throws Exception {
        // Given
        VersionedValue value = new VersionedValue("John Doe".getBytes(), 1L);
        GetResponse getResponse = new GetResponse("user:123", value);
        byte[] payload = jsonMapper.writeValueAsBytes(getResponse);
        Message message = new Message(replica, client, MessageType.CLIENT_RESPONSE, payload, "test-correlation-id-2");
        
        // When
        byte[] encodedMessage = messageCodec.encode(message);
        Message decodedMessage = messageCodec.decode(encodedMessage);
        
        // Then
        GetResponse decodedResponse = jsonMapper.readValue(decodedMessage.payload(), GetResponse.class);
        
        assertEquals(message, decodedMessage);
        assertEquals(getResponse, decodedResponse);
        assertEquals("user:123", decodedResponse.key());
        assertEquals(value, decodedResponse.value());
        assertNotNull(decodedResponse.value());
    }
    
    @Test
    void shouldDemonstrateTypeSafeMessaging() {
        // Given - Client creates a get request
        GetRequest request = new GetRequest("user:123");
        
        // When - Convert to Message for network transmission
        Message requestMessage = createClientMessage(
            client, replica, MessageType.CLIENT_GET_REQUEST, request);
        
        // Then - Verify message structure
        assertEquals(client, requestMessage.source());
        assertEquals(replica, requestMessage.destination());
        assertEquals(MessageType.CLIENT_GET_REQUEST, requestMessage.messageType());
        
        // And - Server can extract the typed request
        GetRequest extractedRequest = extractPayload(requestMessage, GetRequest.class);
        assertEquals("user:123", extractedRequest.key());
    }
    
    // Helper methods to demonstrate the pattern
    private <T> Message createClientMessage(NetworkAddress source, NetworkAddress dest, 
                                           MessageType type, T payload) {
        try {
            byte[] payloadBytes = jsonMapper.writeValueAsBytes(payload);
            return new Message(source, dest, type, payloadBytes, "test-correlation-id");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create message", e);
        }
    }
    
    private <T> T extractPayload(Message message, Class<T> payloadType) {
        try {
            return jsonMapper.readValue(message.payload(), payloadType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract payload", e);
        }
    }
} 