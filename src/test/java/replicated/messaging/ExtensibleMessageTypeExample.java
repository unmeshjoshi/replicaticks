package replicated.messaging;

/**
 * Example demonstrating how library consumers can extend the message type system.
 * 
 * This shows how external projects can define their own message types while
 * maintaining compatibility with the core messaging infrastructure.
 */
public class ExtensibleMessageTypeExample {
    
    /**
     * Example: A banking application that uses the replicated library
     * can define its own message types for banking operations.
     */
    public static final class BankingMessageTypes {
        
        // Custom banking message types
        public static final MessageTypeInterface TRANSFER_REQUEST = new CustomMessageType(
            "BANKING_TRANSFER_REQUEST",
            MessageTypeInterface.Category.CLIENT_REQUEST,
            10000L // 10 second timeout for transfers
        );
        
        public static final MessageTypeInterface TRANSFER_RESPONSE = new CustomMessageType(
            "BANKING_TRANSFER_RESPONSE",
            MessageTypeInterface.Category.CLIENT_RESPONSE,
            0L
        );
        
        public static final MessageTypeInterface BALANCE_INQUIRY = new CustomMessageType(
            "BANKING_BALANCE_INQUIRY",
            MessageTypeInterface.Category.CLIENT_REQUEST,
            3000L
        );
        
        public static final MessageTypeInterface BALANCE_RESPONSE = new CustomMessageType(
            "BANKING_BALANCE_RESPONSE",
            MessageTypeInterface.Category.CLIENT_RESPONSE,
            0L
        );
        
        // Internal banking operations
        public static final MessageTypeInterface AUDIT_LOG_REQUEST = new CustomMessageType(
            "BANKING_AUDIT_LOG",
            MessageTypeInterface.Category.INTERNAL_REQUEST,
            5000L
        );
        
        private BankingMessageTypes() {} // Utility class
    }
    
    /**
     * Example: A gaming application that uses the replicated library
     * can define message types for game operations.
     */
    public static final class GameMessageTypes {
        
        public static final MessageTypeInterface PLAYER_JOIN_REQUEST = new CustomMessageType(
            "GAME_PLAYER_JOIN",
            MessageTypeInterface.Category.CLIENT_REQUEST,
            2000L
        );
        
        public static final MessageTypeInterface PLAYER_ACTION = new CustomMessageType(
            "GAME_PLAYER_ACTION",
            MessageTypeInterface.Category.CLIENT_REQUEST,
            100L // Fast timeout for game actions
        );
        
        public static final MessageTypeInterface GAME_STATE_SYNC = new CustomMessageType(
            "GAME_STATE_SYNC",
            MessageTypeInterface.Category.INTERNAL_REQUEST,
            500L // Very fast for real-time games
        );
        
        public static final MessageTypeInterface GAME_RESPONSE = new CustomMessageType(
            "GAME_RESPONSE",
            MessageTypeInterface.Category.CLIENT_RESPONSE,
            0L
        );
        
        private GameMessageTypes() {} // Utility class
    }
    
    /**
     * Custom implementation of MessageTypeInterface.
     * Library consumers can create their own implementations.
     */
    private static final class CustomMessageType implements MessageTypeInterface {
        private final String id;
        private final Category category;
        private final long timeoutMs;
        
        CustomMessageType(String id, Category category, long timeoutMs) {
            this.id = id;
            this.category = category;
            this.timeoutMs = timeoutMs;
        }
        
        @Override
        public String getId() {
            return id;
        }
        
        @Override
        public Category getCategory() {
            return category;
        }
        
        @Override
        public long getTimeoutMs() {
            return timeoutMs;
        }
        
        @Override
        public String toString() {
            return "CustomMessageType{" + id + "}";
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof MessageTypeInterface)) return false;
            MessageTypeInterface other = (MessageTypeInterface) obj;
            return id.equals(other.getId());
        }
        
        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }
    
    /**
     * Example usage showing how different applications can use 
     * both standard and custom message types.
     */
    public static void demonstrateUsage() {
        // Using standard library message types
        MessageTypeInterface stdGet = StandardMessageTypes.CLIENT_GET_REQUEST;
        MessageTypeInterface stdSet = StandardMessageTypes.CLIENT_SET_REQUEST;
        
        // Using custom banking message types
        MessageTypeInterface bankTransfer = BankingMessageTypes.TRANSFER_REQUEST;
        MessageTypeInterface bankBalance = BankingMessageTypes.BALANCE_INQUIRY;
        
        // Using custom game message types
        MessageTypeInterface gameJoin = GameMessageTypes.PLAYER_JOIN_REQUEST;
        MessageTypeInterface gameAction = GameMessageTypes.PLAYER_ACTION;
        
        // All work seamlessly with the same messaging infrastructure
        System.out.println("Standard GET: " + stdGet.getId() + " (timeout: " + stdGet.getTimeoutMs() + "ms)");
        System.out.println("Banking transfer: " + bankTransfer.getId() + " (timeout: " + bankTransfer.getTimeoutMs() + "ms)");
        System.out.println("Game action: " + gameAction.getId() + " (timeout: " + gameAction.getTimeoutMs() + "ms)");
        
        // Category-based routing still works
        System.out.println("Bank transfer is client message: " + bankTransfer.isClientMessage());
        System.out.println("Game action is request: " + gameAction.isRequest());
        System.out.println("Standard GET is response: " + stdGet.isResponse());
    }
} 