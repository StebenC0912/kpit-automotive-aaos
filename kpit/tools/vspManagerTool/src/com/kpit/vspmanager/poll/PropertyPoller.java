package com.kpit.vspmanager.poll;

import com.kpit.vspmanager.adb.AdbClient;
import com.kpit.vspmanager.adb.AdbException;
import com.kpit.vspmanager.model.DumpResult;
import com.kpit.vspmanager.model.PropertySnapshot;
import com.kpit.vspmanager.parse.DumpParser;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls `adb shell dumpsys hvac_service` on a fixed delay from a background thread, diffs
 * against the previous snapshot per (property, area) key, and publishes results to the
 * listener on the EDT. A single dumpsys call already returns every property, so "watching"
 * never needs to poll per-property.
 */
public final class PropertyPoller {

    private final AdbClient adbClient;
    private final PollListener listener;
    private final long intervalMillis;

    private final AtomicReference<String> serial = new AtomicReference<>();
    private final Map<String, PropertySnapshot> lastByKey = new HashMap<>();

    private ScheduledExecutorService executor;

    public PropertyPoller(AdbClient adbClient, PollListener listener, long intervalMillis) {
        this.adbClient = adbClient;
        this.listener = listener;
        this.intervalMillis = intervalMillis;
    }

    public void setSerial(String serial) {
        this.serial.set(serial);
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vspManager-poller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::pollOnce, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void pollOnce() {
        String currentSerial = serial.get();
        String raw;
        try {
            raw = adbClient.getDump(currentSerial);
        } catch (AdbException e) {
            notifyError(e.getMessage());
            return;
        }

        DumpResult result = DumpParser.parse(raw);
        List<PropertySnapshot> changed = new ArrayList<>();
        if (result.getStatus() == DumpResult.Status.OK) {
            for (PropertySnapshot snapshot : result.getSnapshots()) {
                PropertySnapshot previous = lastByKey.get(snapshot.key());
                if (previous == null || previous.getValue() != snapshot.getValue()) {
                    changed.add(snapshot);
                }
                lastByKey.put(snapshot.key(), snapshot);
            }
        } else if (result.getStatus() == DumpResult.Status.PARSE_ERROR) {
            // Malformed output must not kill the poller - log it and keep polling next tick.
            notifyError("Unparseable dumpsys output:\n" + result.getRawText());
            return;
        }
        // NOT_READY falls through to onUpdate so the UI can show the "not ready" banner.

        SwingUtilities.invokeLater(() -> listener.onUpdate(result, changed));
    }

    private void notifyError(String message) {
        SwingUtilities.invokeLater(() -> listener.onError(message));
    }
}
