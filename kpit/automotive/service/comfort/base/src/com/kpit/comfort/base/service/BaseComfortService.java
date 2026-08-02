package com.kpit.comfort.base.service;

import android.app.Service;
import android.os.IInterface;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base class for every System Service in the Comfort domain (e.g. {@code HvacService}).
 *
 * <p>Owns everything domain services would otherwise have to duplicate:
 * <ul>
 *     <li>A capped {@link ExecutorService} (5 threads) so Binder IPC calls, JNI/VHAL round trips
 *         and property updates never run on the main thread — the #1 ANR source for a bound
 *         system service.</li>
 *     <li>A {@link RemoteCallbackList} of registered AIDL listeners plus a broadcast helper that
 *         prunes dead listeners automatically.</li>
 *     <li>The native VHAL bridge lifecycle ({@code base_comfort_vhal_jni.cpp}): init on
 *         {@link #onCreate()}, release on {@link #onDestroy()}.</li>
 * </ul>
 *
 * @param <T> the AIDL listener interface this service fans events out to
 *            (e.g. {@code IHvacListener}).
 */
public abstract class BaseComfortService<T extends IInterface> extends Service {

    protected static final String TAG = "BaseComfortService";

    private static final int THREAD_POOL_SIZE = 5;

    /** Fixed-size worker pool: all Binder/JNI/VHAL work happens here, never on the main thread. */
    protected final ExecutorService mExecutorPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    /** Registered listeners for this service; use {@link #broadcastToListeners} to fan out. */
    protected final RemoteCallbackList<T> mCallbacks = new RemoteCallbackList<>();

    /** Opaque handle to the native VhalBridge instance; 0 when not (yet) initialized. */
    private volatile long mNativeHandle;

    static {
        System.loadLibrary("base_comfort_jni");
    }

    // ---- Native methods implemented in base_comfort_vhal_jni.cpp ------------------------------

    /** Initializes the native VHAL bridge for this service instance. Returns 0 on failure. */
    protected native long nativeInit();

    /** Tears down the native VHAL bridge and frees the handle. */
    protected native void nativeRelease(long handle);

    /** Subscribes to change notifications for a vehicle property/area. */
    protected native boolean nativeSubscribe(long handle, int propId, int areaId, float sampleRateHz);

    /** Cancels a previous {@link #nativeSubscribe} for the given property. */
    protected native void nativeUnsubscribe(long handle, int propId);

    protected native boolean nativeSetIntProperty(long handle, int propId, int areaId, int value);

    protected native int nativeGetIntProperty(long handle, int propId, int areaId);

    protected native boolean nativeSetFloatProperty(long handle, int propId, int areaId, float value);

    protected native float nativeGetFloatProperty(long handle, int propId, int areaId);

    protected native boolean nativeSetBoolProperty(long handle, int propId, int areaId, boolean value);

    protected native boolean nativeGetBoolProperty(long handle, int propId, int areaId);

    // ---- Service lifecycle --------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        mExecutorPool.execute(() -> {
            long handle = nativeInit();
            mNativeHandle = handle;
            if (handle == 0) {
                Log.e(TAG, getClass().getSimpleName() + ": native VHAL bridge failed to initialize");
            } else {
                Log.i(TAG, getClass().getSimpleName() + ": native VHAL bridge ready (handle=" + handle + ")");
            }
        });
    }

    @Override
    public void onDestroy() {
        mCallbacks.kill();
        final long handle = mNativeHandle;
        mNativeHandle = 0;
        if (handle != 0) {
            // Run release synchronously on the pool and wait for shutdown so the native
            // resources are guaranteed gone before the process is allowed to die.
            mExecutorPool.execute(() -> nativeRelease(handle));
        }
        mExecutorPool.shutdown();
        super.onDestroy();
    }

    // ---- Helpers for concrete services (HvacService, SeatService, ...) -------------------------

    /** Current native handle, or 0 if the VHAL bridge has not finished initializing yet. */
    protected final long getNativeHandle() {
        return mNativeHandle;
    }

    /**
     * Invoked from JNI on the native VHAL callback thread whenever a subscribed property changes.
     * Immediately hops onto {@link #mExecutorPool} so the JNI callback thread is released right
     * away and {@link #onVehiclePropertyChanged} always runs off the main thread.
     */
    private void onNativePropertyEvent(int propId, int areaId) {
        mExecutorPool.execute(() -> onVehiclePropertyChanged(propId, areaId));
    }

    /**
     * Called on a pooled worker thread whenever a subscribed vehicle property changes. Concrete
     * services translate the raw property id into a domain event and fan it out via
     * {@link #broadcastToListeners}.
     */
    protected abstract void onVehiclePropertyChanged(int propId, int areaId);

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
