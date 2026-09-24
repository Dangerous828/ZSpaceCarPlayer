package com.ktools.zspacecarplayer.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import java.util.Calendar;

/**
 * 白天 / 夜间配色档位。
 *
 * 档位只有三态，实际深浅色由 AppCompat 改写 Configuration 后加载 values-night/ 资源实现，
 * 布局与 drawable 全部引用 @color token，所以这里不碰任何视图。
 */
public final class NightModeManager {

    public static final int MODE_AUTO = 0;
    public static final int MODE_DAY = 1;
    public static final int MODE_NIGHT = 2;

    public static final String PREF_NAME = "zspace_ui";
    private static final String KEY_MODE = "night_mode";

    /** 自动档入夜/出夜时刻: 车机拿不到大灯光控信号, 本地时间是最可靠的代理 */
    private static final int NIGHT_FROM_HOUR = 18;
    private static final int NIGHT_TO_HOUR = 6;

    private NightModeManager() {}

    public static int getMode(Context context) {
        return prefs(context).getInt(KEY_MODE, MODE_AUTO);
    }

    public static void setMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply();
        apply(context);
    }

    public static String modeLabel(int mode) {
        if (mode == MODE_DAY) return "白天";
        if (mode == MODE_NIGHT) return "夜间";
        return "自动";
    }

    /**
     * 把档位换算成实际深浅色并交给 AppCompat。
     * 传入与当前相同的值是空操作，不会重建界面，因此可以在 onResume 里放心重复调用。
     */
    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(isNight(context)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    public static boolean isNight(Context context) {
        return isNightAt(getMode(context), Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
    }

    /** 自动档按小时判定的纯函数面: 18 点整入夜, 6 点整出夜 */
    static boolean isNightAt(int mode, int hourOfDay) {
        if (mode == MODE_DAY) return false;
        if (mode == MODE_NIGHT) return true;
        return hourOfDay >= NIGHT_FROM_HOUR || hourOfDay < NIGHT_TO_HOUR;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
