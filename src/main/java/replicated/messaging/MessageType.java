package replicated.messaging;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MessageType is a Netty–style extensible constant that replaces the former enum.
 * <p>
 * It implements {@link MessageTypeInterface} so that external code can rely on the
 * minimal contract (id, category, timeout).  A global registry guarantees that
 * every id maps to a single canonical instance, enabling equality by reference.
 */
public final class MessageType implements MessageTypeInterface {

    // ===== Static registry (id → instance) ==================================
    private static final Map<String, MessageType> REGISTRY = new ConcurrentHashMap<>();

    private static MessageType register(MessageType type) {
        MessageType existing = REGISTRY.putIfAbsent(type.id, type);
        return existing == null ? type : existing; // return canonical instance
    }

    /** Look up an existing MessageType by id (or null if not registered). */
    public static MessageType valueOf(String id) {
        return REGISTRY.get(id);
    }

    /**
     * Factory method to obtain an existing MessageType by id or register a new one
     * if it is not present yet.  This allows external libraries to define custom
     * message types at runtime (Netty-style extensibility).
     */
    public static MessageType valueOf(String id, MessageTypeInterface.Category category, long timeoutMs) {
        return REGISTRY.computeIfAbsent(id, k -> new MessageType(k, category, timeoutMs));
    }

    // ===== Pre-defined (library) constants ==================================
    private static final long DEFAULT_CLIENT_TIMEOUT = 5_000L;
    private static final long DEFAULT_INTERNAL_TIMEOUT = 3_000L;
    private static final long DEFAULT_SYSTEM_TIMEOUT = 1_000L;

    // --- Client messages -----------------------------------------------------
    public static final MessageType CLIENT_GET_REQUEST  = register(new MessageType("CLIENT_GET_REQUEST",  MessageTypeInterface.Category.CLIENT_REQUEST,  DEFAULT_CLIENT_TIMEOUT));
    public static final MessageType CLIENT_SET_REQUEST  = register(new MessageType("CLIENT_SET_REQUEST",  MessageTypeInterface.Category.CLIENT_REQUEST,  DEFAULT_CLIENT_TIMEOUT));
    public static final MessageType CLIENT_GET_RESPONSE = register(new MessageType("CLIENT_GET_RESPONSE", MessageTypeInterface.Category.CLIENT_RESPONSE,  0));
    public static final MessageType CLIENT_SET_RESPONSE = register(new MessageType("CLIENT_SET_RESPONSE", MessageTypeInterface.Category.CLIENT_RESPONSE,  0));

    // --- Internal replica messages -----------------------------------------
    public static final MessageType INTERNAL_GET_REQUEST   = register(new MessageType("INTERNAL_GET_REQUEST",  MessageTypeInterface.Category.INTERNAL_REQUEST,  DEFAULT_INTERNAL_TIMEOUT));
    public static final MessageType INTERNAL_SET_REQUEST   = register(new MessageType("INTERNAL_SET_REQUEST",  MessageTypeInterface.Category.INTERNAL_REQUEST,  DEFAULT_INTERNAL_TIMEOUT));
    public static final MessageType INTERNAL_GET_RESPONSE  = register(new MessageType("INTERNAL_GET_RESPONSE", MessageTypeInterface.Category.INTERNAL_RESPONSE, 0));
    public static final MessageType INTERNAL_SET_RESPONSE  = register(new MessageType("INTERNAL_SET_RESPONSE", MessageTypeInterface.Category.INTERNAL_RESPONSE, 0));

    // --- System messages ----------------------------------------------------
    public static final MessageType PING_REQUEST    = register(new MessageType("PING_REQUEST",    MessageTypeInterface.Category.SYSTEM_REQUEST,  DEFAULT_SYSTEM_TIMEOUT));
    public static final MessageType PING_RESPONSE   = register(new MessageType("PING_RESPONSE",   MessageTypeInterface.Category.SYSTEM_RESPONSE, 0));
    public static final MessageType FAILURE_RESPONSE= register(new MessageType("FAILURE_RESPONSE",MessageTypeInterface.Category.SYSTEM_RESPONSE, 0));

    // --- Paxos messages -----------------------------------------------------
    public static final MessageType PAXOS_PROPOSE_REQUEST  = register(new MessageType("PAXOS_PROPOSE_REQUEST",  MessageTypeInterface.Category.CLIENT_REQUEST,   DEFAULT_CLIENT_TIMEOUT));
    public static final MessageType PAXOS_PROPOSE_RESPONSE = register(new MessageType("PAXOS_PROPOSE_RESPONSE", MessageTypeInterface.Category.CLIENT_RESPONSE,  0));
    public static final MessageType PAXOS_PREPARE_REQUEST  = register(new MessageType("PAXOS_PREPARE_REQUEST",  MessageTypeInterface.Category.INTERNAL_REQUEST, DEFAULT_INTERNAL_TIMEOUT));
    public static final MessageType PAXOS_PROMISE_RESPONSE = register(new MessageType("PAXOS_PROMISE_RESPONSE", MessageTypeInterface.Category.INTERNAL_RESPONSE, 0));
    public static final MessageType PAXOS_ACCEPT_REQUEST   = register(new MessageType("PAXOS_ACCEPT_REQUEST",   MessageTypeInterface.Category.INTERNAL_REQUEST, DEFAULT_INTERNAL_TIMEOUT));
    public static final MessageType PAXOS_ACCEPTED_RESPONSE= register(new MessageType("PAXOS_ACCEPTED_RESPONSE",MessageTypeInterface.Category.INTERNAL_RESPONSE, 0));
    public static final MessageType PAXOS_COMMIT_REQUEST   = register(new MessageType("PAXOS_COMMIT_REQUEST",   MessageTypeInterface.Category.INTERNAL_REQUEST, DEFAULT_INTERNAL_TIMEOUT));

    // ===== Instance fields ==================================================
    private final String id;
    private final MessageTypeInterface.Category category;
    private final long timeoutMs;

    // ===== Constructors ======================================================
    private MessageType(String id, MessageTypeInterface.Category category, long timeoutMs) {
        this.id = Objects.requireNonNull(id, "id");
        this.category = Objects.requireNonNull(category, "category");
        this.timeoutMs = timeoutMs;
    }

    // ===== MessageTypeInterface implementation ==============================
    @Override public String getId() { return id; }
    @Override public MessageTypeInterface.Category getCategory() { return category; }
    @Override public long getTimeoutMs() { return timeoutMs; }

    // ===== Convenience =======================================================
    public boolean isResponse() { return category.isResponse(); }
    public boolean isRequest()  { return !isResponse(); }
    public boolean isClientMessage()   { return category == MessageTypeInterface.Category.CLIENT_REQUEST   || category == MessageTypeInterface.Category.CLIENT_RESPONSE; }
    public boolean isInternalMessage() { return category == MessageTypeInterface.Category.INTERNAL_REQUEST || category == MessageTypeInterface.Category.INTERNAL_RESPONSE; }
    public boolean isSystemMessage()   { return category == MessageTypeInterface.Category.SYSTEM_REQUEST   || category == MessageTypeInterface.Category.SYSTEM_RESPONSE; }

    // ===== Equality & hashing ===============================================
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageType)) return false;
        MessageType other = (MessageType) o;
        return id.equals(other.id);
    }

    @Override public int hashCode() { return id.hashCode(); }

    @Override public String toString() { return id; }

    /**
     * Compatibility helper mimicking the enum-generated {@code values()} method.
     * It returns all registered message types; the order is unspecified.
     */
    public static MessageType[] values() {
        return REGISTRY.values().toArray(new MessageType[0]);
    }

}