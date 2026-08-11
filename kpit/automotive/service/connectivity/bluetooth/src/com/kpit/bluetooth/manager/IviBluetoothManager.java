package com.kpit.bluetooth.manager;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.kpit.bluetooth.IIviBluetoothListener;
import com.kpit.bluetooth.IIviBluetoothService;
import com.kpit.connectivity.base.manager.BaseConnectivityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * SDK client API for the Bluetooth connectivity domain (instruction.md section VI). Resolves
 * {@code IviBluetoothService} via {@code ServiceManager.getService()} the same way
 * {@code AllianceCarHvacManager} resolves {@code AllianceCarHvacService} — see {@code BaseConnectivityManager}.
 */
public class IviBluetoothManager extends BaseConnectivityManager<IIviBluetoothService> {
    private static final String TAG = "IviBluetoothManager";
    private static final String SERVICE_NAME = "bluetooth_service";

    private static IviBluetoothManager sInstance;

    private final List<BluetoothListener> mListeners = new ArrayList<>();

    public static synchronized IviBluetoothManager getInstance() {
        if (sInstance == null) {
            sInstance = new IviBluetoothManager();
        }
        return sInstance;
    }

    public IviBluetoothManager() {
        super(SERVICE_NAME);
    }

    @Override
    protected IIviBluetoothService asInterface(IBinder binder) {
        return IIviBluetoothService.Stub.asInterface(binder);
    }

    @Override
    protected void onServiceConnected(IIviBluetoothService service) {
        try {
            service.registerListener(mBinderListener);
            Log.d(TAG, "Register successfully");
        } catch (RemoteException e) {
            Log.e(TAG, "Cannot register listener", e);
        }
    }

    @Override
    protected void onServiceDisconnected() {
        Log.d(TAG, "Disconnect service");
    }

    public void registerBluetoothListener(BluetoothListener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
        // Registering a listener is the app's signal it wants events -- force the connection (and
        // therefore the remote registerListener()) now instead of waiting for some future outbound
        // connect()/disconnect()/sendMediaCommand() call, since bluetooth_app has no connect button
        // at all (pairing happens through OS Settings) and may never make one otherwise.
        getService();
    }

    public void unregisterBluetoothListener(BluetoothListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    public void unregisterAll() {
        synchronized (mListeners) {
            mListeners.clear();
        }
    }

    /** Kicks off connection on the underlying HFP + A2DP profile proxies for this device. */
    public void connect(String macAddress) {
        IIviBluetoothService service = getService();
        if (service == null) {
            Log.w(TAG, "connect: service not connected");
            return;
        }
        try {
            service.connect(macAddress);
        } catch (RemoteException e) {
            Log.e(TAG, "connect failed", e);
        }
    }

    public void disconnect(String macAddress) {
        IIviBluetoothService service = getService();
        if (service == null) {
            Log.w(TAG, "disconnect: service not connected");
            return;
        }
        try {
            service.disconnect(macAddress);
        } catch (RemoteException e) {
            Log.e(TAG, "disconnect failed", e);
        }
    }

    /** @param action one of {@link MediaAction}'s ACTION_* constants -- routed to AVRCP passthrough. */
    public void sendMediaCommand(int action) {
        IIviBluetoothService service = getService();
        if (service == null) {
            Log.w(TAG, "sendMediaCommand: service not connected");
            return;
        }
        try {
            service.sendMediaCommand(action);
        } catch (RemoteException e) {
            Log.e(TAG, "sendMediaCommand failed", e);
        }
    }

    private final IIviBluetoothListener.Stub mBinderListener = new IIviBluetoothListener.Stub() {
        @Override
        public void onDeviceConnectionChanged(BluetoothDeviceInfo device, boolean connected) {
            synchronized (mListeners) {
                for (BluetoothListener listener : mListeners) {
                    listener.onDeviceConnectionChanged(device, connected);
                }
            }
        }

        @Override
        public void onPlaybackStateChanged(int state, long positionMs) {
            synchronized (mListeners) {
                for (BluetoothListener listener : mListeners) {
                    listener.onPlaybackStateChanged(state, positionMs);
                }
            }
        }

        @Override
        public void onMediaMetadataChanged(MediaPlaybackInfo info) {
            synchronized (mListeners) {
                for (BluetoothListener listener : mListeners) {
                    listener.onMediaMetadataChanged(info);
                }
            }
        }
    };
}
