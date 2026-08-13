package com.kpit.vspmanager.parse;

import com.kpit.vspmanager.model.DumpResult;
import com.kpit.vspmanager.model.HvacProperty;
import com.kpit.vspmanager.model.PropertySnapshot;

import java.util.List;

/**
 * Hand-rolled smoke test (no JUnit dependency, keeps the tool zero-external-deps). Run with:
 *   javac -d build/test-classes $(find src test -name '*.java')
 *   java -cp build/test-classes com.kpit.vspmanager.parse.DumpParserTest
 */
public final class DumpParserTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testNormalFourteenRowDump();
        testNotReadyError();
        testMalformedLine();
        testEmptyOutput();

        if (failures == 0) {
            System.out.println("All DumpParserTest cases passed.");
        } else {
            System.out.println(failures + " DumpParserTest case(s) FAILED.");
            System.exit(1);
        }
    }

    private static void testNormalFourteenRowDump() {
        String sample = String.join("\n",
                "PROP_AC_STATE (real VHAL=0x15400500) area=0 value=1.0",
                "PROP_MAX_STATE (real VHAL=0x15400501) area=0 value=0.0",
                "PROP_RECYCLE_STATE (real VHAL=0x15400502) area=0 value=0.0",
                "PROP_FAN_SPEED (real VHAL=0x15400503) area=0 value=7.0",
                "PROP_SYNC (real VHAL=0x15400504) area=0 value=0.0",
                "PROP_AUTO_MODE (real VHAL=0x15400505) area=0 value=0.0",
                "PROP_DEFROST (real VHAL=0x15400506) area=0 value=0.0",
                "PROP_VENTILATION_MODE (real VHAL=0x15400507) area=0 value=2.0",
                "PROP_VEHICLE_STATE (real VHAL=none) area=0 value=5.0",
                "PROP_TEMP_OUTSIDE (real VHAL=0x15400508) area=0 value=21.5",
                "PROP_TEMP (real VHAL=0x15600503) area=1 value=22.5",
                "PROP_TEMP (real VHAL=0x15600503) area=2 value=20.0",
                "PROP_SEAT_HEATING (real VHAL=0x15400509) area=1 value=1.0",
                "PROP_SEAT_HEATING (real VHAL=0x15400509) area=2 value=0.0");

        DumpResult result = DumpParser.parse(sample);
        check("normal dump status OK", result.getStatus() == DumpResult.Status.OK);
        check("normal dump has 14 rows", result.getSnapshots().size() == 14);

        PropertySnapshot temp = find(result.getSnapshots(), HvacProperty.PROP_TEMP, 1);
        check("PROP_TEMP area=1 value parsed", temp != null && temp.getValue() == 22.5f);
        check("PROP_TEMP area=1 real VHAL label parsed",
                temp != null && "0x15600503".equals(temp.getRealVhalLabel()));

        PropertySnapshot vehicleState = find(result.getSnapshots(), HvacProperty.PROP_VEHICLE_STATE, 0);
        check("PROP_VEHICLE_STATE real VHAL label is 'none'",
                vehicleState != null && "none".equals(vehicleState.getRealVhalLabel()));
    }

    private static void testNotReadyError() {
        DumpResult result = DumpParser.parse("ERROR native VHAL bridge not ready");
        check("not-ready dump status NOT_READY", result.getStatus() == DumpResult.Status.NOT_READY);
        check("not-ready dump has no snapshots", result.getSnapshots().isEmpty());
    }

    private static void testMalformedLine() {
        String sample = "PROP_AC_STATE (real VHAL=0x15400500) area=0 value=1.0\nthis line is garbage";
        DumpResult result = DumpParser.parse(sample);
        check("malformed dump status PARSE_ERROR", result.getStatus() == DumpResult.Status.PARSE_ERROR);
        check("malformed dump preserves raw text",
                result.getRawText() != null && result.getRawText().contains("garbage"));
    }

    private static void testEmptyOutput() {
        DumpResult result = DumpParser.parse("   \n  \n");
        check("empty dump status PARSE_ERROR", result.getStatus() == DumpResult.Status.PARSE_ERROR);
    }

    private static PropertySnapshot find(List<PropertySnapshot> snapshots, HvacProperty property, int area) {
        for (PropertySnapshot snapshot : snapshots) {
            if (snapshot.getProperty() == property && snapshot.getArea() == area) {
                return snapshot;
            }
        }
        return null;
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            System.out.println("PASS: " + description);
        } else {
            System.out.println("FAIL: " + description);
            failures++;
        }
    }
}
