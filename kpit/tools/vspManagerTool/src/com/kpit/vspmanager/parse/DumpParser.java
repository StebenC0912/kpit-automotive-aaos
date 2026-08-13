package com.kpit.vspmanager.parse;

import com.kpit.vspmanager.model.DumpResult;
import com.kpit.vspmanager.model.HvacProperty;
import com.kpit.vspmanager.model.PropertySnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the exact text AllianceCarHvacService.handleDumpGet() prints, one line per property:
 *   <PROP_NAME> (real VHAL=<0xHHHHHHHH|none>) area=<int> value=<float>
 * or, if the native VHAL bridge handle is 0, a single line:
 *   ERROR native VHAL bridge not ready
 */
public final class DumpParser {

    private static final Pattern LINE_PATTERN =
            Pattern.compile("^(\\w+) \\(real VHAL=([^)]*)\\) area=(-?\\d+) value=(.+)$");

    private DumpParser() {
    }

    public static DumpResult parse(String rawOutput) {
        if (rawOutput == null) {
            return DumpResult.parseError("");
        }

        List<String> lines = new ArrayList<>();
        for (String line : rawOutput.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }

        if (lines.isEmpty()) {
            return DumpResult.parseError(rawOutput);
        }
        if (lines.get(0).startsWith("ERROR")) {
            return DumpResult.notReady();
        }

        Instant now = Instant.now();
        List<PropertySnapshot> snapshots = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher matcher = LINE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                return DumpResult.parseError(rawOutput);
            }

            HvacProperty property = HvacProperty.byName(matcher.group(1));
            if (property == null) {
                return DumpResult.parseError(rawOutput);
            }

            String realVhalLabel = matcher.group(2);
            int area;
            float value;
            try {
                area = Integer.parseInt(matcher.group(3));
                value = Float.parseFloat(matcher.group(4));
            } catch (NumberFormatException e) {
                return DumpResult.parseError(rawOutput);
            }

            snapshots.add(new PropertySnapshot(property, area, value, realVhalLabel, now));
        }

        return DumpResult.ok(snapshots);
    }
}
