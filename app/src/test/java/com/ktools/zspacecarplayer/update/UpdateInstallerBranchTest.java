package com.ktools.zspacecarplayer.update;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

/**
 * 远程升级 (2026-09-12): 安装环节的 SDK 分支与配置契约。
 *
 * 这里测的不是「能不能装」(那要真机), 而是两件一旦写错就必然出事的事:
 *  1. SDK 分支的临界值。车机是 API 18 走 file://, 新设备 API 24+ 必须走 content://,
 *     26+ 还要先拿「安装未知应用」授权。临界值差一档就是 FileUriExposedException 直接崩;
 *  2. 三处必须一字不差的字符串: 代码里的 authority、AndroidManifest 里 provider 的
 *     authority、file_paths.xml 里的 cache-path。任一处对不上, API 24+ 上
 *     FileProvider.getUriForFile 会抛 IllegalArgumentException, 升级链路死在最后一步。
 */
public class UpdateInstallerBranchTest {

    private static final String PKG = "com.ktools.zspacecarplayer";

    // ------------------------------------------------------------ SDK 分支临界值

    @Test
    public void fileUriIsUsedBelowApi24() {
        // 车机 API 18 正是这一档: 老 ROM 的安装器对 content:// 支持参差, file:// 最稳
        Assert.assertFalse(UpdateInstaller.shouldUseFileProvider(18));
        Assert.assertFalse(UpdateInstaller.shouldUseFileProvider(19));
        Assert.assertFalse(UpdateInstaller.shouldUseFileProvider(21));
        Assert.assertFalse(UpdateInstaller.shouldUseFileProvider(23));
    }

    @Test
    public void fileProviderIsMandatoryFromApi24On() {
        // API 24 起 file:// 跨应用暴露会抛 FileUriExposedException, 没有回旋余地
        Assert.assertTrue(UpdateInstaller.shouldUseFileProvider(24));
        Assert.assertTrue(UpdateInstaller.shouldUseFileProvider(26));
        Assert.assertTrue(UpdateInstaller.shouldUseFileProvider(28));
        Assert.assertTrue("targetSdk 28 之上也要成立", UpdateInstaller.shouldUseFileProvider(34));
    }

    @Test
    public void unknownSourceGrantOnlyMattersFromApi26On() {
        // API 18 没有 per-app 的「安装未知应用」概念, 整段必须跳过, 否则反射都找不到方法
        Assert.assertFalse(UpdateInstaller.needsUnknownSourceGrant(18));
        Assert.assertFalse(UpdateInstaller.needsUnknownSourceGrant(24));
        Assert.assertFalse(UpdateInstaller.needsUnknownSourceGrant(25));
        Assert.assertTrue(UpdateInstaller.needsUnknownSourceGrant(26));
        Assert.assertTrue(UpdateInstaller.needsUnknownSourceGrant(34));
    }

    @Test
    public void carDeviceTakesTheFileUriPathWithoutAnyPermissionGate() {
        // 把车机那一档的两条分支合起来钉死: API 18 = file:// + 不查安装授权
        int carSdk = 18;
        Assert.assertFalse(UpdateInstaller.shouldUseFileProvider(carSdk));
        Assert.assertFalse(UpdateInstaller.needsUnknownSourceGrant(carSdk));
    }

    // ------------------------------------------------------------ 配置契约

    @Test
    public void authorityIsPackagePlusFileProviderSuffix() {
        Assert.assertEquals(PKG + ".fileprovider", UpdateInstaller.fileProviderAuthority(PKG));
    }

    @Test
    public void manifestDeclaresMatchingProviderPermissionAndPaths() {
        String manifest = readProjectFile("src/main/AndroidManifest.xml");
        Assume.assumeTrue("找不到 AndroidManifest.xml, 跳过配置契约检查", manifest != null);

        Assert.assertTrue("必须声明 REQUEST_INSTALL_PACKAGES (API 26+ 用)",
                manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        Assert.assertTrue("provider 必须是 androidx 版 FileProvider (项目用 androidx.appcompat)",
                manifest.contains("androidx.core.content.FileProvider"));
        Assert.assertFalse("不能混进 support 库的 FileProvider",
                manifest.contains("android.support.v4.content.FileProvider"));
        Assert.assertTrue("authority 必须与代码拼出来的一字不差",
                manifest.contains("android:authorities=\"${applicationId}.fileprovider\""));
        Assert.assertTrue(manifest.contains("@xml/file_paths"));
        Assert.assertTrue("provider 不能对外导出", manifest.contains("android:exported=\"false\""));
        Assert.assertTrue("必须允许临时授权, 否则安装器读不到 content://",
                manifest.contains("android:grantUriPermissions=\"true\""));
    }

    @Test
    public void filePathsCoversExactlyTheDownloadDirectory() {
        String paths = readProjectFile("src/main/res/xml/file_paths.xml");
        Assume.assumeTrue("找不到 file_paths.xml, 跳过配置契约检查", paths != null);

        Assert.assertTrue("下载目录是 getCacheDir()/" + ApkDownloader.UPDATE_DIR
                        + ", 必须由 cache-path 覆盖",
                paths.contains("cache-path"));
        Assert.assertTrue("path 必须指向 " + ApkDownloader.UPDATE_DIR + "/ 子目录, 不能是 '.'",
                paths.contains("path=\"" + ApkDownloader.UPDATE_DIR + "/\""));
        Assert.assertFalse("把整个 cache 暴露出去会连带泄露崩溃报告",
                paths.contains("path=\".\""));
        // 常量与 xml 是两处独立写死的字符串, 这里把它们的对应关系钉住
        Assert.assertEquals("updates", ApkDownloader.UPDATE_DIR);
    }

    // ------------------------------------------------------------ 结果与文案

    @Test
    public void installRefusesWithoutContext() {
        // 纯防御分支, 不碰任何 Android API, 单测里可以直接验
        Assert.assertEquals(UpdateInstaller.RESULT_BAD_FILE, UpdateInstaller.install(null, null));
        Assert.assertEquals(UpdateInstaller.RESULT_BAD_FILE,
                UpdateInstaller.install(null, new File("/tmp/whatever.apk")));
    }

    @Test
    public void everyInstallResultHasADistinctDriverFacingMessage() {
        int[] results = {
                UpdateInstaller.RESULT_STARTED,
                UpdateInstaller.RESULT_NEEDS_UNKNOWN_SOURCE,
                UpdateInstaller.RESULT_NO_INSTALLER,
                UpdateInstaller.RESULT_BAD_FILE,
        };
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int r : results) {
            String msg = UpdateInstaller.describeResult(r);
            Assert.assertNotNull(msg);
            Assert.assertTrue("文案不能为空 (result=" + r + ")", msg.length() > 0);
            Assert.assertFalse("文案不该是英文异常原文: " + msg, msg.contains("Exception"));
            Assert.assertTrue("四种结果的文案必须互不相同, 否则车主看不出差别: " + msg,
                    seen.add(msg));
        }
    }

    @Test
    public void successMessageTellsDriverToConfirmManually() {
        // 车机没有静默安装: 文案必须明确「要去系统界面上点安装」, 否则车主会一直干等
        String msg = UpdateInstaller.describeResult(UpdateInstaller.RESULT_STARTED);
        Assert.assertTrue(msg, msg.contains("安装"));
    }

    @Test
    public void unknownSourceMessageTellsDriverToGrantThenRetry() {
        String msg = UpdateInstaller.describeResult(UpdateInstaller.RESULT_NEEDS_UNKNOWN_SOURCE);
        Assert.assertTrue(msg, msg.contains("未知应用"));
        Assert.assertTrue("要说清授权后还得再点一次", msg.contains("重新"));
    }

    @Test
    public void unknownResultFallsBackToBadFileMessage() {
        Assert.assertEquals(UpdateInstaller.describeResult(UpdateInstaller.RESULT_BAD_FILE),
                UpdateInstaller.describeResult(999));
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 读工程内的配置文件。单测的工作目录通常是模块目录 (app/), 但也可能是仓库根,
     * 两种都试一遍; 都找不到就用 Assume 跳过, 不让环境差异变成红灯。
     */
    private static String readProjectFile(String relative) {
        String[] candidates = {
                relative,
                "app/" + relative,
                "../app/" + relative,
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) {
                try {
                    byte[] buf = new byte[(int) f.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    try {
                        int off = 0;
                        while (off < buf.length) {
                            int n = in.read(buf, off, buf.length - off);
                            if (n < 0) {
                                break;
                            }
                            off += n;
                        }
                    } finally {
                        in.close();
                    }
                    return new String(buf, "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
