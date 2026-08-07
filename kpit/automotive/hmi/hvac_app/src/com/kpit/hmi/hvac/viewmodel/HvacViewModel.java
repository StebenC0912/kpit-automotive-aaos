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

import com.kpit.hvac.manager.HvacListener;
import com.kpit.hvac.manager.HvacManager;
import com.kpit.hvac.manager.SystemListener;

public class HvacViewModel extends AndroidViewModel implements HvacListener, SystemListener {
    private static final String TAG = "HvacViewModel";
    private final MutableLiveData<HvacSystemAboveState> mHvacSystemAboveStateLiveData = new MutableLiveData<>();
    private final MutableLiveData<HvacTempState> mHvacTempStateLiveDate = new MutableLiveData<>();
    private final MutableLiveData<HvacSystemBelowState> mHvacSystemBelowStateLiveData = new MutableLiveData<>();
    private final MutableLiveData<HvacFanState> mHvacFanStateLiveDate = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mIsOtherControlsEnable = new MutableLiveData<>();
    private final HvacManager mHvacVehicleManager;
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
        mHvacVehicleManager = HvacManager.getInstance();
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

        if (currentOtherControlsEnabled != mCachedOtherControlsEnable) {
            mCachedOtherControlsEnable = currentOtherControlsEnabled;

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
        if (!mCachedOtherControlsEnable) return;
        mFanSpeed = speed;
        pushFanState();
    }

    @Override
    public void onACStateChanged(boolean value) {
        mIsAcOn = value;
        checkInterlockingAndEvaluate();
    }

    @Override
    public void onMaxStateChanged(boolean value) {
        if (!mCachedOtherControlsEnable) return;
        mIsMaxOn = value;
        boolean isAcEnabled = (mCurrentVehicleState >= 5);
        pushAboveState(isAcEnabled);
    }

    @Override
    public void onAirRecycleStateChanged(boolean value) {
        if (!mCachedOtherControlsEnable) return;
        mIsCycleOn = value;
        boolean isAcEnabled = (mCurrentVehicleState >= 5);
        pushAboveState(isAcEnabled);
    }

    @Override
    public void onTempChanged(float value, int area) {
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
        if (!mCachedOtherControlsEnable) return;
        mIsSyncOn = value;
        if (mIsSyncOn) {
            mRightTemp = mLeftTemp;
        }
        pushTempState();
    }

    @Override
    public void onHeatingSeatChanged(boolean value, int area) {
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
        if (!mCachedOtherControlsEnable) return;
        mVentilationFootOn = value == 1;
        mVentilationFootAndFaceOn = value == 2;
        mVentilationFaceOn = value == 3;
        pushBelowState();
    }

    @Override
    public void onAutoStateChanged(boolean value) {
        if (!mCachedOtherControlsEnable) return;
        mIsAutoOn = value;
        pushBelowState();
    }

    @Override
    public void onDefrostStateChanged(boolean value) {
        if (!mCachedOtherControlsEnable) return;
        mIsDefrostOn = value;
        pushBelowState();
    }

    @Override
    public void onVehicleStateChange(int value) {
        mCurrentVehicleState = value;
        checkInterlockingAndEvaluate();
    }

    @Override
    public void onTempOutsideChanged(int value) {
        Log.d(TAG, "onTempOutsideChanged: " + value);
    }

    public void toggleAc() {
        if (mCurrentVehicleState < 5) return;
        mIsAcOn = !mIsAcOn;
        checkInterlockingAndEvaluate();
        mHvacVehicleManager.setAcState(mIsAcOn);
    }

    public void toggleMax() {
        if (!mCachedOtherControlsEnable) return;
        mIsMaxOn = !mIsMaxOn;
        boolean isAcEnable = mCurrentVehicleState >= 5;
        pushAboveState(isAcEnable);
        mHvacVehicleManager.setMaxState(mIsMaxOn);
    }

    public void toggleRecycle() {
        if (!mCachedOtherControlsEnable) return;
        mIsCycleOn = !mIsCycleOn;
        boolean isAcEnable = mCurrentVehicleState >= 5;
        pushAboveState(isAcEnable);
        mHvacVehicleManager.setCycleState(mIsCycleOn);
    }

    public void toggleSync() {
        if (!mCachedOtherControlsEnable) return;
        mIsSyncOn = !mIsSyncOn;
        if (mIsSyncOn) mRightTemp = mLeftTemp;
        pushTempState();
        mHvacVehicleManager.setTemp(2, mRightTemp);
        mHvacVehicleManager.setSync(mIsSyncOn);
    }

    public void toggleSeatHeating(int area) {
        if (!mCachedOtherControlsEnable) return;
        switch (area) {
            case 1:
                mLeftHeatingOn = !mLeftHeatingOn;
                mHvacVehicleManager.setHeatingSeat(area, mLeftHeatingOn);
                break;
            case 2:
                mRightHeatingOn = !mRightHeatingOn;
                mHvacVehicleManager.setHeatingSeat(area, mRightHeatingOn);
                break;
            default:
                Log.w(TAG, "toggleSeatHeating: Not handle this area");
        }
        pushBelowState();
    }

    public void toggleVentilationMode(int mode) {
        if (!mCachedOtherControlsEnable) return;
        mVentilationFootOn = mode == 1;
        mVentilationFootAndFaceOn = mode == 2;
        mVentilationFaceOn = mode == 3;
        mHvacVehicleManager.setVentilationMode(mode);
        pushBelowState();
    }

    public void toggleAuto() {
        if (!mCachedOtherControlsEnable) return;
        mIsAutoOn = !mIsAutoOn;
        mHvacVehicleManager.setAuto(mIsAutoOn);
        pushBelowState();
    }

    public void toggleDefrost() {
        if (!mCachedOtherControlsEnable) return;
        mIsDefrostOn = !mIsDefrostOn;
        mHvacVehicleManager.setDefrost(mIsDefrostOn);
        pushBelowState();
    }

    public void incrementTemp(int area) {
        if (!mCachedOtherControlsEnable) return;
        switch (area) {
            case 1:
                mLeftTemp += 0.5f;
                mHvacVehicleManager.setTemp(area, mLeftTemp);
                break;
            case 2:
                mRightTemp += 0.5f;
                mHvacVehicleManager.setTemp(area, mRightTemp);
                break;
            default:
                Log.w(TAG, "incrementTemp: Not handle this area");
        }
        pushTempState();
    }

    public void decreaseTemp(int area) {
        if (!mCachedOtherControlsEnable) return;
        switch (area) {
            case 1:
                mLeftTemp -= 0.5f;
                mHvacVehicleManager.setTemp(area, mLeftTemp);
                break;
            case 2:
                mRightTemp -= 0.5f;
                mHvacVehicleManager.setTemp(area, mRightTemp);
                break;
            default:
                Log.w(TAG, "decreaseTemp: Not handle this area");
        }
        pushTempState();
    }

    public void increaseFanSpeed() {
        if (!mCachedOtherControlsEnable) return;
        if (mFanSpeed < 12) {
            int targetFanSpeed = mFanSpeed + 1;
            mHvacVehicleManager.setFanSpeed(targetFanSpeed);
        }
    }

    public void decrementFanSpeed() {
        if (!mCachedOtherControlsEnable) return;
        if (mFanSpeed > 0) {
            int targetFanSpeed = mFanSpeed - 1;
            mHvacVehicleManager.setFanSpeed(targetFanSpeed);
        }
    }

    @Override
    public void onCleared() {
        mHvacVehicleManager.unregisterAll();
        super.onCleared();
    }
}
