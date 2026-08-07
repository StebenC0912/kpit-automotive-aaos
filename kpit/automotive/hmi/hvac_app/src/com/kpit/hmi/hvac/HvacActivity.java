package com.kpit.hmi.hvac;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.kpit.hmi.hvac.model.HvacFanState;
import com.kpit.hmi.hvac.model.HvacSystemAboveState;
import com.kpit.hmi.hvac.model.HvacSystemBelowState;
import com.kpit.hmi.hvac.model.HvacTempState;
import com.kpit.hmi.hvac.viewmodel.HvacViewModel;

public class HvacActivity extends AppCompatActivity {
    private static final float INACTIVE_TOGGLE_ALPHA = 0.5f;
    private static final int MIN_FAN_SPEED = 0;
    private static final int MAX_FAN_SPEED = 12;

    private HvacViewModel hvacViewModel;
    private boolean mIsPanelEnabled = false;
    private int mCurrentFanSpeed = 0;

    private ImageButton btnAc;
    private ImageButton btnMax;
    private ImageButton btnCycle;
    private ImageButton btnFanDown;
    private LinearLayout layoutFanSpeed;
    private ImageButton btnFanUp;
    private ImageButton btnTempLeftDown;
    private TextView tempLeft;
    private ImageButton btnTempLeftUp;
    private ImageButton btnSync;
    private ImageButton btnTempRightDown;
    private TextView tempRight;
    private ImageButton btnTempRightUp;
    private ImageButton btnHeatingLeft;
    private ImageButton btnVentilationFoot;
    private ImageButton btnVentilationFootAndFace;
    private ImageButton btnAuto;
    private ImageButton btnVentilationFace;
    private ImageButton btnDefrost;
    private ImageButton btnHeatingRight;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        btnAc = findViewById(R.id.btnAc);
        btnMax = findViewById(R.id.btnMax);
        btnCycle = findViewById(R.id.btnCycle);
        btnFanDown = findViewById(R.id.btnFanDown);
        layoutFanSpeed = findViewById(R.id.layoutFanSpeed);
        btnFanUp = findViewById(R.id.btnFanUp);
        btnTempLeftDown = findViewById(R.id.btnTempLeftDown);
        tempLeft = findViewById(R.id.tempLeft);
        btnTempLeftUp = findViewById(R.id.btnTempLeftUp);
        btnSync = findViewById(R.id.btnSync);
        btnTempRightDown = findViewById(R.id.btnTempRightDown);
        tempRight = findViewById(R.id.tempRight);
        btnTempRightUp = findViewById(R.id.btnTempRightUp);
        btnHeatingLeft = findViewById(R.id.btnHeatingLeft);
        btnVentilationFoot = findViewById(R.id.btnVentilationFoot);
        btnVentilationFootAndFace = findViewById(R.id.btnVentilationFootAndFace);
        btnAuto = findViewById(R.id.btnAuto);
        btnVentilationFace = findViewById(R.id.btnVentilationFace);
        btnDefrost = findViewById(R.id.btnDefrost);
        btnHeatingRight = findViewById(R.id.btnHeatingRight);

        hvacViewModel = new ViewModelProvider(this).get(HvacViewModel.class);
        hvacViewModel.getHvacStateLiveData().observe(this, this::renderHvacSystemAboveUi);
        hvacViewModel.getHvacFanStateLiveDate().observe(this, this::renderHvacFanUi);
        hvacViewModel.getHvacTempStateLiveDate().observe(this, this::renderHvacTempUi);
        hvacViewModel.getHvacSystemBelowStateLiveData().observe(this, this::renderHvacSystemBelowUi);

        hvacViewModel.getIsOtherControlsEnable().observe(this, this::setPanelInteractivity);

        btnAc.setOnClickListener(v -> hvacViewModel.toggleAc());
        btnMax.setOnClickListener(v -> hvacViewModel.toggleMax());
        btnCycle.setOnClickListener(v -> hvacViewModel.toggleRecycle());

        btnFanDown.setOnClickListener(v -> hvacViewModel.decrementFanSpeed());
        btnFanUp.setOnClickListener(v -> hvacViewModel.increaseFanSpeed());

        btnTempLeftDown.setOnClickListener(v -> hvacViewModel.decreaseTemp(1));
        btnTempLeftUp.setOnClickListener(v -> hvacViewModel.incrementTemp(1));
        btnTempRightDown.setOnClickListener(v -> hvacViewModel.decreaseTemp(2));
        btnTempRightUp.setOnClickListener(v -> hvacViewModel.incrementTemp(2));
        btnSync.setOnClickListener(v -> hvacViewModel.toggleSync());

        btnAuto.setOnClickListener(v -> hvacViewModel.toggleAuto());
        btnHeatingLeft.setOnClickListener(v -> hvacViewModel.toggleSeatHeating(1));
        btnHeatingRight.setOnClickListener(v -> hvacViewModel.toggleSeatHeating(2));
        btnDefrost.setOnClickListener(v -> hvacViewModel.toggleDefrost());

        btnVentilationFoot.setOnClickListener(v -> hvacViewModel.toggleVentilationMode(1));
        btnVentilationFootAndFace.setOnClickListener(v -> hvacViewModel.toggleVentilationMode(2));
        btnVentilationFace.setOnClickListener(v -> hvacViewModel.toggleVentilationMode(3));

    }

    private void renderHvacSystemAboveUi(HvacSystemAboveState hvacSystemAboveState) {
        btnAc.setActivated(hvacSystemAboveState.isACActivate());
        btnAc.setEnabled(hvacSystemAboveState.isAcEnable());
        btnAc.setAlpha(hvacSystemAboveState.isAcEnable() ? 1.0f : 0.35f);

        btnMax.setActivated(hvacSystemAboveState.isMaxActivate());
        btnCycle.setActivated(hvacSystemAboveState.isRecycleActivate());
    }

    private void renderHvacFanUi(HvacFanState hvacFanState) {
        int totalBars = layoutFanSpeed.getChildCount();
        for (int i = 0; i < totalBars; i++) {
            View segment = layoutFanSpeed.getChildAt(i);
            segment.setActivated(i < hvacFanState.getFanSpeed());
        }
        mCurrentFanSpeed = hvacFanState.getFanSpeed();
        updateFanButtonsState();
    }

    private void updateFanButtonsState() {
        boolean downEnabled = mIsPanelEnabled && mCurrentFanSpeed > MIN_FAN_SPEED;
        boolean upEnabled = mIsPanelEnabled && mCurrentFanSpeed < MAX_FAN_SPEED;
        btnFanDown.setEnabled(downEnabled);
        btnFanDown.setAlpha(downEnabled ? 1.0f : INACTIVE_TOGGLE_ALPHA);
        btnFanUp.setEnabled(upEnabled);
        btnFanUp.setAlpha(upEnabled ? 1.0f : INACTIVE_TOGGLE_ALPHA);
    }

    private void renderHvacTempUi(HvacTempState hvacTempState) {
        tempRight.setText(String.format("%.0f", hvacTempState.getRightZoneTemp()));
        tempLeft.setText(String.format("%.0f", hvacTempState.getLeftZoneTemp()));
        btnSync.setActivated(hvacTempState.isSyncOn());

    }

    private void renderHvacSystemBelowUi(HvacSystemBelowState hvacSystemBelowState) {
        btnAuto.setActivated(hvacSystemBelowState.isAutoActivate());

        // These icons have no dedicated "pressed" drawable, so activation is conveyed via alpha instead.
        setToggleAlpha(btnDefrost, hvacSystemBelowState.isDefrostActive());
        setToggleAlpha(btnHeatingRight, hvacSystemBelowState.isHeatingRightActive());
        setToggleAlpha(btnHeatingLeft, hvacSystemBelowState.isHeatingLeftActive());
        setToggleAlpha(btnVentilationFootAndFace, hvacSystemBelowState.isVentilationFootFaceActive());
        setToggleAlpha(btnVentilationFoot, hvacSystemBelowState.isVentilationFootActive());
        setToggleAlpha(btnVentilationFace, hvacSystemBelowState.isVentilationFaceActive());
    }

    private void setToggleAlpha(ImageButton button, boolean activated) {
        button.setActivated(activated);
        button.setAlpha(activated ? 1.0f : INACTIVE_TOGGLE_ALPHA);
    }

    private void setPanelInteractivity(boolean isEnable) {
        float targetAlpha = isEnable ? 1.0f : 0.35f;

        View[] hvacControlPanel = new View[]{
                btnMax, btnCycle,
                btnTempLeftDown, btnTempLeftUp,
                btnSync, btnTempRightDown, btnTempRightUp,
                btnHeatingLeft, btnVentilationFoot, btnVentilationFootAndFace,
                btnAuto, btnVentilationFace, btnDefrost,
                btnHeatingRight};
        for (View view : hvacControlPanel) {
            view.setEnabled(isEnable);
            view.setAlpha(targetAlpha);
        }

        mIsPanelEnabled = isEnable;
        updateFanButtonsState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
