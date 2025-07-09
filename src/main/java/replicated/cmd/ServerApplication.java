package replicated.cmd;

import replicated.messaging.JsonMessageCodec;
import replicated.messaging.MessageCodec;
import replicated.messaging.NetworkAddress;
import replicated.messaging.ServerMessageBus;
import replicated.network.Network;
import replicated.network.NioNetwork;
import replicated.replica.QuorumReplica;
import replicated.storage.RocksDbStorage;
import replicated.storage.Storage;

import java.util.Arrays;
import java.util.List;

/**
 * Command-line server application for running replica nodes.
 * This is the main entry point for starting a replica in production.
 */
public class ServerApplication {
    
    private final String name;
    private final NetworkAddress myAddress;
    private final List<NetworkAddress> peers;
    private final String storagePath;

    private Network network;
    private Storage storage;
    private ServerMessageBus messageBus;
    private QuorumReplica replica;
    private volatile boolean running = false;
    
    public ServerApplication(String name, String ipAddress, int port, List<NetworkAddress> peers, String storagePath) {
        this.name = name;
        this.myAddress = new NetworkAddress(ipAddress, port);
        this.peers = peers;
        this.storagePath = storagePath;
    }
    
    /**
     * Starts the server with the given configuration.
     * @return true if server started successfully, false otherwise
     */
    public boolean start() {
        try {
            System.out.println("Starting replica " + name + " at " + myAddress + "...");
            
            // 1. Initialize network
            System.out.println("Initializing network...");
            this.network = new NioNetwork();
            ((NioNetwork) network).bind(myAddress);
            System.out.println("Network bound to " + myAddress);

            // 2. Initialize storage (production: RocksDbStorage)
            System.out.println("Initializing storage at " + storagePath + "...");
            this.storage = new RocksDbStorage(storagePath);
            System.out.println("Storage initialized");

            // 3. Initialize message bus
            System.out.println("Initializing message bus...");
            MessageCodec codec = new JsonMessageCodec();
            this.messageBus = new ServerMessageBus(network, codec);
            System.out.println("Message bus initialized");

            // 4. Initialize replica
            System.out.println("Initializing replica with " + peers.size() + " peers...");
            this.replica = new QuorumReplica(name, myAddress, peers, messageBus, codec, storage);
            System.out.println("Replica initialized");

            // 5. Register replica as message handler
            messageBus.registerHandler(myAddress, replica);
            System.out.println("Replica registered as message handler");

            // 6. Add shutdown hook for clean shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutdown signal received, stopping replica " + name + "...");
                stop();
            }));

            running = true;
            System.out.println("Replica " + name + " started successfully at " + myAddress);
            System.out.println("Peers: " + peers);
            System.out.println("Storage: " + storagePath);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Main method for command-line execution.
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        System.out.println("Starting Distributed Key-Value Store Server...");
        System.out.println("Arguments: " + String.join(" ", args));
        
        ServerApplication server = ServerApplication.fromArgs(args);
        if (server.start()) {
            try {
                server.runEventLoop();
            } catch (Exception e) {
                System.err.println("Fatal error in server: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
        } else {
            System.err.println("Failed to start server");
            System.exit(1);
        }
    }

    public static ServerApplication fromArgs(String[] args) {
        String name = "replica-1";
        String ip = "localhost";
        int port = 9092;
        List<NetworkAddress> peers = List.of();
        String storagePath = "data/replica-1";
        for (String arg : args) {
            if (arg.startsWith("--name=")) name = arg.substring(7);
            else if (arg.startsWith("--ip=")) ip = arg.substring(5);
            else if (arg.startsWith("--port=")) port = Integer.parseInt(arg.substring(7));
            else if (arg.startsWith("--peers=")) {
                String peersStr = arg.substring(8);
                if (!peersStr.isBlank()) {
                    peers = Arrays.stream(peersStr.split(","))
                        .map(NetworkAddress::parse)
                        .toList();
                }
            }
            else if (arg.startsWith("--storage=")) storagePath = arg.substring(10);
        }
        return new ServerApplication(name, ip, port, peers, storagePath);
    }

    public void runEventLoop() {
        if (!running) {
            System.err.println("Server not started. Call start() first.");
            return;
        }

        System.out.println("Starting event loop...");
        long tickCount = 0;
        
        while (running) {
            try {
                // Main event loop: tick all components in order
                replica.tick();
                messageBus.tick();
                network.tick();
                storage.tick();
                
                tickCount++;
                if (tickCount % 1000 == 0) {
                    System.out.println("Replica " + name + " processed " + tickCount + " ticks");
                }
                
                // Small sleep to prevent busy-waiting
                Thread.sleep(1); // 1ms tick interval
            } catch (InterruptedException e) {
                System.out.println("Event loop interrupted, shutting down...");
                break;
            } catch (Exception e) {
                System.err.println("Error in event loop: " + e.getMessage());
                e.printStackTrace();
                // Continue running unless it's a fatal error
            }
        }
        
        System.out.println("Event loop stopped after " + tickCount + " ticks");
    }

    public void stop() {
        System.out.println("Stopping replica " + name + "...");
        running = false;
        
        try {
            if (network != null && network instanceof NioNetwork) {
                System.out.println("Closing network...");
                ((NioNetwork) network).close();
            }
            if (storage != null && storage instanceof RocksDbStorage) {
                System.out.println("Closing storage...");
                ((RocksDbStorage) storage).close();
            }
            System.out.println("Replica " + name + " stopped successfully");
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Getters for testing and diagnostics
    public String getName() { return name; }
    public String getIpAddress() { return myAddress.ipAddress(); }
    public int getPort() { return myAddress.port(); }
    public List<NetworkAddress> getPeers() { return peers; }
    public String getStoragePath() { return storagePath; }
    public boolean isRunning() { return running; }
} 