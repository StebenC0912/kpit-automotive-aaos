package com.kpit.connectivity.base.service;

import android.app.Service;
import android.os.IInterface;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base class for every System Service in the Connectivity domain (e.g. {@code IviBluetoothService}).
 *
 * <p>Unlike {@code BaseComfortService}, there is NO native VHAL bridge here: Connectivity domains
 * (Bluetooth, WiFi) own no vehicle signal, so vps::VpsDispatcher (Component 3) is never involved
 * (instruction.md section VI — "no VHAL/JNI"). Concrete services instead acquire Android's own
 * framework/hidden profile or session proxies directly (e.g. {@code BluetoothHeadsetClient},
 * {@code BluetoothA2dpSink}, {@code BluetoothAvrcpController}) inside
 * {@link #onConnectivitySourceConnect()}, and fan out state changes captured from their own
 * {@code BroadcastReceiver}s via {@link #broadcastToListeners}.
 *
 * <p>What this base DOES still own, identically to {@code BaseComfortService}:
 * <ul>
 *     <li>A capped {@link ExecutorService} (5 threads) so Binder IPC, profile-proxy calls and
 *         broadcast-receiver callbacks never run on the main thread (rule IV.1).</li>
 *     <li>A {@link RemoteCallbackList} of registered AIDL listeners plus a broadcast helper that
 *         prunes dead listeners automatically.</li>
 * </ul>
 *
 * @param <T> the AIDL listener interface this service fans events out to
 *            (e.g. {@code IIviBluetoothListener}).
 */
public abstract class BaseConnectivityService<T extends IInterface> extends Service {

    protected static final String TAG = "BaseConnectivityService";

    private static final int THREAD_POOL_SIZE = 5;

    /** Fixed-size worker pool: all Binder/profile-proxy/broadcast work happens here. */
    protected final ExecutorService mExecutorPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    /** Registered listeners for this service; use {@link #broadcastToListeners} to fan out. */
    protected final RemoteCallbackList<T> mCallbacks = new RemoteCallbackList<>();

    // ---- Service lifecycle --------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        mExecutorPool.execute(this::onConnectivitySourceConnect);
    }

    @Override
    public void onDestroy() {
        mCallbacks.kill();
        mExecutorPool.execute(this::onConnectivitySourceDisconnect);
        mExecutorPool.shutdown();
        super.onDestroy();
    }

    // ---- Hooks for concrete services (IviBluetoothService, WifiService, ...) -------------------

    /**
     * Called once on a pooled worker thread during {@link #onCreate()}. Concrete services acquire
     * their profile/session proxies here (e.g. {@code BluetoothAdapter#getProfileProxy}) and
     * register whatever {@code BroadcastReceiver}s they need for incremental updates.
     *
     * <p><b>Rule IV.5:</b> once a proxy connects (e.g. inside its
     * {@code BluetoothProfile.ServiceListener#onServiceConnected()}), this method MUST eagerly
     * query current state — {@code getConnectedDevices()}, and for AVRCP additionally
     * {@code getCurrentMetadata()}/{@code getPlaybackState()} for any device already connected —
     * before returning. Do not rely solely on subsequent broadcasts for this initial state, or a
     * service restart / app-installed-while-already-paired / boot race will show stale or wrong
     * state until the next incidental event fires.
     */
    protected abstract void onConnectivitySourceConnect();

    /**
     * Called once on a pooled worker thread during {@link #onDestroy()}. Concrete services
     * release their profile/session proxies and unregister their {@code BroadcastReceiver}s here.
     */
    protected abstract void onConnectivitySourceDisconnect();

    /**
     * Broadcasts an event to every registered listener, automatically skipping (and letting
     * {@link RemoteCallbackList} prune) listeners whose process has died.
     *
     * @return the number of listeners the broadcast was attempted on.
     */
    protected final int broadcastToListeners(ListenerInvocation<T> invocation) {
        int count = mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    invocation.invoke(mCallbacks.getBroadcastItem(i));
                } catch (RemoteException e) {
                    Log.w(TAG, "Listener callback failed, will be pruned", e);
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
        return count;
    }

    /** Functional callback used with {@link #broadcastToListeners} to avoid boilerplate. */
    protected interface ListenerInvocation<T> {
        void invoke(T listener) throws RemoteException;
    }
}
