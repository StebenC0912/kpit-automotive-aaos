package com.kpit.bluetooth.service;

import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.Log;

import com.kpit.bluetooth.IIviBluetoothListener;
import com.kpit.bluetooth.IIviBluetoothService;
import com.kpit.bluetooth.manager.BluetoothDeviceInfo;
import com.kpit.bluetooth.manager.IviPlaybackState;
import com.kpit.bluetooth.manager.MediaAction;
import com.kpit.bluetooth.manager.MediaPlaybackInfo;
import com.kpit.connectivity.base.service.BaseConnectivityService;

import java.util.List;
import java.util.Locale;

/**
 * Service implementation for the Bluetooth connectivity domain (instruction.md section VI). This
 * is where HFP / A2DP / AVRCP are actually touched -- everything else in the domain (Manager SDK,
 * AIDL, parcelables) is plumbing around these three profile proxies.
 *
 * <pre>
 *   Profile   Car's role         Proxy class                  Touched in
 *   -------   ----------------   ---------------------------  --------------------------------
 *   HFP       Hands-Free (HF)    BluetoothHeadsetClient        onConnectivitySourceConnect(),
 *                                                               connectDevice()/disconnectDevice()
 *   A2DP      Sink               BluetoothA2dpSink              onConnectivitySourceConnect(),
 *                                                               connectDevice()/disconnectDevice()
 *   AVRCP     Controller         MediaSessionManager/            onConnectivitySourceConnect()
 *                                MediaController                 (session attach + presync),
 *                                                                 dispatchMediaCommand(),
 *                                                                 MediaController.Callback
 * </pre>
 *
 * <p>AVRCP does NOT go through {@code BluetoothAvrcpController}: that class carries no
 * {@code @SystemApi} annotation at all in this AOSP version (module-internal to the Bluetooth
 * mainline module only, see instruction.md section V), so it's unreachable from this app no
 * matter the sdk/platform_apis setting. The Bluetooth module's own
 * {@code BluetoothMediaBrowserService} publishes the AVRCP-backed session instead, and is meant
 * to be consumed via {@link MediaSessionManager}/{@link MediaController} -- both fully public SDK
 * API, gated only by the {@code MEDIA_CONTENT_CONTROL} signature permission this service already
 * qualifies for (system-signed, {@code sharedUserId="android.uid.system"}).
 */
public class IviBluetoothService extends BaseConnectivityService<IIviBluetoothListener> {

    private static final String TAG = "IviBluetoothService";
    private static final String SERVICE_NAME = "bluetooth_service";

    // Package of the Bluetooth mainline module's app process; used to pick its MediaSession out
    // of every other active session on the device (see attachToBluetoothSession()).
    private static final String BLUETOOTH_SESSION_PACKAGE = "com.android.bluetooth";

    // ---- Profile proxies -- the whole "Component 3" for this domain (no VPS/JNI, see section VI) --
    private volatile BluetoothHeadsetClient mHfpProxy;      // HFP  (car = Hands-Free)
    private volatile BluetoothA2dpSink mA2dpProxy;          // A2DP (car = Sink)

    // AVRCP goes through the platform's media-session framework instead of a profile proxy --
    // see the class-level note above. MediaSessionManager/Controller callbacks land wherever the
    // caller registers them; registering with mMainHandler keeps that off the worker pool, and
    // every callback immediately re-hops onto mExecutorPool before doing any Binder fan-out
    // (rule VI.1 -- never do IPC/broadcast work on the main thread).
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile MediaSessionManager mMediaSessionManager;
    private volatile MediaController mMediaController;

    private final MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mExecutorPool.execute(() -> publishMetadata(metadata));
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            mExecutorPool.execute(() -> publishPlaybackState(state));
        }

        @Override
        public void onSessionDestroyed() {
            mMediaController = null;
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener mActiveSessionsChangedListener =
            controllers -> mExecutorPool.execute(() -> attachToBluetoothSession(controllers));

    private final IIviBluetoothService.Stub mBinder = new IIviBluetoothService.Stub() {
        @Override
        public void connect(String macAddress) {
            mExecutorPool.execute(() -> connectDevice(macAddress));
        }

        @Override
        public void disconnect(String macAddress) {
            mExecutorPool.execute(() -> disconnectDevice(macAddress));
        }

        @Override
        public void sendMediaCommand(int action) {
            mExecutorPool.execute(() -> dispatchMediaCommand(action));
        }

        @Override
        public void registerListener(IIviBluetoothListener listener) {
            mCallbacks.register(listener);
        }

        @Override
        public void unregisterListener(IIviBluetoothListener listener) {
            mCallbacks.unregister(listener);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: IviBluetoothService creating");
        ServiceManager.addService(SERVICE_NAME, mBinder);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // =============================================================================================
    // PROFILE ACQUISITION -- HFP + A2DP + AVRCP all get registered here, on the worker pool
    // (BaseConnectivityService contract), once during onCreate().
    // =============================================================================================
    @Override
    protected void onConnectivitySourceConnect() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "onConnectivitySourceConnect: no BluetoothAdapter on this device");
            return;
        }

        // --- HFP ---------------------------------------------------------------------------------
        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                mHfpProxy = (BluetoothHeadsetClient) proxy;
                // Rule IV.5: eager resync -- don't wait for the next broadcast.
                syncConnectedDevices(mHfpProxy.getConnectedDevices(), BluetoothDeviceInfo.PROFILE_HFP);
            }

            @Override
            public void onServiceDisconnected(int profile) {
                mHfpProxy = null;
            }
        }, BluetoothProfile.HEADSET_CLIENT);

        // --- A2DP --------------------------------------------------------------------------------
        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                mA2dpProxy = (BluetoothA2dpSink) proxy;
                // AVRCP has no reachable profile proxy of its own (class-level note) and rides on
                // the A2DP link in practice, so its badge bit tracks A2DP's connected-device set.
                syncConnectedDevices(mA2dpProxy.getConnectedDevices(),
                        BluetoothDeviceInfo.PROFILE_A2DP | BluetoothDeviceInfo.PROFILE_AVRCP);
            }

            @Override
            public void onServiceDisconnected(int profile) {
                mA2dpProxy = null;
            }
        }, BluetoothProfile.A2DP_SINK);

        // --- AVRCP (via MediaSessionManager, not a profile proxy -- see class-level note) -------
        mMediaSessionManager = getSystemService(MediaSessionManager.class);
        if (mMediaSessionManager != null) {
            mMediaSessionManager.addOnActiveSessionsChangedListener(
                    mActiveSessionsChangedListener, null /* notificationListener */, mMainHandler);
            // Rule IV.5 eager resync: the Bluetooth session may already be active before we
            // registered above (service restart / already-paired-and-playing on boot).
            attachToBluetoothSession(mMediaSessionManager.getActiveSessions(null));
        } else {
            Log.e(TAG, "onConnectivitySourceConnect: no MediaSessionManager on this device");
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(mConnectivityReceiver, filter);
    }

    @Override
    protected void onConnectivitySourceDisconnect() {
        unregisterReceiver(mConnectivityReceiver);
        if (mMediaSessionManager != null) {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(mActiveSessionsChangedListener);
            mMediaSessionManager = null;
        }
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaControllerCallback);
            mMediaController = null;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return;
        }
        if (mHfpProxy != null) {
            adapter.closeProfileProxy(BluetoothProfile.HEADSET_CLIENT, mHfpProxy);
        }
        if (mA2dpProxy != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP_SINK, mA2dpProxy);
        }
    }

    /**
     * Picks the Bluetooth mainline module's session (if any) out of every currently active
     * {@link MediaController}, and (re)attaches {@link #mMediaControllerCallback} to it. Also
     * used as the {@link MediaSessionManager.OnActiveSessionsChangedListener} callback, since the
     * Bluetooth session is created/destroyed by that module as devices connect/disconnect.
     */
    private void attachToBluetoothSession(List<MediaController> controllers) {
        MediaController match = null;
        for (MediaController controller : controllers) {
            if (BLUETOOTH_SESSION_PACKAGE.equals(controller.getPackageName())) {
                match = controller;
                break;
            }
        }
        if ((mMediaController == null && match == null)
                || (mMediaController != null && mMediaController.equals(match))) {
            return; // no change
        }
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaControllerCallback);
        }
        mMediaController = match;
        if (mMediaController == null) {
            return;
        }
        mMediaController.registerCallback(mMediaControllerCallback, mMainHandler);
        // Rule IV.5, media half: pull current track/position immediately rather than waiting on
        // the next onMetadataChanged()/onPlaybackStateChanged() callback.
        publishMetadata(mMediaController.getMetadata());
        publishPlaybackState(mMediaController.getPlaybackState());
    }

    private void syncConnectedDevices(List<BluetoothDevice> devices, int profileFlag) {
        for (BluetoothDevice device : devices) {
            broadcastToListeners(l -> l.onDeviceConnectionChanged(toDeviceInfo(device, profileFlag), true));
        }
    }

    // =============================================================================================
    // EVENT FLOW -- incremental updates after the initial sync above.
    // =============================================================================================
    private final BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Hop straight onto the worker pool -- never do BT/AIDL work on the receiver's thread.
            mExecutorPool.execute(() -> handleBroadcast(intent));
        }
    };

    private void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (action == null || device == null) {
            return;
        }

        switch (action) {
            case BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED: {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                boolean connected = state == BluetoothProfile.STATE_CONNECTED;
                broadcastToListeners(l -> l.onDeviceConnectionChanged(
                        toDeviceInfo(device, BluetoothDeviceInfo.PROFILE_HFP), connected));
                break;
            }
            case BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED: {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                boolean connected = state == BluetoothProfile.STATE_CONNECTED;
                // AVRCP rides on the A2DP link (see onConnectivitySourceConnect()'s A2DP listener).
                broadcastToListeners(l -> l.onDeviceConnectionChanged(
                        toDeviceInfo(device, BluetoothDeviceInfo.PROFILE_A2DP | BluetoothDeviceInfo.PROFILE_AVRCP),
                        connected));
                break;
            }
            default:
                Log.w(TAG, "handleBroadcast: unhandled action " + action);
        }
    }

    // =============================================================================================
    // COMMAND FLOW -- connect/disconnect (HFP+A2DP) and media transport controls (AVRCP).
    // =============================================================================================
    private void connectDevice(String macAddress) {
        BluetoothDevice device = getRemoteDeviceOrNull(macAddress);
        if (device == null) {
            return;
        }
        // connect()/disconnect() are @hide-only (no @SystemApi) on these profile proxies in this
        // AOSP version, so they're stripped from the Bluetooth mainline module's exported stub —
        // same unreachable-API story as BluetoothAvrcpController (section V). setConnectionPolicy()
        // is the @SystemApi-blessed way to request a connection; the stack auto-connects a paired
        // device once its policy is ALLOWED, and still fires ACTION_CONNECTION_STATE_CHANGED.
        if (mHfpProxy != null) {
            mHfpProxy.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }
        if (mA2dpProxy != null) {
            mA2dpProxy.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }
    }

    private void disconnectDevice(String macAddress) {
        BluetoothDevice device = getRemoteDeviceOrNull(macAddress);
        if (device == null) {
            return;
        }
        if (mHfpProxy != null) {
            mHfpProxy.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        }
        if (mA2dpProxy != null) {
            mA2dpProxy.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        }
    }

    // BluetoothAdapter.getRemoteDevice() validates against a case-sensitive regex requiring
    // uppercase hex digits and throws IllegalArgumentException on anything else (including a
    // syntactically fine but lowercase address, e.g. one copied from bt_config.conf, which always
    // prints lowercase). Left uncaught, that exception kills this persistent process on a bad AIDL
    // argument from any caller. Normalize case and swallow-and-log instead of crash-looping.
    private BluetoothDevice getRemoteDeviceOrNull(String macAddress) {
        try {
            return BluetoothAdapter.getDefaultAdapter().getRemoteDevice(macAddress.toUpperCase(Locale.US));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "getRemoteDeviceOrNull: invalid MAC address " + macAddress, e);
            return null;
        }
    }

    private void dispatchMediaCommand(int action) {
        if (mMediaController == null) {
            Log.w(TAG, "dispatchMediaCommand: no active Bluetooth media session");
            return;
        }
        MediaController.TransportControls controls = mMediaController.getTransportControls();
        switch (action) {
            case MediaAction.ACTION_PLAY:
                controls.play();
                break;
            case MediaAction.ACTION_PAUSE:
                controls.pause();
                break;
            case MediaAction.ACTION_NEXT:
                controls.skipToNext();
                break;
            case MediaAction.ACTION_PREVIOUS:
                controls.skipToPrevious();
                break;
            default:
                Log.w(TAG, "dispatchMediaCommand: unknown action " + action);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private BluetoothDeviceInfo toDeviceInfo(BluetoothDevice device, int profileFlag) {
        return new BluetoothDeviceInfo(device.getAddress(), device.getName(), profileFlag);
    }

    private void publishMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            return;
        }
        MediaPlaybackInfo info = new MediaPlaybackInfo(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                IviPlaybackState.STATE_STOPPED, // playback state comes from publishPlaybackState()
                0L);
        broadcastToListeners(l -> l.onMediaMetadataChanged(info));
    }

    private void publishPlaybackState(PlaybackState state) {
        if (state == null) {
            return;
        }
        int mapped;
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
                mapped = IviPlaybackState.STATE_PLAYING;
                break;
            case PlaybackState.STATE_PAUSED:
                mapped = IviPlaybackState.STATE_PAUSED;
                break;
            default:
                mapped = IviPlaybackState.STATE_STOPPED;
                break;
        }
        final int finalMapped = mapped;
        broadcastToListeners(l -> l.onPlaybackStateChanged(finalMapped, state.getPosition()));
    }
}
