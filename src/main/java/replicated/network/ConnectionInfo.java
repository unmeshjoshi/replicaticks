package replicated.network;

import replicated.messaging.NetworkAddress;

import java.nio.channels.SocketChannel;
import java.time.Instant;

/**
 * Connection metadata and lifecycle information for tracking network connections.
 * 
 * This class provides proper connection context management following production
 * distributed system patterns from Kafka, Cassandra, and TigerBeetle.
 */
public class ConnectionInfo {
    
    private final SocketChannel channel;
    private final NetworkAddress remoteAddress;
    private final NetworkAddress localAddress;
    private final Instant connectionTime;
    private final ConnectionType connectionType;
    private Instant lastActivityTime;
    private ConnectionState state;
    private String metadata;
    
    public enum ConnectionType {
        INBOUND,    // Server-side: accepted client connection
        OUTBOUND    // Client-side: initiated connection to server
    }
    
    public enum ConnectionState {
        CONNECTING,
        CONNECTED,
        CLOSING,
        CLOSED
    }
    
    public ConnectionInfo(SocketChannel channel, NetworkAddress remoteAddress, 
                         NetworkAddress localAddress, ConnectionType connectionType) {
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.connectionType = connectionType;
        this.connectionTime = Instant.now();
        this.lastActivityTime = Instant.now();
        this.state = ConnectionState.CONNECTING;
    }
    
    public SocketChannel getChannel() {
        return channel;
    }
    
    public NetworkAddress getRemoteAddress() {
        return remoteAddress;
    }
    
    public NetworkAddress getLocalAddress() {
        return localAddress;
    }
    
    public Instant getConnectionTime() {
        return connectionTime;
    }
    
    public ConnectionType getConnectionType() {
        return connectionType;
    }
    
    public Instant getLastActivityTime() {
        return lastActivityTime;
    }
    
    public void updateActivity() {
        this.lastActivityTime = Instant.now();
    }
    
    public ConnectionState getState() {
        return state;
    }
    
    public void setState(ConnectionState state) {
        this.state = state;
    }
    
    public String getMetadata() {
        return metadata;
    }
    
    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
    
    public boolean isConnected() {
        return state == ConnectionState.CONNECTED && channel.isOpen();
    }
    
    public boolean isInbound() {
        return connectionType == ConnectionType.INBOUND;
    }
    
    public boolean isOutbound() {
        return connectionType == ConnectionType.OUTBOUND;
    }
    
    @Override
    public String toString() {
        return String.format("ConnectionInfo{type=%s, remote=%s, local=%s, state=%s, connected=%s}",
                           connectionType, remoteAddress, localAddress, state, isConnected());
    }
} 