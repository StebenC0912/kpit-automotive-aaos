package com.kpit.comfort.base.manager;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Base class for every SDK-side Manager in the Comfort domain (e.g. {@code AllianceCarHvacManager}).
 *
 * <p>This is the ONLY surface HMI applications are allowed to link against for talking to a
 * Comfort domain service. It hides the Binder plumbing (service lookup, death recovery,
 * reconnection) behind a small, generic contract so that concrete managers only have to:
 * <ol>
 *     <li>Implement {@link #asInterface(IBinder)} to wrap the raw {@link IBinder} with their
 *         AIDL-generated {@code Stub.asInterface()}.</li>
 *     <li>Call {@link #getService()} whenever they need to make a remote call.</li>
 * </ol>
 *
 * @param <T> the AIDL service interface this manager talks to (e.g. {@code IHvacService}).
 */
public abstract class AllianceCarBaseManager<T extends IInterface> {

    private static final String TAG = "AllianceCarBaseManager";

    private final String mServiceName;
    private final Object mLock = new Object();

    private volatile T mService;

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "Service '" + mServiceName + "' died; will reconnect lazily on next call");
            synchronized (mLock) {
                mService = null;
            }
            onServiceDisconnected();
        }
    };

    /**
     * @param serviceName the name the concrete service was published under via
     *                     {@code ServiceManager.addService(serviceName, this)}.
     */
    protected AllianceCarBaseManager(String serviceName) {
        mServiceName = serviceName;
    }

    /** Wraps a raw {@link IBinder} with the concrete AIDL {@code Stub.asInterface()} call. */
    protected abstract T asInterface(IBinder binder);

    /** Called on (re)connection. Runs on the caller's thread; keep it fast/non-blocking. */
    protected void onServiceConnected(T service) {
        // Optional hook for subclasses; default no-op.
    }

    /** Called when the remote service process has died. Runs on a Binder callback thread. */
    protected void onServiceDisconnected() {
        // Optional hook for subclasses; default no-op.
    }

    /**
     * Returns a live handle to the remote service, transparently reconnecting if the previous
     * connection was never established or the remote process died. Returns {@code null} if the
     * service is not currently registered with {@link ServiceManager} (e.g. not booted yet).
     */
    protected final T getService() {
        T service = mService;
        if (service != null && service.asBinder().isBinderAlive()) {
            return service;
        }
        synchronized (mLock) {
            service = mService;
            if (service != null && service.asBinder().isBinderAlive()) {
                return service;
            }
            return connectLocked();
        }
    }

    /** Forces a fresh lookup on next {@link #getService()} call, e.g. after a known outage. */
    public final void resetConnection() {
        synchronized (mLock) {
            unlinkLocked();
            mService = null;
        }
    }

    public final boolean isConnected() {
        T service = mService;
        return service != null && service.asBinder().isBinderAlive();
    }

    private T connectLocked() {
        IBinder binder = ServiceManager.getService(mServiceName);
        if (binder == null) {
            Log.e(TAG, "Service '" + mServiceName + "' is not registered with ServiceManager");
            return null;
        }
        try {
            binder.linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Service '" + mServiceName + "' died before linkToDeath completed", e);
            return null;
        }
        T service = asInterface(binder);
        mService = service;
        onServiceConnected(service);
        return service;
    }

    private void unlinkLocked() {
        T service = mService;
        if (service != null) {
            service.asBinder().unlinkToDeath(mDeathRecipient, 0);
        }
    }
}
