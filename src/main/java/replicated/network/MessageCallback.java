package replicated.network;

import replicated.messaging.Message;

/**
 * Callback interface for push-based message delivery from Network implementations.
 * 
 * This interface enables the Network layer to proactively deliver messages to registered
 * handlers during its tick() cycle, eliminating the need for polling-based receive() calls.
 * 
 * The callback approach matches real-world networking patterns where:
 * - Network layer processes incoming data during its event loop
 * - Registered handlers are notified immediately when messages are ready
 * - No polling or blocking receive operations are needed
 * 
 * Usage:
 * <pre>
 * network.registerMessageHandler(new MessageCallback() {
 *     public void onMessage(Message message, MessageContext context) {
 *         // Handle the message immediately
 *         routeToHandler(message, context);
 *     }
 * });
 * </pre>
 */
public interface MessageCallback {
    
    /**
     * Called by the Network implementation when a message is ready for delivery.
     * 
     * This method is invoked during Network.tick() when messages have been processed
     * and are ready for delivery to application layers.
     * 
     * @param message The message that has been received and is ready for processing
     * @param context The MessageContext containing channel information and routing metadata
     *                for this message, enabling channel-based response routing
     */
    void onMessage(Message message, MessageContext context);
} 