package replicated.messaging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTypeTest {

    @Test
    void shouldContainAllRequiredMessageTypes() {
        // Given & When & Then
        assertEquals(7, MessageType.values().length);
        
        // Verify all required message types exist
        assertNotNull(MessageType.CLIENT_GET_REQUEST);
        assertNotNull(MessageType.CLIENT_SET_REQUEST);
        assertNotNull(MessageType.CLIENT_RESPONSE);
        assertNotNull(MessageType.INTERNAL_GET_REQUEST);
        assertNotNull(MessageType.INTERNAL_GET_RESPONSE);
        assertNotNull(MessageType.INTERNAL_SET_REQUEST);
        assertNotNull(MessageType.INTERNAL_SET_RESPONSE);
    }
} 