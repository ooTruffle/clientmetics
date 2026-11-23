package com.ootruffle.clientmetics.asset;

import com.ootruffle.clientmetics.Clientmetics;
import com.ootruffle.clientmetics.Config;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Loads things (models, decoded images) once each, on two background threads. {@link #get} never
 * blocks: it returns null until the value is ready, and keeps returning null for loads that failed.
 * <p>
 * Each entry remembers when it was last asked for, so {@link #sweep} can drop what nobody has
 * worn for a while (see {@link MemoryJanitor}).
 */
public final class Assets {

    @FunctionalInterface
    public interface Loader<T> {
        T load(String key) throws Exception;
    }

    private static final AtomicInteger THREADS = new AtomicInteger();
    public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        final Thread t = new Thread(r, "Clientmetics-Assets-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private static final Map<String, CompletableFuture<Object>> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_USED = new ConcurrentHashMap<>();

    private Assets() {}

    /** The value for {@code kind:key}, starting its load on first call; null while loading or if it failed. */
    @SuppressWarnings("unchecked")
    public static <T> T get(String kind, String key, Loader<T> loader) {
        final String id = kind + ":" + key;
        LAST_USED.put(id, System.currentTimeMillis());
        final CompletableFuture<Object> future = CACHE.computeIfAbsent(id, k ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        final T value = loader.load(key);
                        if (Config.get().debug) {
                            Clientmetics.LOGGER.info("Loaded {} {}", kind, key);
                        }
                        return value;
                    } catch (Exception e) {
                        Clientmetics.LOGGER.warn("Couldn't load {} {}: {}", kind, key, e.toString());
                        return null;
                    }
                }, EXECUTOR));
        return future.isDone() ? (T) future.getNow(null) : null;
    }

    /** Forgets one entry, so the next {@link #get} loads it again. */
    public static void forget(String kind, String key) {
        CACHE.remove(kind + ":" + key);
        LAST_USED.remove(kind + ":" + key);
    }

    /**
     * Drops finished entries of one kind that haven't been asked for in {@code maxAgeMs}, handing
     * each value to {@code dispose} (for GL resources: call it on the render thread). Failed loads
     * are dropped too, so they're retried later. Returns how many were dropped.
     */
    public static int sweep(String kind, long maxAgeMs, Consumer<Object> dispose) {
        final String prefix = kind + ":";
        final long cutoff = System.currentTimeMillis() - maxAgeMs;
        int dropped = 0;
        for (Iterator<Map.Entry<String, CompletableFuture<Object>>> it = CACHE.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, CompletableFuture<Object>> e = it.next();
            if (!e.getKey().startsWith(prefix) || !e.getValue().isDone()) {
                continue;
            }
            final Long used = LAST_USED.get(e.getKey());
            if (used != null && used > cutoff) {
                continue;
            }
            it.remove();
            LAST_USED.remove(e.getKey());
            final Object value = e.getValue().getNow(null);
            if (value != null && dispose != null) {
                try {
                    dispose.accept(value);
                } catch (RuntimeException ex) {
                    Clientmetics.LOGGER.warn("Couldn't free {}: {}", e.getKey(), ex.toString());
                }
            }
            dropped++;
        }
        return dropped;
    }

    /** How many entries of one kind are loaded. */
    public static int count(String kind) {
        final String prefix = kind + ":";
        int n = 0;
        for (String key : CACHE.keySet()) {
            if (key.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /** Forgets everything loaded, so the next {@link #get} reads files again. */
    public static void clear() {
        CACHE.clear();
        LAST_USED.clear();
    }

}
