package replicated.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageTypeTest {

    @Test
    void shouldContainAllRequiredMessageTypes() {
        // Given & When & Then
        assertEquals(18, MessageType.values().length);
        
        // Verify all required message types exist
        assertNotNull(MessageType.CLIENT_GET_REQUEST);
        assertNotNull(MessageType.CLIENT_SET_REQUEST);
        assertNotNull(MessageType.CLIENT_GET_RESPONSE);
        assertNotNull(MessageType.CLIENT_SET_RESPONSE);
        assertNotNull(MessageType.INTERNAL_GET_REQUEST);
        assertNotNull(MessageType.INTERNAL_GET_RESPONSE);
        assertNotNull(MessageType.INTERNAL_SET_REQUEST);
        assertNotNull(MessageType.INTERNAL_SET_RESPONSE);
        
        // Verify new system message types
        assertNotNull(MessageType.PING_REQUEST);
        assertNotNull(MessageType.PING_RESPONSE);
        assertNotNull(MessageType.FAILURE_RESPONSE);
        
        // Verify Paxos message types
        assertNotNull(MessageType.PAXOS_PROPOSE_REQUEST);
        assertNotNull(MessageType.PAXOS_PROPOSE_RESPONSE);
        assertNotNull(MessageType.PAXOS_PREPARE_REQUEST);
        assertNotNull(MessageType.PAXOS_PROMISE_RESPONSE);
        assertNotNull(MessageType.PAXOS_ACCEPT_REQUEST);
        assertNotNull(MessageType.PAXOS_ACCEPTED_RESPONSE);
        assertNotNull(MessageType.PAXOS_COMMIT_REQUEST);

    }
    
    @Test
    void shouldCorrectlyIdentifyResponseTypes() {
        // Verify response types
        assertTrue(MessageType.CLIENT_GET_RESPONSE.isResponse());
        assertTrue(MessageType.CLIENT_SET_RESPONSE.isResponse());

        assertTrue(MessageType.INTERNAL_GET_RESPONSE.isResponse());
        assertTrue(MessageType.INTERNAL_SET_RESPONSE.isResponse());
        assertTrue(MessageType.PING_RESPONSE.isResponse());
        assertTrue(MessageType.FAILURE_RESPONSE.isResponse());
        
        // Verify request types
        assertFalse(MessageType.CLIENT_GET_REQUEST.isResponse());
        assertFalse(MessageType.CLIENT_SET_REQUEST.isResponse());
        assertFalse(MessageType.INTERNAL_GET_REQUEST.isResponse());
        assertFalse(MessageType.INTERNAL_SET_REQUEST.isResponse());
        assertFalse(MessageType.PING_REQUEST.isResponse());
    }
    
    @Test
    void shouldCorrectlyIdentifyRequestTypes() {
        // Verify request types
        assertTrue(MessageType.CLIENT_GET_REQUEST.isRequest());
        assertTrue(MessageType.CLIENT_SET_REQUEST.isRequest());
        assertTrue(MessageType.INTERNAL_GET_REQUEST.isRequest());
        assertTrue(MessageType.INTERNAL_SET_REQUEST.isRequest());
        assertTrue(MessageType.PING_REQUEST.isRequest());
        
        // Verify response types
        assertFalse(MessageType.CLIENT_GET_RESPONSE.isRequest());
        assertFalse(MessageType.CLIENT_SET_RESPONSE.isRequest());

        assertFalse(MessageType.INTERNAL_GET_RESPONSE.isRequest());
        assertFalse(MessageType.INTERNAL_SET_RESPONSE.isRequest());
        assertFalse(MessageType.PING_RESPONSE.isRequest());
        assertFalse(MessageType.FAILURE_RESPONSE.isRequest());
    }
        @Test
    void shouldProvideCorrectCategories() {
        // Verify client categories
        assertEquals(MessageType.Category.CLIENT_REQUEST, MessageType.CLIENT_GET_REQUEST.getCategory());
        assertEquals(MessageType.Category.CLIENT_REQUEST, MessageType.CLIENT_SET_REQUEST.getCategory());
        assertEquals(MessageType.Category.CLIENT_RESPONSE, MessageType.CLIENT_GET_RESPONSE.getCategory());
        assertEquals(MessageType.Category.CLIENT_RESPONSE, MessageType.CLIENT_SET_RESPONSE.getCategory());

        
        // Verify internal categories
        assertEquals(MessageType.Category.INTERNAL_REQUEST, MessageType.INTERNAL_GET_REQUEST.getCategory());
        assertEquals(MessageType.Category.INTERNAL_REQUEST, MessageType.INTERNAL_SET_REQUEST.getCategory());
        assertEquals(MessageType.Category.INTERNAL_RESPONSE, MessageType.INTERNAL_GET_RESPONSE.getCategory());
        assertEquals(MessageType.Category.INTERNAL_RESPONSE, MessageType.INTERNAL_SET_RESPONSE.getCategory());
        
        // Verify system categories
        assertEquals(MessageType.Category.SYSTEM_REQUEST, MessageType.PING_REQUEST.getCategory());
        assertEquals(MessageType.Category.SYSTEM_RESPONSE, MessageType.PING_RESPONSE.getCategory());
        assertEquals(MessageType.Category.SYSTEM_RESPONSE, MessageType.FAILURE_RESPONSE.getCategory());
    }
    
    @Test
    void shouldProvideCorrectMessageTypeChecks() {
        // Verify client message checks
        assertTrue(MessageType.CLIENT_GET_REQUEST.isClientMessage());
        assertTrue(MessageType.CLIENT_SET_REQUEST.isClientMessage());
        assertTrue(MessageType.CLIENT_GET_RESPONSE.isClientMessage());
        assertTrue(MessageType.CLIENT_SET_RESPONSE.isClientMessage());

        // Verify internal message checks
        assertTrue(MessageType.INTERNAL_GET_REQUEST.isInternalMessage());
        assertTrue(MessageType.INTERNAL_SET_REQUEST.isInternalMessage());
        assertTrue(MessageType.INTERNAL_GET_RESPONSE.isInternalMessage());
        assertTrue(MessageType.INTERNAL_SET_RESPONSE.isInternalMessage());
        
        // Verify system message checks
        assertTrue(MessageType.PING_REQUEST.isSystemMessage());
        assertTrue(MessageType.PING_RESPONSE.isSystemMessage());
        assertTrue(MessageType.FAILURE_RESPONSE.isSystemMessage());
        
        // Verify cross-category checks
        assertFalse(MessageType.CLIENT_GET_REQUEST.isInternalMessage());
        assertFalse(MessageType.CLIENT_GET_REQUEST.isSystemMessage());
        assertFalse(MessageType.INTERNAL_GET_REQUEST.isClientMessage());
        assertFalse(MessageType.INTERNAL_GET_REQUEST.isSystemMessage());
        assertFalse(MessageType.PING_REQUEST.isClientMessage());
        assertFalse(MessageType.PING_REQUEST.isInternalMessage());
    }
    

} 