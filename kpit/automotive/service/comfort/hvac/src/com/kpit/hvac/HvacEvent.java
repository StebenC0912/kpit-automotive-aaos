package com.kpit.hvac;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class HvacEvent implements Parcelable {
    private int id;
    private int areaId;
    private float value;
    public HvacEvent(int id, int areaId, float value) {
        this.id = id;
        this.areaId = areaId;
        this.value = value;
    }
    protected HvacEvent(Parcel in) {
        id = in.readInt();
        areaId = in.readInt();
        value = in.readFloat();
    }

    public static final Creator<HvacEvent> CREATOR = new Creator<HvacEvent>() {
        @Override
        public HvacEvent createFromParcel(Parcel in) {
            return new HvacEvent(in);
        }

        @Override
        public HvacEvent[] newArray(int size) {
            return new HvacEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeInt(areaId);
        dest.writeFloat(value);
    }

    public int getId() {
        return id;
    }

    public int getAreaId() {
        return areaId;
    }

    public float getValue() {
        return value;
    }

    @NonNull
    @Override
    public String toString() {
        return "HvacEvent{" +
                "id=" + id +
                ", areaId=" + areaId +
                ", value=" + value +
                '}';
    }
}
