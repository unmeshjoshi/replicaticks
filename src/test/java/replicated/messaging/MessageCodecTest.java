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
        Message originalMessage = new Message(source, destination, messageType, payload);
        
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
        MessageType messageType = MessageType.CLIENT_RESPONSE;
        byte[] emptyPayload = new byte[0];
        Message originalMessage = new Message(source, destination, messageType, emptyPayload);
        
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
        Message originalMessage = new Message(source, destination, messageType, binaryPayload);
        
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
} 