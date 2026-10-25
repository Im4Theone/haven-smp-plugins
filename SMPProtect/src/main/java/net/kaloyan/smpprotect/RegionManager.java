package net.kaloyan.smpprotect;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds all regions. Commands change them on the main thread; every change is written to disk first and only then
 * applied in memory, so a failed save never leaves the running server and the file disagreeing. Listeners read the
 * current immutable {@link RegionIndex}.
 */
public final class RegionManager {

    private final RegionStore store;
    private final Map<String, Region> regions = new LinkedHashMap<>();
    private volatile RegionIndex index = new RegionIndex(List.of());

    /** @param store where regions are kept */
    public RegionManager(RegionStore store) {
        this.store = store;
    }

    /**
     * Replaces everything in memory with the file's contents.
     *
     * @throws IOException if the file is unreadable or invalid; memory is then left as it was
     */
    public synchronized void load() throws IOException {
        List<Region> loaded = store.load();
        Map<String, Region> fresh = new LinkedHashMap<>();
        for (Region region : loaded) {
            fresh.put(region.name(), region);
        }
        regions.clear();
        regions.putAll(fresh);
        index = new RegionIndex(regions.values());
    }

    /** @return the current snapshot; never changes after it was returned */
    public RegionIndex index() {
        return index;
    }

    /** @return the region with this name */
    public synchronized Optional<Region> get(String name) {
        return Optional.ofNullable(regions.get(name));
    }

    /** @return a copy of all regions */
    public synchronized List<Region> all() {
        return new ArrayList<>(regions.values());
    }

    /**
     * Adds or replaces a region (matched by name) and saves.
     *
     * @param region the region
     * @throws IOException if saving failed; nothing changed then
     */
    public synchronized void put(Region region) throws IOException {
        Map<String, Region> next = new LinkedHashMap<>(regions);
        next.put(region.name(), region);
        commit(next);
    }

    /**
     * Removes a region and saves.
     *
     * @param name the region's name
     * @return {@code false} if there was no such region
     * @throws IOException if saving failed; nothing changed then
     */
    public synchronized boolean remove(String name) throws IOException {
        if (!regions.containsKey(name)) {
            return false;
        }
        Map<String, Region> next = new LinkedHashMap<>(regions);
        next.remove(name);
        commit(next);
        return true;
    }

    private void commit(Map<String, Region> next) throws IOException {
        Collection<Region> values = next.values();
        store.save(values);
        regions.clear();
        regions.putAll(next);
        index = new RegionIndex(regions.values());
    }
}
