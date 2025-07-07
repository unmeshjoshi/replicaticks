package replicated.messaging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void shouldCreateMessageWithAllRequiredFields() {
        // Given
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8081);
        MessageType messageType = MessageType.CLIENT_GET_REQUEST;
        byte[] payload = "test payload".getBytes();
        
        // When
        Message message = new Message(source, destination, messageType, payload, "test-correlation-id");
        
        // Then
        assertEquals(source, message.source());
        assertEquals(destination, message.destination());
        assertEquals(messageType, message.messageType());
        assertArrayEquals(payload, message.payload());
    }
    
    @Test
    void shouldProvideEqualityBasedOnAllFields() {
        // Given
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8081);
        MessageType messageType = MessageType.CLIENT_GET_REQUEST;
        byte[] payload = "test".getBytes();
        
        Message message1 = new Message(source, destination, messageType, payload, "test-correlation-id");
        Message message2 = new Message(source, destination, messageType, payload, "test-correlation-id");
        Message message3 = new Message(source, destination, MessageType.CLIENT_SET_REQUEST, payload, "test-correlation-id");
        
        // When & Then
        assertEquals(message1, message2);
        assertNotEquals(message1, message3);
        assertEquals(message1.hashCode(), message2.hashCode());
    }
    
    @Test
    void shouldRejectNullSource() {
        // Given
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8081);
        MessageType messageType = MessageType.CLIENT_GET_REQUEST;
        byte[] payload = "test".getBytes();
        
        // When & Then
        assertThrows(NullPointerException.class, () ->
            new Message(null, destination, messageType, payload, "test-correlation-id"));
    }
    
    @Test
    void shouldRejectNullDestination() {
        // Given
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        MessageType messageType = MessageType.CLIENT_GET_REQUEST;
        byte[] payload = "test".getBytes();
        
        // When & Then
        assertThrows(NullPointerException.class, () ->
            new Message(source, null, messageType, payload, "test-correlation-id"));
    }
    
    @Test
    void shouldRejectNullMessageType() {
        // Given
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8081);
        byte[] payload = "test".getBytes();
        
        // When & Then
        assertThrows(NullPointerException.class, () ->
            new Message(source, destination, null, payload, "test-correlation-id"));
    }
    
    @Test
    void shouldRejectNullPayload() {
        // Given
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8081);
        MessageType messageType = MessageType.CLIENT_GET_REQUEST;
        
        // When & Then
        assertThrows(NullPointerException.class, () ->
            new Message(source, destination, messageType, null, "test-correlation-id"));
    }
    
    @Test
    void shouldThrowExceptionForNullCorrelationId() {
        // Given
        NetworkAddress source = new NetworkAddress("192.168.1.1", 8080);
        NetworkAddress destination = new NetworkAddress("192.168.1.2", 8080);
        MessageType messageType = MessageType.CLIENT_GET_REQUEST;
        byte[] payload = "test".getBytes();
        
        // When & Then
        assertThrows(NullPointerException.class, () ->
            new Message(source, destination, messageType, payload, null));
    }
} 