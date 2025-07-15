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
        MessageType messageType = MessageType.INTERNAL_GET_REQUEST; // Use non-client message type
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

    @Test
    void shouldCreateClientMessageWithFactoryMethod() {
        // Given
        NetworkAddress destination = new NetworkAddress("127.0.0.1", 8080);
        byte[] payload = "test".getBytes();
        String correlationId = "test-correlation-id";
        
        // When
        Message clientMessage = Message.clientMessage(destination, MessageType.CLIENT_GET_REQUEST, payload, correlationId);
        
        // Then
        assertNull(clientMessage.source(), "Client message should have null source");
        assertEquals(destination, clientMessage.destination());
        assertEquals(MessageType.CLIENT_GET_REQUEST, clientMessage.messageType());
        assertArrayEquals(payload, clientMessage.payload());
        assertEquals(correlationId, clientMessage.correlationId());
    }
    
    @Test
    void shouldCreateUnboundMessageWithSystemMessageType() {
        // Given
        NetworkAddress destination = new NetworkAddress("127.0.0.1", 8080);
        byte[] payload = "test".getBytes();
        String correlationId = "test-correlation-id";
        
        // When
        Message unboundMessage = Message.unboundMessage(destination, MessageType.PING_REQUEST, payload, correlationId);
        
        // Then
        assertNull(unboundMessage.source(), "Unbound message should have null source");
        assertEquals(destination, unboundMessage.destination());
        assertEquals(MessageType.PING_REQUEST, unboundMessage.messageType());
        assertArrayEquals(payload, unboundMessage.payload());
        assertEquals(correlationId, unboundMessage.correlationId());
    }
    
    @Test
    void shouldThrowExceptionForNonClientOrSystemMessageType() {
        // Given
        NetworkAddress destination = new NetworkAddress("127.0.0.1", 8080);
        byte[] payload = "test".getBytes();
        String correlationId = "test-correlation-id";
        
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            Message.unboundMessage(destination, MessageType.INTERNAL_GET_REQUEST, payload, correlationId);
        }, "Should throw exception for internal message type");
    }
    
    @Test
    void shouldThrowExceptionForNonClientMessageType() {
        // Given
        NetworkAddress destination = new NetworkAddress("127.0.0.1", 8080);
        byte[] payload = "test".getBytes();
        String correlationId = "test-correlation-id";
        
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            Message.clientMessage(destination, MessageType.PING_REQUEST, payload, correlationId);
        }, "Should throw exception for non-client message type");
    }
} 