package replicated.messaging;

public interface MessageCodec {
    
    /**
     * Encodes any object (including Message) into a byte array for transmission or storage.
     * This unified method works for both Message objects and arbitrary payloads.
     * 
     * @param obj the object to encode
     * @return the encoded object as bytes
     * @throws RuntimeException if encoding fails or obj is null
     */
    byte[] encode(Object obj);
    
    /**
     * Decodes a byte array back into an object of the specified type.
     * This unified method works for both Message objects and arbitrary payloads.
     * 
     * @param data the encoded bytes
     * @param type the target class type
     * @return the decoded object
     * @throws RuntimeException if decoding fails
     */
    <T> T decode(byte[] data, Class<T> type);

    /**
     * Convenience method to decode bytes back into a Message.
     * Delegates to the unified decode method.
     * 
     * @param data the encoded message bytes
     * @return the decoded message
     */
    default Message decode(byte[] data) {
        return decode(data, Message.class);
    }

    /**
     * Legacy method for payload encoding - delegates to unified encode method.
     * @deprecated Use encode(Object) instead
     */
    @Deprecated
    default byte[] encodePayload(Object payload) {
        return encode(payload);
    }

    /**
     * Legacy method for payload decoding - delegates to unified decode method.
     * @deprecated Use decode(byte[], Class<T>) instead
     */
    @Deprecated
    default <T> T decodePayload(byte[] data, Class<T> type) {
        return decode(data, type);
    }
} 