package com.ktools.zspacecarplayer;

import android.app.Application;

import com.ktools.zspacecarplayer.crash.CrashMonitor;
import com.ktools.zspacecarplayer.ui.NightModeManager;

/**
 * 应用入口：唯一职责是在任何业务代码之前把崩溃监视装上。
 *
 * 车机上的故障（native SIGSEGV、主线程卡死）事后无从复现，装晚了就等于没有。
 */
public class ZSpaceApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashMonitor.install(this);
        // 深浅色必须在第一个 Activity 起来之前定档, 否则会先闪一帧白底
        NightModeManager.apply(this);
    }
}
