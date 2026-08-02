package com.kpit.bluetooth.manager;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Backing implementation for the {@code MediaPlaybackInfo} AIDL parcelable (instruction.md
 * section VI). Populated by {@code IviBluetoothService} from an AVRCP
 * {@code MediaMetadata}/{@code PlaybackState} pair sourced from {@code BluetoothAvrcpController}.
 */
public final class MediaPlaybackInfo implements Parcelable {

    private final String title;
    private final String artist;
    private final String album;
    private final int playbackState;
    private final long positionMs;

    public MediaPlaybackInfo(String title, String artist, String album, int playbackState, long positionMs) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.playbackState = playbackState;
        this.positionMs = positionMs;
    }

    private MediaPlaybackInfo(Parcel in) {
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        playbackState = in.readInt();
        positionMs = in.readLong();
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public int getPlaybackState() {
        return playbackState;
    }

    public long getPositionMs() {
        return positionMs;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeInt(playbackState);
        dest.writeLong(positionMs);
    }

    public static final Creator<MediaPlaybackInfo> CREATOR = new Creator<MediaPlaybackInfo>() {
        @Override
        public MediaPlaybackInfo createFromParcel(Parcel in) {
            return new MediaPlaybackInfo(in);
        }

        @Override
        public MediaPlaybackInfo[] newArray(int size) {
            return new MediaPlaybackInfo[size];
        }
    };
}
