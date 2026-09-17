package com.ktools.zspacecarplayer.update;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * 远程升级 (2026-09-12): 拉起系统安装界面。
 *
 * 边界说明: 车机上的 App 没有静默安装权限 (那是系统签名/DeviceOwner 才有的能力),
 * 所以这里做的事只有一件 —— 把校验通过的 APK 交给系统 PackageInstaller,
 * 由车主在系统界面上手动点「安装」。这也是刻意的设计: OTA 不能绕过人。
 *
 * SDK 分支 (车机实为 API 18, 但 targetSdk 28 意味着新设备上也不能崩):
 *  - API 24+ : 必须用 FileProvider 生成 content:// 。file:// 会被 StrictMode 的
 *              FileUriExposedException 直接拦下 (崩溃), 因为跨应用暴露私有文件路径不安全。
 *  - API <24 : 直接用 Uri.fromFile 的 file:// 。这条正是车机 API 18 走的路径;
 *              FileProvider 在低版本虽然也能用, 但老 ROM 的安装器对 content:// 支持参差,
 *              file:// 反而是最稳的。
 *  - API 26+ : 还要 packageManager.canRequestPackageInstalls()。为 false 时先引导去
 *              「允许安装未知应用」设置页, 不直接抛给安装器一个必然失败的动作。
 *              API 18 没有这个概念 (全局「未知来源」开关由安装器自己提示), 整段跳过。
 */
public final class UpdateInstaller {

    private static final String TAG = "UpdateInstaller";

    /** 系统安装界面已成功拉起 */
    public static final int RESULT_STARTED = 0;
    /** API 26+ 未授予「安装未知应用」, 已尝试打开授权设置页 */
    public static final int RESULT_NEEDS_UNKNOWN_SOURCE = 1;
    /** 车机上找不到任何能处理安装意图的组件 */
    public static final int RESULT_NO_INSTALLER = 2;
    /** 文件不存在/为空/无法生成 URI */
    public static final int RESULT_BAD_FILE = 3;

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private UpdateInstaller() {}

    /**
     * 拉起系统安装界面。
     *
     * @return 上述 RESULT_* 之一; 调用方据此给出对应文案
     */
    public static int install(Context context, File apk) {
        if (context == null) {
            return RESULT_BAD_FILE;
        }
        if (apk == null || !apk.isFile() || apk.length() <= 0) {
            Log.w(TAG, "install refused, apk missing or empty: " + apk);
            return RESULT_BAD_FILE;
        }
        int sdk = Build.VERSION.SDK_INT;

        if (needsUnknownSourceGrant(sdk) && !canRequestPackageInstalls(context)) {
            Log.w(TAG, "sdk=" + sdk + " but canRequestPackageInstalls=false, opening settings");
            openUnknownSourceSettings(context);
            return RESULT_NEEDS_UNKNOWN_SOURCE;
        }

        Uri uri;
        try {
            if (shouldUseFileProvider(sdk)) {
                uri = FileProvider.getUriForFile(context,
                        fileProviderAuthority(context.getPackageName()), apk);
            } else {
                // API < 24 (车机 API 18 走这条): file:// 尚未被 FileUriExposedException 禁止。
                // 必须放开目录与文件权限为全局可读, 否则 PackageInstaller (独立 UID)
                // 读取 apk 文件时会遭遇 EACCES 权限被拒, 在系统界面报「解析程序包时出现问题」!
                makeReadableForInstaller(apk);
                uri = Uri.fromFile(apk);
            }
        } catch (Throwable t) {
            // 常见原因: file_paths.xml 没覆盖到该目录 → IllegalArgumentException
            Log.w(TAG, "build apk uri failed sdk=" + sdk + ": " + t);
            return RESULT_BAD_FILE;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, APK_MIME);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
            Log.i(TAG, "install intent fired sdk=" + sdk + " uri=" + uri
                    + " size=" + apk.length());
            return RESULT_STARTED;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "ACTION_VIEW has no installer, trying ACTION_INSTALL_PACKAGE: " + e);
        } catch (Throwable t) {
            Log.w(TAG, "startActivity(ACTION_VIEW) failed: " + t);
        }
        // 兜底: 个别精简 ROM 只登记了 ACTION_INSTALL_PACKAGE
        try {
            Intent alt = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            alt.setData(uri);
            alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(alt);
            Log.i(TAG, "install intent fired via ACTION_INSTALL_PACKAGE sdk=" + sdk);
            return RESULT_STARTED;
        } catch (Throwable t) {
            Log.w(TAG, "no installer available on this device: " + t);
            return RESULT_NO_INSTALLER;
        }
    }

    /**
     * 打开「允许安装未知应用」授权页 (API 26+)。
     *
     * @return true 表示设置页已拉起
     */
    public static boolean openUnknownSourceSettings(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 26) {
            // API 18 没有 per-app 的未知来源授权, 全局开关由系统安装器自己提示, 这里什么都不做
            return false;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "unknown-app-sources settings opened");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ACTION_MANAGE_UNKNOWN_APP_SOURCES unavailable: " + t);
        }
        try {
            Intent fallback = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "security settings fallback failed: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ 纯判定 (可单测)

    /** API 24 (N) 起 file:// 跨应用暴露会抛 FileUriExposedException, 必须换 content:// */
    static boolean shouldUseFileProvider(int sdkInt) {
        return sdkInt >= 24;
    }

    /** API 26 (O) 起「安装未知应用」是 per-app 运行时授权 */
    static boolean needsUnknownSourceGrant(int sdkInt) {
        return sdkInt >= 26;
    }

    /** 与 AndroidManifest 里 provider 的 authority 必须一字不差 */
    static String fileProviderAuthority(String packageName) {
        return packageName + ".fileprovider";
    }

    /** 安装结果 → 车主文案 (纯函数, 便于单测锁住措辞) */
    public static String describeResult(int result) {
        switch (result) {
            case RESULT_STARTED:
                return "已打开系统安装界面, 请在屏幕上点「安装」完成升级";
            case RESULT_NEEDS_UNKNOWN_SOURCE:
                return "需要先在设置里允许本应用「安装未知应用」, 授权后请重新点一次安装";
            case RESULT_NO_INSTALLER:
                return "车机上没有找到可用的安装程序, 无法完成升级";
            case RESULT_BAD_FILE:
            default:
                return "安装包文件无效, 请重新下载";
        }
    }

    private static boolean canRequestPackageInstalls(Context context) {
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            // 个别 ROM 阉割了该 API: 探测不出来就放行, 让系统安装器自己去拦,
            // 总比在这里返回 false 把升级路径彻底堵死要好
            Log.w(TAG, "canRequestPackageInstalls probe failed, assume granted: " + t);
            return true;
        }
    }

    /**
     * 将 updates 目录与 apk 设为全局可读, 供低版本 Android 系统安装器跨 UID 读取。
     */
    static void makeReadableForInstaller(File apk) {
        if (apk == null) return;
        try {
            apk.setReadable(true, false);
            File parent = apk.getParentFile();
            if (parent != null) {
                parent.setReadable(true, false);
                parent.setExecutable(true, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "makeReadableForInstaller failed: " + t);
        }
    }
}
