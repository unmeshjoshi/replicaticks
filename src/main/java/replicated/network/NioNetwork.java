package replicated.network;

import replicated.messaging.NetworkAddress;
import replicated.messaging.Message;
import replicated.messaging.MessageCodec;
import replicated.messaging.JsonMessageCodec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Production-ready NIO-based network implementation.
 * Uses Java NIO for non-blocking network I/O while maintaining deterministic tick() behavior.
 * 
 * Key features:
 * - Non-blocking I/O using NIO channels
 * - Multiple server sockets can be bound to different addresses
 * - Maintains message queues for deterministic delivery
 * - Supports network partitioning for testing
 * - Thread-safe for concurrent access
 */
public class NioNetwork implements Network {
    
    private final MessageCodec codec;
    private final Selector selector;
    
    // Server sockets bound to specific addresses
    private final Map<NetworkAddress, ServerSocketChannel> serverChannels = new ConcurrentHashMap<>();
    
    // Client connections to other nodes (outbound connections we initiate)
    private final Map<NetworkAddress, SocketChannel> outboundConnections = new ConcurrentHashMap<>();
    
    // Accepted client connections with full metadata (inbound connections from clients to us as server)
    private final Map<SocketChannel, ConnectionInfo> inboundConnections = new ConcurrentHashMap<>();
    
    // Message queues for each address
    private final Map<NetworkAddress, Queue<Message>> messageQueues = new ConcurrentHashMap<>();
    
    // Outbound message queue for async sending
    private final Queue<Message> outboundQueue = new ConcurrentLinkedQueue<>();
    
    // Queue for messages pending connection establishment
    private final Map<NetworkAddress, Queue<Message>> pendingMessages = new ConcurrentHashMap<>();
    
    // Network partitioning state
    private final Set<String> partitionedLinks = new HashSet<>();
    private final Map<String, Double> linkPacketLoss = new HashMap<>();
    private final Map<String, Integer> linkDelays = new HashMap<>();
    
    // Per-channel state management (replaces shared buffers to prevent data corruption)
    private final Map<SocketChannel, ChannelState> channelStates = new ConcurrentHashMap<>();
    
    // Request-response correlation tracking (enables proper response routing)
    private final Map<String, MessageContext> pendingRequests = new ConcurrentHashMap<>();
    
    // map message identity (object) to context
    private final Map<Message, MessageContext> messageContexts = new ConcurrentHashMap<>();
    
    private final Random random = new Random();
    
    public NioNetwork() {
        this(new JsonMessageCodec());
    }
    
    public NioNetwork(MessageCodec codec) {
        this.codec = codec;
        try {
            this.selector = Selector.open();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create NIO selector", e);
        }
    }
    
    /**
     * Binds a server socket to the specified address for incoming connections.
     * This must be called before the network can receive messages at this address.
     */
    public void bind(NetworkAddress address) {
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }
        
        try {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(address.ipAddress(), address.port()));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            
            serverChannels.put(address, serverChannel);
            messageQueues.put(address, new ConcurrentLinkedQueue<>());
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind to address: " + address, e);
        }
    }
    
    /**
     * Unbinds the server socket from the specified address.
     */
    public void unbind(NetworkAddress address) {
        ServerSocketChannel serverChannel = serverChannels.remove(address);
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                // Log but don't fail
            }
        }
        messageQueues.remove(address);
    }
    
    @Override
    public void send(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }

        System.out.println("NIO: Sending message from " + message.source() + " to " + message.destination() + 
                          " type=" + message.messageType());
        
        // Check for network partition
        String linkKey = linkKey(message.source(), message.destination());
        if (partitionedLinks.contains(linkKey)) {
            System.out.println("NIO: Message dropped due to partition: " + linkKey);
            return; // Drop message due to partition
        }
        
        // Check for packet loss
        Double lossRate = linkPacketLoss.get(linkKey);
        if (lossRate != null && random.nextDouble() < lossRate) {
            System.out.println("NIO: Message dropped due to packet loss: " + linkKey);
            return; // Drop message due to packet loss
        }
        
        // Add to outbound queue for async processing
        outboundQueue.offer(message);
        System.out.println("NIO: Message added to outbound queue, queue size: " + outboundQueue.size());
    }
    
    @Override
    public List<Message> receive(NetworkAddress address) {
        if (address == null) {
            throw new IllegalArgumentException("Address cannot be null");
        }
        
        Queue<Message> queue = messageQueues.get(address);
        if (queue == null) {
            return List.of();
        }
        
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = queue.poll()) != null) {
            messages.add(message);
        }
        
        return messages;
    }
    
    @Override
    public void tick() {
        try {
            // Process selector events (non-blocking)
            selector.selectNow();
            
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();
                
                try {
                    if (key.isReadable()) {
                        System.out.println("NIO: Processing READ event");
                        handleRead(key);
                    } else if (key.isWritable()) {
                        System.out.println("NIO: Processing WRITE event");
                        handleWrite(key);
                    } else if (key.isConnectable()) {
                        System.out.println("NIO: Processing CONNECT event");
                        handleConnect(key);
                    } else if (key.isAcceptable()) {
                        System.out.println("NIO: Processing ACCEPT event");
                        handleAccept(key);
                    }
                } catch (IOException e) {
                    // Close problematic connection
                    key.cancel();
                    if (key.channel() != null) {
                        try {
                            key.channel().close();
                        } catch (IOException ignored) {}
                    }
                }
            }
            
            // Process outbound messages
            processOutboundMessages();
            
        } catch (IOException e) {
            throw new RuntimeException("Error in network tick", e);
        }
    }
    
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        
        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_READ);
            
            // Get the client's address from the accepted connection
            InetSocketAddress clientAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
            InetSocketAddress localAddress = (InetSocketAddress) clientChannel.getLocalAddress();
            
            NetworkAddress clientNetworkAddress = new NetworkAddress(
                clientAddress.getAddress().getHostAddress(), 
                clientAddress.getPort()
            );
            
            NetworkAddress localNetworkAddress = new NetworkAddress(
                localAddress.getAddress().getHostAddress(),
                localAddress.getPort()
            );
            
            // Create connection info with proper metadata
            ConnectionInfo connectionInfo = new ConnectionInfo(
                clientChannel, 
                clientNetworkAddress, 
                localNetworkAddress, 
                ConnectionInfo.ConnectionType.INBOUND
            );
            connectionInfo.setState(ConnectionInfo.ConnectionState.CONNECTED);
            
            // Store the accepted client channel with full metadata
            inboundConnections.put(clientChannel, connectionInfo);
            
            // Create channel state for the new connection
            channelStates.put(clientChannel, new ChannelState());
            
            System.out.println("NIO: Accepted connection from client: " + clientNetworkAddress + 
                              " -> " + localNetworkAddress + " (connection info: " + connectionInfo + ")");
        }
    }
    
    private void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        System.out.println("NIO: handleConnect called for channel: " + channel);
        
        try {
            if (channel.finishConnect()) {
                System.out.println("NIO: Connection established successfully");
                // Connection established, switch to read mode
                key.interestOps(SelectionKey.OP_READ);
                
                // Find the destination address for this channel
                NetworkAddress destination = findDestinationForChannel(channel);
                if (destination != null) {
                    System.out.println("NIO: Found destination " + destination + " for connected channel");
                    // Send any queued messages
                    sendQueuedMessages(destination, channel);
                } else {
                    System.out.println("NIO: WARNING - Could not find destination for connected channel");
                }
            } else {
                System.out.println("NIO: Connection still pending, will retry later");
            }
        } catch (IOException e) {
            System.err.println("NIO: Connection failed: " + e.getMessage());
            key.cancel();
            channel.close();
        }
    }
    
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        
        // Get or create channel state for this specific channel (prevents data corruption)
        ChannelState channelState = channelStates.computeIfAbsent(channel, k -> new ChannelState());
        ByteBuffer readBuffer = channelState.getReadBuffer();
        readBuffer.clear();
        
        int bytesRead = channel.read(readBuffer);
        System.out.println("NIO: handleRead - read " + bytesRead + " bytes from channel");
        
        if (bytesRead == -1) {
            // Connection closed - comprehensive cleanup
            System.out.println("NIO: Connection closed, performing cleanup");
            cleanupConnection(channel);
            return;
        }
        
        if (bytesRead > 0) {
            channelState.updateActivity();
            readBuffer.flip();
            
            // Get source address for message context
            NetworkAddress sourceAddress = getChannelSourceAddress(channel);
            
            // Handle multiple messages in a single buffer
            int messagesDecoded = 0;
            while (readBuffer.hasRemaining()) {
                // Try to decode a message from the current buffer position
                int initialPosition = readBuffer.position();
                byte[] data = new byte[readBuffer.remaining()];
                readBuffer.get(data);
                
                try {
                    Message message = codec.decode(data);
                    messagesDecoded++;
                    System.out.println("NIO: Decoded message #" + messagesDecoded + " from " + message.source() + " to " + message.destination() + 
                                      " type=" + message.messageType());
                    
                    // Create message context to preserve source channel information
                    MessageContext messageContext = new MessageContext(message, channel, sourceAddress);
                    
                    // Route message with context
                    routeInboundMessage(messageContext);
                    
                    // If we successfully decoded a message, calculate how many bytes it consumed
                    // and advance the buffer position accordingly
                    byte[] encodedMessage = codec.encode(message);
                    int messageLength = encodedMessage.length;
                    readBuffer.position(initialPosition + messageLength);
                    
                } catch (Exception e) {
                    // If we can't decode a message, it might be a partial message
                    // Put the buffer back to the initial position and break
                    readBuffer.position(initialPosition);
                    System.out.println("NIO: Could not decode message (possibly partial), stopping decode loop");
                    break;
                }
            }
            
            System.out.println("NIO: Decoded " + messagesDecoded + " messages from " + bytesRead + " bytes");
        }
    }
    
    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        
        // Get channel state for this specific channel
        ChannelState channelState = channelStates.get(channel);
        if (channelState == null) {
            System.err.println("NIO: No channel state found for write operation");
            return;
        }
        
        // Get pending data from attachment
        ByteBuffer buffer = (ByteBuffer) key.attachment();
        if (buffer != null) {
            int bytesWritten = channel.write(buffer);
            channelState.updateActivity();
            
            System.out.println("NIO: handleWrite - wrote " + bytesWritten + " bytes, remaining: " + buffer.remaining());
            
            if (!buffer.hasRemaining()) {
                // Finished writing, switch back to read mode
                key.interestOps(SelectionKey.OP_READ);
                key.attach(null);
                
                // Continue processing any remaining queued messages for this destination
                NetworkAddress destination = findDestinationForChannel(channel);
                if (destination != null) {
                    sendQueuedMessages(destination, channel);
                }
            }
        } else {
            // Check if there are pending writes in the channel state
            if (channelState.hasPendingWrites()) {
                ByteBuffer pendingBuffer = channelState.getPendingWrites().poll();
                if (pendingBuffer != null) {
                    int bytesWritten = channel.write(pendingBuffer);
                    channelState.updateActivity();
                    
                    System.out.println("NIO: handleWrite - wrote pending " + bytesWritten + " bytes, remaining: " + pendingBuffer.remaining());
                    
                    if (pendingBuffer.hasRemaining()) {
                        // Still have data to write, keep it for next time
                        key.attach(pendingBuffer);
                    } else if (!channelState.hasPendingWrites()) {
                        // No more pending writes, switch back to read mode
                        key.interestOps(SelectionKey.OP_READ);
                    }
                }
            }
        }
    }
    
    private void processOutboundMessages() {
        // Process new outbound messages
        Message message;
        int processedCount = 0;
        while ((message = outboundQueue.poll()) != null) {
            processedCount++;
            System.out.println("NIO: Processing outbound message #" + processedCount + " from " + 
                              message.source() + " to " + message.destination());
            try {
                sendMessageDirectly(message);
            } catch (IOException e) {
                System.err.println("Failed to send message: " + e);
                // Message failed to send, could retry or log
            }
        }
        
        if (processedCount > 0) {
            System.out.println("NIO: Processed " + processedCount + " outbound messages");
        }
        
        // Also try to send any pending messages for established connections
        for (Map.Entry<NetworkAddress, Queue<Message>> entry : pendingMessages.entrySet()) {
            NetworkAddress destination = entry.getKey();
            Queue<Message> queue = entry.getValue();
            
            if (!queue.isEmpty()) {
                System.out.println("NIO: Found " + queue.size() + " pending messages for " + destination);
                SocketChannel channel = outboundConnections.get(destination);
                if (channel != null && channel.isConnected()) {
                    System.out.println("NIO: Channel connected, sending queued messages to " + destination);
                    try {
                        sendQueuedMessages(destination, channel);
                    } catch (IOException e) {
                        System.err.println("Failed to send queued messages to " + destination + ": " + e);
                    }
                } else {
                    System.out.println("NIO: Channel not connected for " + destination + 
                                      " (channel=" + channel + ", connected=" + 
                                      (channel != null ? channel.isConnected() : "null") + ")");
                }
            }
        }
    }
    
    private void sendMessageDirectly(Message message) throws IOException {
        NetworkAddress destination = message.destination();
        SocketChannel channel = null;
        
        // SIMPLIFIED ROUTING: Check for inbound channel first (for responses), 
        // then fall back to outbound channel (for requests)
        channel = findInboundChannelForDestination(destination);
        if (channel != null && channel.isConnected()) {
            System.out.println("NIO: Using inbound connection for " + destination);
        } else {
            // No inbound connection, create/use outbound connection
            channel = getOrCreateClientChannel(destination);
            System.out.println("NIO: Using outbound connection for " + destination);
        }
        
        System.out.println("NIO: sendMessageDirectly to " + destination + 
                          " (channel=" + channel + ", connected=" + 
                          (channel != null ? channel.isConnected() : "null") + ")");
        
        if (channel != null && channel.isConnected()) {
            byte[] data = codec.encode(message);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            
            int bytesWritten = channel.write(buffer);
            System.out.println("NIO: Wrote " + bytesWritten + " bytes immediately, remaining=" + buffer.remaining());
            
            if (buffer.hasRemaining()) {
                // Couldn't write everything, register for write events
                SelectionKey key = channel.keyFor(selector);
                if (key != null) {
                    key.interestOps(SelectionKey.OP_WRITE);
                    key.attach(buffer);
                    System.out.println("NIO: Registered for write events, buffer attached");
                }
            } else {
                System.out.println("NIO: Message sent completely");
            }
        } else {
            // Connection not ready, queue the message for later delivery
            pendingMessages.computeIfAbsent(destination, k -> new ConcurrentLinkedQueue<>()).offer(message);
            System.out.println("NIO: Message queued for later delivery, pending count for " + destination + 
                              ": " + pendingMessages.get(destination).size());
        }
    }
    
    private SocketChannel getOrCreateClientChannel(NetworkAddress address) throws IOException {
        SocketChannel channel = outboundConnections.get(address);
        
        System.out.println("NIO: getOrCreateClientChannel for " + address + 
                          " (existing channel=" + channel + 
                          ", connected=" + (channel != null ? channel.isConnected() : "null") + 
                          ", isOpen=" + (channel != null ? channel.isOpen() : "null") + ")");
        
        if (channel == null || !channel.isOpen()) {
            if (channel != null) {
                System.out.println("NIO: Existing channel not open, creating new one");
            } else {
                System.out.println("NIO: No existing channel, creating new one");
            }
            
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            
            boolean connected = channel.connect(new InetSocketAddress(address.ipAddress(), address.port()));
            if (!connected) {
                // Connection in progress, register for connect events
                System.out.println("NIO: Connection in progress, registering for connect events");
                channel.register(selector, SelectionKey.OP_CONNECT);
            } else {
                // Immediate connection, register for read events
                System.out.println("NIO: Immediate connection, registering for read events");
                channel.register(selector, SelectionKey.OP_READ);
            }
            
            outboundConnections.put(address, channel);
            
            // Create channel state for the new outbound connection
            channelStates.put(channel, new ChannelState());
            
            System.out.println("NIO: Stored new channel in outboundConnections map");
        } else {
            System.out.println("NIO: Using existing channel (connected=" + channel.isConnected() + ")");
        }
        
        return channel;
    }
    
    /**
     * Finds the NetworkAddress destination for a given SocketChannel.
     * Searches both outbound connections and inbound connections.
     */
    private NetworkAddress findDestinationForChannel(SocketChannel channel) {
        // First check outbound connections (client-side)
        for (Map.Entry<NetworkAddress, SocketChannel> entry : outboundConnections.entrySet()) {
            if (entry.getValue() == channel) {
                return entry.getKey();
            }
        }
        
        // Then check inbound connections (server-side) - return the remote address
        ConnectionInfo connectionInfo = inboundConnections.get(channel);
        if (connectionInfo != null) {
            return connectionInfo.getRemoteAddress();
        }
        
        return null;
    }
    
    /**
     * Finds an inbound SocketChannel for a given destination address.
     * This is used when the server needs to send responses back to a client.
     */
    private SocketChannel findInboundChannelForDestination(NetworkAddress destination) {
        for (ConnectionInfo connectionInfo : inboundConnections.values()) {
            if (connectionInfo.getRemoteAddress().equals(destination)) {
                return connectionInfo.getChannel();
            }
        }
        return null;
    }
    
    /**
     * Determines if a message is a response to a previous request.
     */
    private boolean isResponseMessage(Message message) {
        if (message == null) return false;
        
        String messageType = message.messageType().toString();
        // All response messages contain "RESPONSE": CLIENT_RESPONSE, INTERNAL_GET_RESPONSE, INTERNAL_SET_RESPONSE
        return messageType.contains("RESPONSE");
    }
    
    /**
     * Finds the original request channel for routing responses back.
     * This implements proper request-response correlation.
     */
    private SocketChannel findResponseChannel(Message message) {
        // Look for pending requests that match this response
        String correlationPattern = message.destination() + "->" + message.source();
        
        for (Map.Entry<String, MessageContext> entry : pendingRequests.entrySet()) {
            String correlationId = entry.getKey();
            MessageContext requestContext = entry.getValue();
            
            if (correlationId.contains(correlationPattern) && 
                requestContext.canRouteResponse()) {
                // Found matching request context, return the source channel
                System.out.println("NIO: Found response routing via correlation: " + correlationId);
                return requestContext.getSourceChannel();
            }
        }
        
        // No correlation found, fallback to inbound channel lookup
        System.out.println("NIO: No correlation found for response, using inbound channel lookup");
        return findInboundChannelForDestination(message.destination());
    }
    
    /**
     * Sends all queued messages for a destination once the connection is established.
     */
    private void sendQueuedMessages(NetworkAddress destination, SocketChannel channel) throws IOException {
        System.out.println("NIO: sendQueuedMessages called for " + destination);
        
        Queue<Message> queue = pendingMessages.get(destination);
        if (queue != null) {
            System.out.println("NIO: Found pending queue with " + queue.size() + " messages");
            
            Message message;
            int sentCount = 0;
            while ((message = queue.poll()) != null) {
                System.out.println("NIO: Sending queued message " + (++sentCount) + " from " + 
                                  message.source() + " to " + message.destination());
                
                // Encode and send the message directly
                byte[] data = codec.encode(message);
                ByteBuffer buffer = ByteBuffer.wrap(data);
                
                int bytesWritten = channel.write(buffer);
                System.out.println("NIO: Wrote " + bytesWritten + " bytes, remaining=" + buffer.remaining());
                
                if (buffer.hasRemaining()) {
                    // Couldn't write everything, register for write events and attach the buffer
                    SelectionKey key = channel.keyFor(selector);
                    if (key != null) {
                        key.interestOps(SelectionKey.OP_WRITE);
                        key.attach(buffer);
                        System.out.println("NIO: Partial write, registered for write events");
                        break; // Stop processing more messages until this one is sent
                    }
                }
            }
            
            System.out.println("NIO: Sent " + sentCount + " queued messages, remaining in queue: " + queue.size());
        } else {
            System.out.println("NIO: No pending messages queue found for " + destination);
        }
    }
    
    @Override
    public void partition(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        
        partitionedLinks.add(linkKey(source, destination));
        partitionedLinks.add(linkKey(destination, source));
    }
    
    @Override
    public void partitionOneWay(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        
        partitionedLinks.add(linkKey(source, destination));
    }
    
    @Override
    public void healPartition(NetworkAddress source, NetworkAddress destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        
        partitionedLinks.remove(linkKey(source, destination));
        partitionedLinks.remove(linkKey(destination, source));
    }
    
    @Override
    public void setDelay(NetworkAddress source, NetworkAddress destination, int delayTicks) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay ticks cannot be negative");
        }
        
        linkDelays.put(linkKey(source, destination), delayTicks);
    }
    
    @Override
    public void setPacketLoss(NetworkAddress source, NetworkAddress destination, double lossRate) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Addresses cannot be null");
        }
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("Loss rate must be between 0.0 and 1.0");
        }
        
        linkPacketLoss.put(linkKey(source, destination), lossRate);
    }
    
    @Override
    public NetworkAddress establishConnection(NetworkAddress destination) {
        if (destination == null) {
            throw new IllegalArgumentException("Destination address cannot be null");
        }
        
        try {
            // Create a new socket channel for this connection
            SocketChannel channel = SocketChannel.open();
            
            // Temporarily use blocking mode for connection establishment
            // This ensures we get the actual local address assigned by the OS
            channel.configureBlocking(true);
            
            // Connect to destination (this will block until connected)
            InetSocketAddress destinationAddress = new InetSocketAddress(
                destination.ipAddress(), destination.port());
            boolean connected = channel.connect(destinationAddress);
            
            if (!connected) {
                throw new IOException("Failed to connect to " + destination);
            }
            
            // Get the actual local address assigned by the OS
            InetSocketAddress localAddress = (InetSocketAddress) channel.getLocalAddress();
            
            // Convert to NetworkAddress
            NetworkAddress actualClientAddress = new NetworkAddress(
                localAddress.getAddress().getHostAddress(), localAddress.getPort());
            
            // Now switch back to non-blocking mode for ongoing operations
            channel.configureBlocking(false);
            
            // Register the channel for read events (connection is already established)
            channel.register(selector, SelectionKey.OP_READ);
            
            // Store the channel for future use
            outboundConnections.put(destination, channel);
            
            return actualClientAddress;
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to establish connection to " + destination, e);
        }
    }
    
    private String linkKey(NetworkAddress source, NetworkAddress destination) {
        return source.toString() + "->" + destination.toString();
    }
    
    /**
     * Generates a unique correlation ID for request-response tracking.
     * This enables proper response routing back to the original request channel.
     */
    private String generateCorrelationId(Message message) {
        return message.source() + "->" + message.destination() + "-" + 
               message.messageType() + "-" + System.currentTimeMillis() + "-" + 
               random.nextInt(1000);
    }
    
    /**
     * Comprehensive connection cleanup to prevent resource leaks.
     * This follows production patterns for proper connection lifecycle management.
     */
    private void cleanupConnection(SocketChannel channel) {
        try {
            // Remove from all tracking maps
            inboundConnections.remove(channel);
            outboundConnections.values().remove(channel);
            
            // Clean up channel state
            ChannelState state = channelStates.remove(channel);
            if (state != null) {
                state.cleanup();
            }
            
            // Clean up pending requests for this channel
            pendingRequests.entrySet().removeIf(entry -> {
                MessageContext context = entry.getValue();
                return context.getSourceChannel() == channel;
            });
            
            // Cancel selection key
            SelectionKey key = channel.keyFor(selector);
            if (key != null) {
                key.cancel();
            }
            
            // Close channel
            if (channel.isOpen()) {
                channel.close();
            }
            
            System.out.println("NIO: Connection cleanup completed for channel");
            
        } catch (IOException e) {
            System.err.println("NIO: Error during connection cleanup: " + e.getMessage());
        }
    }
    
    /**
     * Gets the source address for a channel based on connection type.
     */
    private NetworkAddress getChannelSourceAddress(SocketChannel channel) {
        // Check if it's an inbound connection
        ConnectionInfo connectionInfo = inboundConnections.get(channel);
        if (connectionInfo != null) {
            return connectionInfo.getRemoteAddress();
        }
        
        // Check if it's an outbound connection
        for (Map.Entry<NetworkAddress, SocketChannel> entry : outboundConnections.entrySet()) {
            if (entry.getValue() == channel) {
                try {
                    InetSocketAddress remoteAddress = (InetSocketAddress) channel.getRemoteAddress();
                    return new NetworkAddress(remoteAddress.getAddress().getHostAddress(), remoteAddress.getPort());
                } catch (IOException e) {
                    return entry.getKey(); // Fallback to destination address
                }
            }
        }
        
        return null;
    }
    
    /**
     * Routes inbound messages with preserved context information.
     * This enables proper response routing back to the source channel.
     */
    private void routeInboundMessage(MessageContext messageContext) {
        Message message = messageContext.getMessage();
        
        // Store request context for response correlation
        if (messageContext.isRequest()) {
            String correlationId = generateCorrelationId(message);
            messageContext.setCorrelationId(correlationId);
            pendingRequests.put(correlationId, messageContext);
            System.out.println("NIO: Stored request context for correlation: " + correlationId + 
                              ", context: " + messageContext);
        }
        
        // Add to destination queue for processing
        Queue<Message> queue = messageQueues.get(message.destination());
        if (queue != null) {
            queue.offer(message);
            // store context for upper layers
            messageContexts.put(message, messageContext);
            System.out.println("NIO: Message added to receive queue for " + message.destination() + 
                              ", queue size: " + queue.size() + ", context: " + messageContext);
        } else {
            System.out.println("NIO: No receive queue found for " + message.destination() + 
                              ", context: " + messageContext);
        }
    }
    
    @Override
    public MessageContext getContextFor(Message message) {
        return messageContexts.get(message);
    }
    
    /**
     * Closes all network resources.
     */
    public void close() {
        try {
            // Close all outbound connections
            for (SocketChannel channel : outboundConnections.values()) {
                try {
                    channel.close();
                } catch (IOException ignored) {}
            }
            
            // Close all inbound connections
            for (ConnectionInfo connectionInfo : inboundConnections.values()) {
                try {
                    connectionInfo.getChannel().close();
                } catch (IOException ignored) {}
            }
            
            // Clean up all channel states
            for (ChannelState state : channelStates.values()) {
                state.cleanup();
            }
            channelStates.clear();
            
            // Clean up pending requests
            pendingRequests.clear();
            
            // Close all server channels
            for (ServerSocketChannel channel : serverChannels.values()) {
                try {
                    channel.close();
                } catch (IOException ignored) {}
            }
            
            // Close selector
            selector.close();
            
        } catch (IOException e) {
            throw new RuntimeException("Error closing network resources", e);
        }
    }
    
    // === NEW PUBLIC API =========================================================
    @Override
    public void sendOnChannel(SocketChannel channel, Message message) {
        if (channel == null || message == null) {
            throw new IllegalArgumentException("Channel and message must not be null");
        }
        if (!channel.isOpen()) {
            throw new IllegalStateException("Channel is closed: " + channel);
        }
        // Encode immediately (small messages) – copies unavoidable for now
        byte[] bytes = codec.encode(message);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        ChannelState state = channelStates.computeIfAbsent(channel, c -> new ChannelState());
        state.addPendingWrite(buffer);

        // Ensure OP_WRITE interest so selector wakes up next tick
        SelectionKey key = channel.keyFor(selector);
        if (key != null && key.isValid()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }
}