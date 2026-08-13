package com.kpit.vspmanager.adb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Talks to a device exclusively via {@code adb}, shelled out through ProcessBuilder - no
 * custom socket, matching the design in kpit/docs/03-implementation-status.md item 5.
 */
public final class AdbClient {

    private static final long DEFAULT_TIMEOUT_SECONDS = 5;

    private final String adbPath;

    public AdbClient(String adbPath) {
        this.adbPath = (adbPath == null || adbPath.trim().isEmpty()) ? "adb" : adbPath.trim();
    }

    /** Serials of devices reported as "device" (online/authorized) by `adb devices -l`. */
    public List<String> listDevices() throws AdbException {
        String output = run(DEFAULT_TIMEOUT_SECONDS, adbPath, "devices", "-l");
        List<String> serials = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && "device".equals(parts[1])) {
                serials.add(parts[0]);
            }
        }
        return Collections.unmodifiableList(serials);
    }

    /** Raw stdout of `adb [-s serial] shell dumpsys hvac_service` - see DumpParser for format. */
    public String getDump(String serial) throws AdbException {
        return run(DEFAULT_TIMEOUT_SECONDS, adbCommand(serial, "shell", "dumpsys", "hvac_service"));
    }

    /** Raw stdout of the --set variant - see SetResult for format. */
    public String setProperty(String serial, String propertyName, int area, float value)
            throws AdbException {
        return run(DEFAULT_TIMEOUT_SECONDS, adbCommand(serial, "shell", "dumpsys", "hvac_service",
                "--set", propertyName, "-a", String.valueOf(area), "-f", String.valueOf(value)));
    }

    private String[] adbCommand(String serial, String... shellArgs) {
        List<String> command = new ArrayList<>();
        command.add(adbPath);
        if (serial != null && !serial.trim().isEmpty()) {
            command.add("-s");
            command.add(serial.trim());
        }
        Collections.addAll(command, shellArgs);
        return command.toArray(new String[0]);
    }

    private String run(long timeoutSeconds, String... command) throws AdbException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new AdbException("Failed to launch '" + command[0]
                    + "' - is adb on PATH, or is the adb path field set correctly?", e);
        }

        // Drain stdout fully before waitFor() so a large dump can't deadlock on a full pipe buffer.
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        } catch (IOException e) {
            process.destroyForcibly();
            throw new AdbException("Failed reading adb output", e);
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AdbException("Interrupted while waiting for adb", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new AdbException("adb command timed out after " + timeoutSeconds + "s: "
                    + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new AdbException("adb exited with code " + process.exitValue() + ": " + output);
        }
        return output.toString();
    }
}
