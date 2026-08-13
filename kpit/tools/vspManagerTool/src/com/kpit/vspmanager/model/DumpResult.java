package com.kpit.vspmanager.model;

import java.util.Collections;
import java.util.List;

/**
 * Result of parsing one dumpsys hvac_service --get call. Distinguishes "the native VHAL
 * bridge isn't up yet" (NOT_READY, dump()'s single ERROR line) from "the output didn't match
 * the expected format at all" (PARSE_ERROR) - a poller must treat those differently rather
 * than crashing or silently treating either as zero properties changed.
 */
public final class DumpResult {

    public enum Status { OK, NOT_READY, PARSE_ERROR }

    private final Status status;
    private final List<PropertySnapshot> snapshots;
    private final String rawText;

    private DumpResult(Status status, List<PropertySnapshot> snapshots, String rawText) {
        this.status = status;
        this.snapshots = snapshots;
        this.rawText = rawText;
    }

    public static DumpResult ok(List<PropertySnapshot> snapshots) {
        return new DumpResult(Status.OK, Collections.unmodifiableList(snapshots), null);
    }

    public static DumpResult notReady() {
        return new DumpResult(Status.NOT_READY, Collections.emptyList(), null);
    }

    public static DumpResult parseError(String rawText) {
        return new DumpResult(Status.PARSE_ERROR, Collections.emptyList(), rawText);
    }

    public Status getStatus() {
        return status;
    }

    public List<PropertySnapshot> getSnapshots() {
        return snapshots;
    }

    public String getRawText() {
        return rawText;
    }
}
