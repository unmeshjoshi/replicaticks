package replicated.messaging;

public interface MessageCodec {
    
    /**
     * Encodes a Message into a byte array for network transmission.
     * 
     * @param message the message to encode
     * @return the encoded message as bytes
     */
    byte[] encode(Message message);
    
    /**
     * Decodes a byte array back into a Message.
     * 
     * @param data the encoded message bytes
     * @return the decoded message
     */
    Message decode(byte[] data);
} 