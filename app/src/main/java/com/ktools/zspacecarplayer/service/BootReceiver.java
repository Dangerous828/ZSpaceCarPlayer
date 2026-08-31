package com.ktools.zspacecarplayer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 车机开机后自动拉起播放服务 (配合 AndroidManifest 中的 RECEIVE_BOOT_COMPLETED)。
 * 由于 MainActivity 注册为 HOME 桌面, 系统启动后也会自动进入应用;
 * 这里保证即使桌面未被选中, 后台播放服务也处于就绪状态。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startService(new Intent(context, AudioPlayerService.class));
        }
    }
}
