package com.kpit.hmi.hvac.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.kpit.hmi.hvac.model.HvacSystemAboveState;
import com.kpit.hmi.hvac.model.HvacTempState;
import com.kpit.hmi.hvac.model.HvacSystemBelowState;
import com.kpit.hmi.hvac.model.HvacFanState;

import com.kpit.hvac.manager.AllianceCarHvacManager;
import com.kpit.hvac.manager.HvacListener;
import com.kpit.hvac.manager.SystemListener;

public class HvacViewModel extends AndroidViewModel implements HvacListener, SystemListener {
    private static final String TAG = "HvacViewModel";
    private final MutableLiveData<HvacSystemAboveState> mHvacSystemAboveStateLiveData = new MutableLiveData<>();
    private final MutableLiveData<HvacTempState> mHvacTempStateLiveDate = new MutableLiveData<>();
    private final MutableLiveData<HvacSystemBelowState> mHvacSystemBelowStateLiveData = new MutableLiveData<>();
    private final MutableLiveData<HvacFanState> mHvacFanStateLiveDate = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mIsOtherControlsEnable = new MutableLiveData<>();
    private final AllianceCarHvacManager mHvacVehicleManager;
    private int mCurrentVehicleState = -1;
    private boolean mIsAcOn = false;
    private boolean mCachedOtherControlsEnable = false;
    private boolean mIsMaxOn = false;
    private boolean mIsCycleOn = false;
    private int mFanSpeed = 0;
    private float mLeftTemp = 22.0f;
    private float mRightTemp = 22.0f;
    private boolean mIsSyncOn = false;
    private boolean mLeftHeatingOn = false;
    private boolean mVentilationFootOn = false;
    private boolean mVentilationFootAndFaceOn = false;
    private boolean mIsAutoOn = false;
    private boolean mVentilationFaceOn = true;
    private boolean mIsDefrostOn = false;
    private boolean mRightHeatingOn = false;


    public HvacViewModel(@NonNull Application application) {
        super(application);
        Log.d(TAG, "HvacViewModel: created, registering with AllianceCarHvacManager");
        mHvacVehicleManager = AllianceCarHvacManager.getInstance();
        mHvacVehicleManager.registerPropertyListener(this);
        mHvacVehicleManager.registerSystemListener(this);

        recalculateAllAndPushUi();
    }

    private void recalculateAllAndPushUi() {
        boolean isAcEnable = (mCurrentVehicleState >= 5);
        pushAboveState(isAcEnable);
        pushFanState();
        pushTempState();
        pushBelowState();
        mIsOtherControlsEnable.postValue(mCachedOtherControlsEnable);
    }

    private void pushAboveState(boolean isAcEnabled) {
        mHvacSystemAboveStateLiveData.postValue(new HvacSystemAboveState(isAcEnabled, mIsAcOn, mIsMaxOn, mIsCycleOn));
    }

    private void pushTempState() {
        mHvacTempStateLiveDate.postValue(new HvacTempState(mRightTemp, mLeftTemp, mIsSyncOn));
    }

    private void pushFanState() {
        mHvacFanStateLiveDate.postValue(new HvacFanState(mFanSpeed));
    }

    private void pushBelowState() {
        mHvacSystemBelowStateLiveData.postValue(new HvacSystemBelowState(mIsAutoOn, mLeftHeatingOn,
                mVentilationFootOn, mVentilationFootAndFaceOn, mVentilationFaceOn, mIsDefrostOn,
                mRightHeatingOn));
    }

    public MutableLiveData<HvacSystemAboveState> getHvacStateLiveData() {
        return mHvacSystemAboveStateLiveData;
    }

    public MutableLiveData<HvacTempState> getHvacTempStateLiveDate() {
        return mHvacTempStateLiveDate;
    }

    public MutableLiveData<HvacSystemBelowState> getHvacSystemBelowStateLiveData() {
        return mHvacSystemBelowStateLiveData;
    }

    public MutableLiveData<HvacFanState> getHvacFanStateLiveDate() {
        return mHvacFanStateLiveDate;
    }

    public MutableLiveData<Boolean> getIsOtherControlsEnable() {
        return mIsOtherControlsEnable;
    }

    private void checkInterlockingAndEvaluate() {
        boolean isAcEnable = (mCurrentVehicleState >= 5);
        if (mCurrentVehicleState < 3) {
            mIsAcOn = false;
            isAcEnable = false;
        }

        boolean currentOtherControlsEnabled = isAcEnable && mIsAcOn;
        Log.d(TAG, "checkInterlockingAndEvaluate: vehicleState=" + mCurrentVehicleState
                + " isAcEnable=" + isAcEnable + " isAcOn=" + mIsAcOn
                + " currentOtherControlsEnabled=" + currentOtherControlsEnabled
                + " cachedOtherControlsEnabled=" + mCachedOtherControlsEnable);

        if (currentOtherControlsEnabled != mCachedOtherControlsEnable) {
            mCachedOtherControlsEnable = currentOtherControlsEnabled;
            Log.d(TAG, "checkInterlockingAndEvaluate: otherControlsEnabled changed to "
                    + currentOtherControlsEnabled);

            if (!currentOtherControlsEnabled) {
                mIsMaxOn = false;
                mIsCycleOn = false;
                mFanSpeed = 0;
                mIsSyncOn = false;
                mIsAutoOn = false;
                mRightHeatingOn = false;
                mLeftHeatingOn = false;
                mVentilationFootOn = false;
                mVentilationFootAndFaceOn = false;
                mVentilationFaceOn = false;
                mIsDefrostOn = false;
            } else {
                mVentilationFaceOn = true;
            }
            mIsOtherControlsEnable.postValue(mCachedOtherControlsEnable);
            recalculateAllAndPushUi();
        } else {
            pushAboveState(isAcEnable);
        }
    }

    @Override
    public void onFanSpeedChanged(int speed) {
        Log.d(TAG, "onFanSpeedChanged: speed=" + speed + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mFanSpeed = speed;
        pushFanState();
    }

    @Override
    public void onACStateChanged(boolean value) {
        Log.d(TAG, "onACStateChanged: value=" + value);
        mIsAcOn = value;
        checkInterlockingAndEvaluate();
    }

    @Override
    public void onMaxStateChanged(boolean value) {
        Log.d(TAG, "onMaxStateChanged: value=" + value + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsMaxOn = value;
        boolean isAcEnabled = (mCurrentVehicleState >= 5);
        pushAboveState(isAcEnabled);
    }

    @Override
    public void onAirRecycleStateChanged(boolean value) {
        Log.d(TAG, "onAirRecycleStateChanged: value=" + value + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsCycleOn = value;
        boolean isAcEnabled = (mCurrentVehicleState >= 5);
        pushAboveState(isAcEnabled);
    }

    @Override
    public void onTempChanged(float value, int area) {
        Log.d(TAG, "onTempChanged: value=" + value + " area=" + area + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        if (area == 1) {
            mLeftTemp = value;
            if (mIsSyncOn) mRightTemp = value;
        } else if (area == 2 && !mIsSyncOn) {
            mRightTemp = value;
        }
        pushTempState();
    }

    @Override
    public void onSyncStateChanged(boolean value) {
        Log.d(TAG, "onSyncStateChanged: value=" + value + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsSyncOn = value;
        if (mIsSyncOn) {
            mRightTemp = mLeftTemp;
        }
        pushTempState();
    }

    @Override
    public void onHeatingSeatChanged(boolean value, int area) {
        Log.d(TAG, "onHeatingSeatChanged: value=" + value + " area=" + area + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        if (area == 1) {
            mLeftHeatingOn = value;
        } else if (area == 2) {
            mRightHeatingOn = value;
        } else {
            Log.w(TAG, "onHeatingSeatChanged: Not handle this area");
        }
        pushBelowState();
    }

    @Override
    public void onVentilationModeChanged(int value) {
        Log.d(TAG, "onVentilationModeChanged: value=" + value + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mVentilationFootOn = value == 1;
        mVentilationFootAndFaceOn = value == 2;
        mVentilationFaceOn = value == 3;
        pushBelowState();
    }

    @Override
    public void onAutoStateChanged(boolean value) {
        Log.d(TAG, "onAutoStateChanged: value=" + value + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsAutoOn = value;
        pushBelowState();
    }

    @Override
    public void onDefrostStateChanged(boolean value) {
        Log.d(TAG, "onDefrostStateChanged: value=" + value + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsDefrostOn = value;
        pushBelowState();
    }

    @Override
    public void onVehicleStateChange(int value) {
        Log.d(TAG, "onVehicleStateChange: value=" + value + " (previous=" + mCurrentVehicleState + ")");
        mCurrentVehicleState = value;
        checkInterlockingAndEvaluate();
    }

    @Override
    public void onTempOutsideChanged(int value) {
        Log.d(TAG, "onTempOutsideChanged: " + value);
    }

    public void toggleAc() {
        Log.d(TAG, "toggleAc: requested, vehicleState=" + mCurrentVehicleState + " currentIsAcOn=" + mIsAcOn);
        if (mCurrentVehicleState < 5) {
            Log.d(TAG, "toggleAc: ignored, vehicleState < 5");
            return;
        }
        mIsAcOn = !mIsAcOn;
        checkInterlockingAndEvaluate();
        Log.d(TAG, "toggleAc: sending setAcState(" + mIsAcOn + ")");
        mHvacVehicleManager.setAcState(mIsAcOn);
    }

    public void toggleMax() {
        Log.d(TAG, "toggleMax: requested, otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsMaxOn = !mIsMaxOn;
        boolean isAcEnable = mCurrentVehicleState >= 5;
        pushAboveState(isAcEnable);
        Log.d(TAG, "toggleMax: sending setMaxState(" + mIsMaxOn + ")");
        mHvacVehicleManager.setMaxState(mIsMaxOn);
    }

    public void toggleRecycle() {
        Log.d(TAG, "toggleRecycle: requested, otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsCycleOn = !mIsCycleOn;
        boolean isAcEnable = mCurrentVehicleState >= 5;
        pushAboveState(isAcEnable);
        Log.d(TAG, "toggleRecycle: sending setCycleState(" + mIsCycleOn + ")");
        mHvacVehicleManager.setCycleState(mIsCycleOn);
    }

    public void toggleSync() {
        Log.d(TAG, "toggleSync: requested, otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsSyncOn = !mIsSyncOn;
        if (mIsSyncOn) mRightTemp = mLeftTemp;
        pushTempState();
        Log.d(TAG, "toggleSync: sending setTemp(2, " + mRightTemp + ") and setSync(" + mIsSyncOn + ")");
        mHvacVehicleManager.setTemp(2, mRightTemp);
        mHvacVehicleManager.setSync(mIsSyncOn);
    }

    public void toggleSeatHeating(int area) {
        Log.d(TAG, "toggleSeatHeating: requested, area=" + area + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        switch (area) {
            case 1:
                mLeftHeatingOn = !mLeftHeatingOn;
                Log.d(TAG, "toggleSeatHeating: sending setHeatingSeat(1, " + mLeftHeatingOn + ")");
                mHvacVehicleManager.setHeatingSeat(area, mLeftHeatingOn);
                break;
            case 2:
                mRightHeatingOn = !mRightHeatingOn;
                Log.d(TAG, "toggleSeatHeating: sending setHeatingSeat(2, " + mRightHeatingOn + ")");
                mHvacVehicleManager.setHeatingSeat(area, mRightHeatingOn);
                break;
            default:
                Log.w(TAG, "toggleSeatHeating: Not handle this area");
        }
        pushBelowState();
    }

    public void toggleVentilationMode(int mode) {
        Log.d(TAG, "toggleVentilationMode: requested, mode=" + mode + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mVentilationFootOn = mode == 1;
        mVentilationFootAndFaceOn = mode == 2;
        mVentilationFaceOn = mode == 3;
        Log.d(TAG, "toggleVentilationMode: sending setVentilationMode(" + mode + ")");
        mHvacVehicleManager.setVentilationMode(mode);
        pushBelowState();
    }

    public void toggleAuto() {
        Log.d(TAG, "toggleAuto: requested, otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsAutoOn = !mIsAutoOn;
        Log.d(TAG, "toggleAuto: sending setAuto(" + mIsAutoOn + ")");
        mHvacVehicleManager.setAuto(mIsAutoOn);
        pushBelowState();
    }

    public void toggleDefrost() {
        Log.d(TAG, "toggleDefrost: requested, otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        mIsDefrostOn = !mIsDefrostOn;
        Log.d(TAG, "toggleDefrost: sending setDefrost(" + mIsDefrostOn + ")");
        mHvacVehicleManager.setDefrost(mIsDefrostOn);
        pushBelowState();
    }

    public void incrementTemp(int area) {
        Log.d(TAG, "incrementTemp: requested, area=" + area + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        switch (area) {
            case 1:
                mLeftTemp += 0.5f;
                Log.d(TAG, "incrementTemp: sending setTemp(1, " + mLeftTemp + ")");
                mHvacVehicleManager.setTemp(area, mLeftTemp);
                break;
            case 2:
                mRightTemp += 0.5f;
                Log.d(TAG, "incrementTemp: sending setTemp(2, " + mRightTemp + ")");
                mHvacVehicleManager.setTemp(area, mRightTemp);
                break;
            default:
                Log.w(TAG, "incrementTemp: Not handle this area");
        }
        pushTempState();
    }

    public void decreaseTemp(int area) {
        Log.d(TAG, "decreaseTemp: requested, area=" + area + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        switch (area) {
            case 1:
                mLeftTemp -= 0.5f;
                Log.d(TAG, "decreaseTemp: sending setTemp(1, " + mLeftTemp + ")");
                mHvacVehicleManager.setTemp(area, mLeftTemp);
                break;
            case 2:
                mRightTemp -= 0.5f;
                Log.d(TAG, "decreaseTemp: sending setTemp(2, " + mRightTemp + ")");
                mHvacVehicleManager.setTemp(area, mRightTemp);
                break;
            default:
                Log.w(TAG, "decreaseTemp: Not handle this area");
        }
        pushTempState();
    }

    public void increaseFanSpeed() {
        Log.d(TAG, "increaseFanSpeed: requested, currentSpeed=" + mFanSpeed + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        if (mFanSpeed < 12) {
            int targetFanSpeed = mFanSpeed + 1;
            Log.d(TAG, "increaseFanSpeed: sending setFanSpeed(" + targetFanSpeed + ")");
            mHvacVehicleManager.setFanSpeed(targetFanSpeed);
        } else {
            Log.d(TAG, "increaseFanSpeed: ignored, already at max (12)");
        }
    }

    public void decrementFanSpeed() {
        Log.d(TAG, "decrementFanSpeed: requested, currentSpeed=" + mFanSpeed + " otherControlsEnabled=" + mCachedOtherControlsEnable);
        if (!mCachedOtherControlsEnable) return;
        if (mFanSpeed > 0) {
            int targetFanSpeed = mFanSpeed - 1;
            Log.d(TAG, "decrementFanSpeed: sending setFanSpeed(" + targetFanSpeed + ")");
            mHvacVehicleManager.setFanSpeed(targetFanSpeed);
        } else {
            Log.d(TAG, "decrementFanSpeed: ignored, already at min (0)");
        }
    }

    @Override
    public void onCleared() {
        Log.d(TAG, "onCleared: unregistering from AllianceCarHvacManager");
        mHvacVehicleManager.unregisterAll();
        super.onCleared();
    }
}
