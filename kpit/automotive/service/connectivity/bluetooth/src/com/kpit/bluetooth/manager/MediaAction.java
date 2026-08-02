package com.kpit.bluetooth.manager;

/**
 * Semantic command ids crossing {@code IIviBluetoothService#sendMediaCommand(int)}.
 * {@code IviBluetoothService} translates these into AVRCP passthrough key codes
 * ({@code KeyEvent.KEYCODE_MEDIA_*}) before calling
 * {@code BluetoothAvrcpController#sendPassThroughCmd()}.
 */
public final class MediaAction {
    private MediaAction() {
    }

    public static final int ACTION_PLAY = 0;
    public static final int ACTION_PAUSE = 1;
    public static final int ACTION_NEXT = 2;
    public static final int ACTION_PREVIOUS = 3;
}
