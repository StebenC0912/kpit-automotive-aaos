package com.kpit.vspmanager.adb;

/** Covers adb-not-found, non-zero exit, and timeout - anything that stops an adb call from
 * producing usable output. */
public class AdbException extends Exception {

    public AdbException(String message) {
        super(message);
    }

    public AdbException(String message, Throwable cause) {
        super(message, cause);
    }
}
