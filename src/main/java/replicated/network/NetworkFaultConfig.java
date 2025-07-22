package replicated.network;

import java.util.*;

/**
 * Configuration for simulating network faults such as partitions, packet loss, and delays.
 * Thread-safe with controlled mutation through explicit methods.
 */
public final class NetworkFaultConfig {
    private final Set<String> partitionedLinks;
    private final Map<String, Double> linkPacketLoss;
    private final Map<String, Integer> linkDelays;

    public NetworkFaultConfig() {
        this(new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    public NetworkFaultConfig(Set<String> partitionedLinks, Map<String, Double> linkPacketLoss, Map<String, Integer> linkDelays) {
        this.partitionedLinks = Collections.unmodifiableSet(new HashSet<>(partitionedLinks));
        this.linkPacketLoss = Collections.unmodifiableMap(new HashMap<>(linkPacketLoss));
        this.linkDelays = Collections.unmodifiableMap(new HashMap<>(linkDelays));
    }

    public Set<String> getPartitionedLinks() {
        return partitionedLinks;
    }

    public Map<String, Double> getLinkPacketLoss() {
        return linkPacketLoss;
    }

    public Map<String, Integer> getLinkDelays() {
        return linkDelays;
    }

    /**
     * Adds a partitioned link to the configuration.
     * @param linkKey the link key to partition
     */
    public void addPartitionedLink(String linkKey) {
        // Create a new mutable set with existing links plus the new one
        Set<String> newPartitionedLinks = new HashSet<>(partitionedLinks);
        newPartitionedLinks.add(linkKey);
        // Replace the immutable set with a new one
        try {
            java.lang.reflect.Field field = NetworkFaultConfig.class.getDeclaredField("partitionedLinks");
            field.setAccessible(true);
            field.set(this, Collections.unmodifiableSet(newPartitionedLinks));
        } catch (Exception e) {
            throw new RuntimeException("Failed to update partitioned links", e);
        }
    }

    /**
     * Removes a partitioned link from the configuration.
     * @param linkKey the link key to unpartition
     */
    public void removePartitionedLink(String linkKey) {
        Set<String> newPartitionedLinks = new HashSet<>(partitionedLinks);
        newPartitionedLinks.remove(linkKey);
        try {
            java.lang.reflect.Field field = NetworkFaultConfig.class.getDeclaredField("partitionedLinks");
            field.setAccessible(true);
            field.set(this, Collections.unmodifiableSet(newPartitionedLinks));
        } catch (Exception e) {
            throw new RuntimeException("Failed to update partitioned links", e);
        }
    }

    /**
     * Sets packet loss rate for a specific link.
     * @param linkKey the link key
     * @param lossRate the packet loss rate (0.0 to 1.0)
     */
    public void setLinkPacketLoss(String linkKey, Double lossRate) {
        Map<String, Double> newLinkPacketLoss = new HashMap<>(linkPacketLoss);
        if (lossRate == null) {
            newLinkPacketLoss.remove(linkKey);
        } else {
            newLinkPacketLoss.put(linkKey, lossRate);
        }
        try {
            java.lang.reflect.Field field = NetworkFaultConfig.class.getDeclaredField("linkPacketLoss");
            field.setAccessible(true);
            field.set(this, Collections.unmodifiableMap(newLinkPacketLoss));
        } catch (Exception e) {
            throw new RuntimeException("Failed to update link packet loss", e);
        }
    }

    /**
     * Sets delay for a specific link.
     * @param linkKey the link key
     * @param delayTicks the delay in ticks
     */
    public void setLinkDelay(String linkKey, Integer delayTicks) {
        Map<String, Integer> newLinkDelays = new HashMap<>(linkDelays);
        if (delayTicks == null) {
            newLinkDelays.remove(linkKey);
        } else {
            newLinkDelays.put(linkKey, delayTicks);
        }
        try {
            java.lang.reflect.Field field = NetworkFaultConfig.class.getDeclaredField("linkDelays");
            field.setAccessible(true);
            field.set(this, Collections.unmodifiableMap(newLinkDelays));
        } catch (Exception e) {
            throw new RuntimeException("Failed to update link delays", e);
        }
    }
} 