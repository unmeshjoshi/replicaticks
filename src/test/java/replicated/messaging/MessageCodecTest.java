package replicated.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageCodecTest {

    @Test
    void shouldEncodeAndDecodeMessage() {
        // Given
        MessageCodec codec = new JsonMessageCodec();
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8081);
        MessageType messageType = MessageType.CLIENT_GET_REQUEST;
        byte[] payload = "test payload".getBytes();
        Message originalMessage = new Message(source, destination, messageType, payload, "test-correlation-id");
        
        // When
        byte[] encoded = codec.encode(originalMessage);
        Message decodedMessage = codec.decode(encoded);
        
        // Then
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        assertEquals(originalMessage, decodedMessage);
    }
    
    @Test
    void shouldHandleEmptyPayload() {
        // Given
        MessageCodec codec = new JsonMessageCodec();
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8081);
        MessageType messageType = MessageType.CLIENT_GET_RESPONSE;
        byte[] emptyPayload = new byte[0];
        Message originalMessage = new Message(source, destination, messageType, emptyPayload, "test-correlation-id-1");
        
        // When
        byte[] encoded = codec.encode(originalMessage);
        Message decodedMessage = codec.decode(encoded);
        
        // Then
        assertEquals(originalMessage, decodedMessage);
        assertArrayEquals(emptyPayload, decodedMessage.payload());
    }
    
    @Test
    void shouldHandleBinaryPayload() {
        // Given
        MessageCodec codec = new JsonMessageCodec();
        NetworkAddress source = new NetworkAddress("10.0.0.1", 9000);
        NetworkAddress destination = new NetworkAddress("10.0.0.2", 9001);
        MessageType messageType = MessageType.INTERNAL_SET_REQUEST;
        
        // Create binary payload with various byte values
        byte[] binaryPayload = {0, 1, -1, 127, -128, 50, -50};
        Message originalMessage = new Message(source, destination, messageType, binaryPayload, "test-correlation-id-2");
        
        // When
        byte[] encoded = codec.encode(originalMessage);
        Message decodedMessage = codec.decode(encoded);
        
        // Then
        assertEquals(originalMessage, decodedMessage);
        assertArrayEquals(binaryPayload, decodedMessage.payload());
    }
    
    @Test
    void shouldThrowExceptionForInvalidData() {
        // Given
        MessageCodec codec = new JsonMessageCodec();
        byte[] invalidData = "not valid JSON".getBytes();
        
        // When & Then
        assertThrows(RuntimeException.class, () -> codec.decode(invalidData));
    }
    
    @Test
    void shouldThrowExceptionForNullMessage() {
        // Given
        MessageCodec codec = new JsonMessageCodec();
        
        // When & Then
        assertThrows(RuntimeException.class, () -> codec.encode(null));
    }
    
    @Test
    void shouldProduceReadableJsonOutput() {
        // Given
        MessageCodec codec = new JsonMessageCodec();
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8081);
        MessageType messageType = MessageType.CLIENT_GET_REQUEST;
        byte[] payload = "hello".getBytes();
        Message message = new Message(source, destination, messageType, payload, "test-correlation-id-3");
        
        // When
        byte[] encoded = codec.encode(message);
        String json = new String(encoded);
        
        // Then
        assertNotNull(json);
        assertTrue(json.contains("192.168.1.1"));
        assertTrue(json.contains("8080"));
        assertTrue(json.contains("CLIENT_GET_REQUEST"));
        // Base64 encoding of "hello" is "aGVsbG8="
        assertTrue(json.contains("aGVsbG8="));
    }
} 