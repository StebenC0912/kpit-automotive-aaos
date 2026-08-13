package com.kpit.vspmanager.model;

/**
 * Result of a dumpsys hvac_service --set call. AllianceCarHvacService.handleDumpSet() always
 * responds with a single line starting "OK " or "ERROR " - success/failure is that simple.
 */
public final class SetResult {

    private final boolean success;
    private final String message;

    private SetResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static SetResult parse(String rawOutput) {
        String trimmed = rawOutput == null ? "" : rawOutput.trim();
        boolean ok = trimmed.startsWith("OK");
        return new SetResult(ok, trimmed);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
