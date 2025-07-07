# Architecture Review: Dual Role Complexity

## Current Problems

### 1. MessageBus Dual Role Confusion
- **Server role**: `registerHandler(NetworkAddress, MessageHandler)` 
- **Client role**: `registerHandler(String correlationId, MessageHandler)`
- **Mixed routing**: Complex conditional logic in `routeMessagesToHandlers()`

### 2. Network Layer Dual Responsibilities  
- **Server mode**: Binds to addresses, accepts connections
- **Client mode**: Creates outbound connections, manages client addresses
- **Mixed connection management**: Both inbound and outbound in same class

### 3. Address Management Confusion
- Clients get ephemeral addresses assigned
- Servers bind to specific addresses  
- Distinction between client/server addresses is blurred

## Proposed Cleaner Architecture

### Option 1: Separate Client and Server Components
```java
// Server-side
public class ServerMessageBus {
    private final Map<NetworkAddress, MessageHandler> handlers = new HashMap<>();
    public void registerHandler(NetworkAddress address, MessageHandler handler);
    public void routeMessages(); // Only address-based routing
}

// Client-side  
public class ClientMessageBus {
    private final Map<String, MessageHandler> correlationHandlers = new HashMap<>();
    public void registerHandler(String correlationId, MessageHandler handler);
    public void routeMessages(); // Only correlation-based routing
}
```

### Option 2: Single MessageBus with Clear Role Separation
```java
public class MessageBus {
    private final RoutingMode mode; // SERVER or CLIENT
    
    // Server mode: only address-based registration
    public void registerServerHandler(NetworkAddress address, MessageHandler handler);
    
    // Client mode: only correlation-based registration  
    public void registerClientHandler(String correlationId, MessageHandler handler);
    
    // Clear routing logic based on mode
    private void routeMessages() {
        if (mode == RoutingMode.SERVER) {
            routeByAddress();
        } else {
            routeByCorrelationId(); 
        }
    }
}
```

### Option 3: Network Layer Separation
```java
// Server network
public class ServerNetwork implements Network {
    private final Map<NetworkAddress, ServerSocketChannel> serverChannels;
    public void bind(NetworkAddress address);
    // Only inbound connection management
}

// Client network  
public class ClientNetwork implements Network {
    private final Map<NetworkAddress, SocketChannel> outboundConnections;
    public NetworkAddress connect(NetworkAddress destination);
    // Only outbound connection management
}
```

## Recommendation

**Option 2** provides the best balance:
- Single MessageBus class but with clear role separation
- Explicit mode-based routing logic
- Easier to understand and maintain
- Less code duplication than Option 1
- More cohesive than current mixed approach

## Questions for Review

1. Should we refactor to separate client/server roles more explicitly?
2. Is the current dual-role approach too complex for maintenance?
3. Would explicit mode-based routing be clearer than current conditional logic?
4. Should Network layer also be separated into client/server components? 