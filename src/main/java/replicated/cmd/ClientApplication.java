package replicated.cmd;

import replicated.client.Client;
import replicated.future.ListenableFuture;
import replicated.messaging.ClientMessageBus;
import replicated.messaging.JsonMessageCodec;
import replicated.messaging.NetworkAddress;
import replicated.network.NioNetwork;
import replicated.storage.VersionedValue;

import java.util.List;

/**
 * Command-line client application for the distributed key-value store.
 * This is the main entry point for client operations in production.
 */
public class ClientApplication {
    
    private final String serverAddress;
    private final Client client;
    
    public ClientApplication(String serverAddress) {
        this.serverAddress = serverAddress;
        NetworkAddress serverAddr = NetworkAddress.parse(serverAddress);
        
        // Create network and message bus for the client
        NioNetwork network = new NioNetwork();
        JsonMessageCodec codec = new JsonMessageCodec();
        ClientMessageBus messageBus = new ClientMessageBus(network, codec);
        
        // Create client with bootstrap replicas
        this.client = new Client(messageBus, List.of(serverAddr));
    }
    
    /**
     * Main method for command-line execution.
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }
        
        String operation = args[0];
        String serverAddress = args[1];
        
        ClientApplication clientApp = new ClientApplication(serverAddress);
        
        try {
            switch (operation.toLowerCase()) {
                case "get":
                    if (args.length < 3) {
                        System.err.println("Error: GET operation requires a key");
                        printUsage();
                        System.exit(1);
                    }
                    String key = args[2];
                    clientApp.get(key);
                    break;
                    
                case "set":
                    if (args.length < 4) {
                        System.err.println("Error: SET operation requires a key and value");
                        printUsage();
                        System.exit(1);
                    }
                    String setKey = args[2];
                    String value = args[3];
                    clientApp.set(setKey, value.getBytes());
                    break;
                    
                default:
                    System.err.println("Error: Unknown operation '" + operation + "'");
                    printUsage();
                    System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Performs a GET operation.
     * @param key The key to retrieve
     */
    public void get(String key) {
        try {
            System.out.println("Getting key: " + key + " from server: " + serverAddress);
            
            ListenableFuture<VersionedValue> future = client.sendGetRequest(key);
            
            // Poll for completion (simple approach for command-line client)
            int maxAttempts = 100; // 10 seconds with 100ms intervals
            int attempts = 0;
            while (future.isPending() && attempts < maxAttempts) {
                // Process network events and incoming messages
                client.getMessageBus().tick();
                
                Thread.sleep(100);
                attempts++;
            }
            
            if (future.isCompleted()) {
                VersionedValue result = future.getResult();
                if (result != null) {
                    System.out.println("Value: " + new String(result.value()));
                    System.out.println("Timestamp: " + result.timestamp());
                } else {
                    System.out.println("Key not found");
                }
            } else if (future.isFailed()) {
                throw new RuntimeException("GET operation failed: " + future.getException().getMessage());
            } else {
                throw new RuntimeException("GET operation timed out");
            }
            
        } catch (Exception e) {
            System.err.println("GET operation failed: " + e.getMessage());
            throw new RuntimeException("GET operation failed", e);
        }
    }
    
    /**
     * Performs a SET operation.
     * @param key The key to set
     * @param value The value to store
     */
    public void set(String key, byte[] value) {
        try {
            System.out.println("Setting key: " + key + " = " + new String(value) + " on server: " + serverAddress);
            System.out.println("DEBUG: Creating SET request...");
            
            ListenableFuture<Boolean> future = client.sendSetRequest(key, value);
            System.out.println("DEBUG: SET request sent, future created. Starting polling...");
            
            // Poll for completion (simple approach for command-line client)
            int maxAttempts = 100; // 10 seconds with 100ms intervals
            int attempts = 0;
            while (future.isPending() && attempts < maxAttempts) {
                // Process network events and incoming messages
                client.getMessageBus().tick();
                
                Thread.sleep(100);
                attempts++;
                if (attempts % 10 == 0) { // Log every second
                    System.out.println("DEBUG: Polling attempt " + attempts + "/" + maxAttempts + 
                                      " - future state: " + (future.isPending() ? "PENDING" : 
                                      future.isCompleted() ? "COMPLETED" : "FAILED"));
                }
            }
            
            System.out.println("DEBUG: Polling finished. Final state - Pending: " + future.isPending() + 
                              ", Completed: " + future.isCompleted() + ", Failed: " + future.isFailed());
            
            if (future.isCompleted()) {
                Boolean success = future.getResult();
                if (success) {
                    System.out.println("SET operation successful");
                } else {
                    System.err.println("SET operation failed");
                }
            } else if (future.isFailed()) {
                System.out.println("DEBUG: Future failed with exception: " + future.getException());
                throw new RuntimeException("SET operation failed: " + future.getException().getMessage());
            } else {
                System.out.println("DEBUG: Future still pending after " + maxAttempts + " attempts");
                throw new RuntimeException("SET operation timed out");
            }
            
        } catch (Exception e) {
            System.err.println("SET operation failed: " + e.getMessage());
            throw new RuntimeException("SET operation failed", e);
        }
    }
    
    private static void printUsage() {
        System.out.println("Usage: ClientApplication <operation> <server-address> [key] [value]");
        System.out.println();
        System.out.println("Operations:");
        System.out.println("  get <server-address> <key>     - Retrieve a value by key");
        System.out.println("  set <server-address> <key> <value> - Store a key-value pair");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  ClientApplication get 127.0.0.1:9001 mykey");
        System.out.println("  ClientApplication set 127.0.0.1:9001 mykey myvalue");
    }
} 