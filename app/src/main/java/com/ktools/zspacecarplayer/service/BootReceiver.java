package com.ktools.zspacecarplayer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.ktools.zspacecarplayer.db.SongDao;
import com.ktools.zspacecarplayer.ui.MainActivity;

/**
 * 车机开机后自动拉起播放器与前台界面。8600 车机 (亿咖通 ROM) 不一定给三方应用发
 * BOOT_COMPLETED, 故同时监听 QUICKBOOT_POWERON 与 CONNECTIVITY_CHANGE
 * (车机上电联网必然触发, 等价于迟到的开机事件) 做兜底。
 * 从未播放过 (无 last_song_id 历史) 时不拉起服务: 无历史不抢播。
 * 单次进程生命周期内只触发一次开机拉起 UI, 防止行车中网络波动 (CONNECTIVITY_CHANGE) 反复弹窗。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    static final String ACTION_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";

    private static boolean bootLaunched = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        boolean trigger = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || ACTION_CONNECTIVITY_CHANGE.equals(action);
        if (!trigger) return;

        // 行车中网络重连兜底防打扰: 若本次开机已经拉起过, 不再重复弹起 UI
        if (ACTION_CONNECTIVITY_CHANGE.equals(action) && bootLaunched) {
            return;
        }

        String lastSongId = SongDao.getInstance(context).getState("last_song_id", "");
        if (lastSongId == null || lastSongId.trim().isEmpty()) return;

        bootLaunched = true;
        Log.i(TAG, "Boot trigger: " + action + ", starting AudioPlayerService and MainActivity");

        // 1. 启动后台播放服务
        context.startService(new Intent(context, AudioPlayerService.class));

        // 2. 拉起前台 UI 界面，避免暗中偷跑抢焦点
        Intent activityIntent = new Intent(context, MainActivity.class);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(activityIntent);
    }
}
