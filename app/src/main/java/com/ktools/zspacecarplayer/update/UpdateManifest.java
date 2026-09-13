package com.ktools.zspacecarplayer.update;

import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 远程升级 (2026-09-12): 服务端版本清单模型。
 *
 * 清单是一份静态 JSON, 与服务器侧约定的字段见 DEFAULT 常量注释。这里刻意不用
 * Gson 的自动映射 (fromJson(json, UpdateManifest.class)), 而是逐字段手工取:
 * 手写 JSON 常见把数字加引号 ("versionCode":"4")、字段缺失、写成 null 三种脏形态,
 * 自动映射会直接抛 JsonSyntaxException 或塞进 0/null, 于是「明明有新版却判定为无」
 * 甚至 NPE 崩在车机上。逐字段容错 + 必填项显式校验, 坏了就抛 ParseException 说清原因。
 */
public final class UpdateManifest {

    private static final String TAG = "UpdateManifest";

    /** sha256 十六进制长度: 少一位就说明服务端填错了, 宁可不升级也不能装没校验过的包 */
    private static final int SHA256_HEX_LEN = 64;

    private final int versionCode;
    private final String versionName;
    private final String apkUrl;
    private final String fileName;
    private final long sizeBytes;
    /** 统一转小写保存, 比对时就不必再关心服务端填的是大写还是小写 */
    private final String sha256;
    private final String notes;
    private final int minVersionCode;
    private final boolean mandatory;
    private final String publishedAt;

    private UpdateManifest(int versionCode, String versionName, String apkUrl, String fileName,
                           long sizeBytes, String sha256, String notes, int minVersionCode,
                           boolean mandatory, String publishedAt) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.notes = notes;
        this.minVersionCode = minVersionCode;
        this.mandatory = mandatory;
        this.publishedAt = publishedAt;
    }

    /** 清单不合法。带明确中文原因, UI 直接展示给车主看, 不用再去猜 */
    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }

        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 解析清单 JSON。
     *
     * @throws ParseException 空响应 / 不是 JSON 对象 / 必填项缺失或格式错
     */
    public static UpdateManifest fromJson(String json) throws ParseException {
        if (json == null) {
            throw new ParseException("清单为空 (服务器没有返回内容)");
        }
        String trimmed = json.trim();
        if (trimmed.length() == 0) {
            throw new ParseException("清单为空 (服务器没有返回内容)");
        }
        // 站点 SPA 兜底页对任何未知路径都回 200 text/html, 车机拿到的会是一整张首页。
        // 这里先按首字符挡掉, 报错信息才有诊断价值 (否则 Gson 抛的是 "Expecting value" 之类天书)
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            throw new ParseException("清单地址返回的不是 JSON (可能是站点兜底页, 请确认服务端已部署 latest.json)");
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(trimmed);
        } catch (Throwable t) {
            throw new ParseException("清单 JSON 解析失败: " + t.getMessage(), t);
        }
        if (root == null || !root.isJsonObject()) {
            throw new ParseException("清单不是一个 JSON 对象");
        }
        JsonObject o = root.getAsJsonObject();

        int versionCode = intField(o, "versionCode", -1);
        if (versionCode <= 0) {
            throw new ParseException("清单缺少合法的 versionCode (读到 " + versionCode + ")");
        }

        String apkUrl = stringField(o, "apkUrl", "");
        if (apkUrl.length() == 0) {
            throw new ParseException("清单缺少 apkUrl, 无从下载");
        }
        String lower = apkUrl.toLowerCase(java.util.Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new ParseException("apkUrl 不是 http(s) 地址: " + apkUrl);
        }

        String sha256 = stringField(o, "sha256", "").trim().toLowerCase(java.util.Locale.US);
        if (sha256.length() != SHA256_HEX_LEN || !isHex(sha256)) {
            // sha256 是「校验失败绝不安装」的唯一凭据。缺了它就没法证明包没被中间人换过,
            // 因此按必填处理: 宁可提示升级失败, 也不能装一个来路不明的 APK。
            throw new ParseException("清单缺少合法的 sha256 (应为 " + SHA256_HEX_LEN
                    + " 位十六进制, 实际长度 " + sha256.length() + ")");
        }

        long sizeBytes = longField(o, "sizeBytes", -1L);
        if (sizeBytes < 0) {
            sizeBytes = -1L; // 未知大小: 跳过字节数校验, 仍以 sha256 为准
        }

        String fileName = stringField(o, "fileName", "");
        if (fileName.length() == 0) {
            fileName = ApkDownloader.fileNameFromUrl(apkUrl);
        }

        int minVersionCode = intField(o, "minVersionCode", 1);
        if (minVersionCode < 0) {
            minVersionCode = 1;
        }

        UpdateManifest m = new UpdateManifest(
                versionCode,
                stringField(o, "versionName", ""),
                apkUrl,
                fileName,
                sizeBytes,
                sha256,
                stringField(o, "notes", ""),
                minVersionCode,
                boolField(o, "mandatory", false),
                stringField(o, "publishedAt", ""));
        Log.i(TAG, "manifest parsed: " + m.describe());
        return m;
    }

    // ------------------------------------------------------------ 取值容错

    /** 字符串字段: 缺失/null 用默认值; 数字被写成字符串也照样收下 */
    private static String stringField(JsonObject o, String name, String fallback) {
        JsonElement e = o.get(name);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        try {
            if (e.isJsonPrimitive()) {
                return e.getAsString();
            }
        } catch (Throwable ignored) {
            // 落到下面统一返回 fallback
        }
        return fallback;
    }

    /** 整型字段: 兼容 4 / "4" / 4.0 三种写法 (手写 JSON 常混用) */
    private static int intField(JsonObject o, String name, int fallback) {
        JsonElement e = o.get(name);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            if (e.getAsJsonPrimitive().isNumber()) {
                return e.getAsInt();
            }
            return Integer.parseInt(e.getAsString().trim());
        } catch (Throwable t) {
            Log.w(TAG, "int field " + name + " unparsable, fallback " + fallback + ": " + t);
            return fallback;
        }
    }

    /** 长整型字段: 同上, 大小可能超过 int (虽然 APK 不会, 但别让 Gson 直接抛) */
    private static long longField(JsonObject o, String name, long fallback) {
        JsonElement e = o.get(name);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            if (e.getAsJsonPrimitive().isNumber()) {
                return e.getAsLong();
            }
            return Long.parseLong(e.getAsString().trim());
        } catch (Throwable t) {
            Log.w(TAG, "long field " + name + " unparsable, fallback " + fallback + ": " + t);
            return fallback;
        }
    }

    /** 布尔字段: 兼容 true / "true" / "1" */
    private static boolean boolField(JsonObject o, String name, boolean fallback) {
        JsonElement e = o.get(name);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            if (e.getAsJsonPrimitive().isBoolean()) {
                return e.getAsBoolean();
            }
            String s = e.getAsString().trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
                return false;
            }
        } catch (Throwable ignored) {
            // 落到 fallback
        }
        return fallback;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return s.length() > 0;
    }

    // ------------------------------------------------------------ 访问器

    public int getVersionCode() {
        return versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getApkUrl() {
        return apkUrl;
    }

    public String getFileName() {
        return fileName;
    }

    /** @return 清单声明的字节数; -1 表示服务端没给, 调用方跳过大小校验 */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** @return 已归一化为小写的 64 位十六进制 sha256 */
    public String getSha256() {
        return sha256;
    }

    public String getNotes() {
        return notes;
    }

    public int getMinVersionCode() {
        return minVersionCode;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    /** 一行日志用摘要: 实车抓 logcat 时靠它确认「清单到底说了什么」 */
    public String describe() {
        return "vc=" + versionCode + " vn=" + versionName + " minVc=" + minVersionCode
                + " mandatory=" + mandatory + " size=" + sizeBytes + " sha256=" + sha256
                + " file=" + fileName + " url=" + apkUrl;
    }

    @Override
    public String toString() {
        return "UpdateManifest{" + describe() + "}";
    }
}
