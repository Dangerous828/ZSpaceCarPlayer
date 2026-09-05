package com.ktools.zspacecarplayer.dsp;

import android.util.Log;

public class NativeDsp {
    private static final String TAG = "NativeDsp";
    private static boolean isLoaded = false;

    static {
        try {
            System.loadLibrary("zspacecarplayer_dsp");
            isLoaded = true;
            Log.i(TAG, "libzspacecarplayer_dsp.so loaded successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load libzspacecarplayer_dsp.so", t);
        }
    }

    public static boolean isAvailable() {
        return isLoaded;
    }

    public static void init(int sampleRate, int channels) {
        if (!isLoaded) return;
        try {
            nativeInit(sampleRate, channels);
        } catch (Throwable t) {
            Log.e(TAG, "nativeInit error", t);
        }
    }

    public static void reset() {
        if (!isLoaded) return;
        try {
            nativeReset();
        } catch (Throwable t) {
            Log.e(TAG, "nativeReset error", t);
        }
    }

    public static void setEqualizerPreset(int preset) {
        if (!isLoaded) return;
        try {
            Log.d(TAG, "setEqualizerPreset: " + preset);
            nativeSetEqualizerPreset(preset);
        } catch (Throwable t) {
            Log.e(TAG, "nativeSetEqualizerPreset error", t);
        }
    }

    public static void setEqualizerBand(int band, float gainDb) {
        if (!isLoaded) return;
        try {
            Log.d(TAG, "setEqualizerBand: band=" + band + ", gainDb=" + gainDb);
            nativeSetEqualizerBand(band, gainDb);
        } catch (Throwable t) {
            Log.e(TAG, "nativeSetEqualizerBand error", t);
        }
    }

    public static void setBassBoost(int percent) {
        if (!isLoaded) return;
        try {
            Log.d(TAG, "setBassBoost: " + percent + "%");
            nativeSetBassBoost(percent);
        } catch (Throwable t) {
            Log.e(TAG, "nativeSetBassBoost error", t);
        }
    }

    public static void setVirtualizer(int percent) {
        if (!isLoaded) return;
        try {
            Log.d(TAG, "setVirtualizer: " + percent + "%");
            nativeSetVirtualizer(percent);
        } catch (Throwable t) {
            Log.e(TAG, "nativeSetVirtualizer error", t);
        }
    }

    public static void setReverb(int mode) {
        if (!isLoaded) return;
        try {
            Log.d(TAG, "setReverb: mode=" + mode);
            nativeSetReverb(mode);
        } catch (Throwable t) {
            Log.e(TAG, "nativeSetReverb error", t);
        }
    }

    public static void processShorts(short[] buffer, int offset, int numFrames) {
        if (!isLoaded || buffer == null || numFrames <= 0) return;
        try {
            nativeProcessShorts(buffer, offset, numFrames);
        } catch (Throwable t) {
            Log.e(TAG, "nativeProcessShorts error", t);
        }
    }

    public static void processBytes(byte[] buffer, int byteOffset, int numBytes) {
        if (!isLoaded || buffer == null || numBytes <= 0) return;
        try {
            nativeProcessBytes(buffer, byteOffset, numBytes);
        } catch (Throwable t) {
            Log.e(TAG, "nativeProcessBytes error", t);
        }
    }

    // Native methods
    private static native void nativeInit(int sampleRate, int channels);
    private static native void nativeReset();
    private static native void nativeSetEqualizerPreset(int preset);
    private static native void nativeSetEqualizerBand(int band, float gainDb);
    private static native void nativeSetBassBoost(int percent);
    private static native void nativeSetVirtualizer(int percent);
    private static native void nativeSetReverb(int mode);
    private static native void nativeProcessShorts(short[] buffer, int offset, int numFrames);
    private static native void nativeProcessBytes(byte[] buffer, int byteOffset, int numBytes);
}
