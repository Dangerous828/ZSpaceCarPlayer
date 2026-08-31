# 🚗 ZSpaceCarPlayer (极空间 / Jellyfin 车载超级桌面播放器) 全景技术规范与开发文档

> **文档版本：** v2.2 (精简版 - 移除 MQTT，专注 Jellyfin 实时在线听歌；代码与文档对齐)  
> **更新时间：** 2026-08-31  
> **目标系统：** 吉利 / 亿咖通 8600 经典固件 (`06.03.08600.H53.00060`, Android 4.3 / API 18)  
> **工程路径：** `/Users/cpuser/Code/kTool/ZSpaceCarPlayer`

---

## 目录
1. [设备环境与硬件/系统基线](#1-设备环境与硬件系统基线)
2. [Android 4.3 (API 18) 低版本兼容性全景规约](#2-android-43-api-18-低版本兼容性全景规约)
3. [Jellyfin 实时在线播放与 API 规范](#3-jellyfin-实时在线播放与-api-规范)
4. [1920×720 车载三栏式视觉与交互架构](#4-1920720-车载三栏式视觉与交互架构)
5. [车载超级桌面 (Launcher) 接管与全屏沉浸](#5-车载超级桌面-launcher-接管与全屏沉浸)
6. [后台保活服务与音频焦点避让](#6-后台保活服务与音频焦点避让)
7. [代码工程结构与一键编译部署](#7-代码工程结构与一键编译部署)

---

## 1. 设备环境与硬件/系统基线

### 1.1 车机硬件参数
* **处理器 (CPU)：** Freescale i.MX 6Quad (4核 Cortex-A9 @ 1.0GHz)
* **运行内存 (RAM)：** 1.6 GB (可用约 600MB+)
* **存储空间 (Storage)：** 内部存储剩余 18.4 GB
* **显示屏分辨率：** 1920 × 720 (应用有效绘图区 1920 × 640)
* **系统固件版本：** `06.03.08600.H53.00060` (Build Date: 2019-05-18)
* **Android 运行环境：** Android 4.3 (Jelly Bean) / API Level 18 / Dalvik 虚拟机

### 1.2 8600 系统原生优势
* **纯净无管控拦截：** 相比 9500 固件，8600 系统无 `ApkAuth` 白名单校验，允许自由安装/替换第三方应用与 Launcher。
* **三大金刚底栏原生稳定：** 底栏为原生系统级 View，不会因为应用崩溃而隐去或触发看门狗重启。
* **无限自启 ADB：** `/system/etc/install-recovery.sh` 已固化自动配置 `setprop persist.adb.tcp.port 5555`，开机即开启无线 ADB 调试。

---

## 2. Android 4.3 (API 18) 低版本兼容性全景规约

由于目标设备运行古老的 Android 4.3 (API 18) 与 Dalvik 虚拟机，必须严格遵守以下 5 大低版本兼容规约：

### 2.1 Java 语言规范与 API 级别限制
* **语言级别限制：** `sourceCompatibility 1.8`（AGP 8.x 最低要求），但语法一律按 Java 7 风格书写，不使用 Lambda / Stream / try-with-resources 之外的 Java 8 特性。
* **API 禁区：** 严禁调用 API 19+ 才引入的方法。典型陷阱：`Long.compare` / `Integer.compare` 是 **API 19** 才加入的，在 4.3 上会抛 `NoSuchMethodError`，必须手写三向比较。同样禁用 `java.time.*`、`java.util.Optional`。
* **UI 字形禁区：** Android 4.3 系统字库缺少绝大多数 emoji 与 Unicode 7 符号（⏮ ⏭ ⏸ 🔁 ️ 等会显示为豆腐块），按钮一律使用纯文本或 Unicode 1.1 老符号（▶ ◀ |）。

### 2.2 HTTPS 外网连接与 TLS 1.2 解锁 (`TLSSocketFactory`)
* **原理：** Android 4.3 原生 SSL 引擎默认禁用了 TLS 1.2 协议，导致访问外网 HTTPS (如 `https://your-jellyfin.example.com/music`) 报 `SSLHandshakeException`。
* **解决方案：** 项目内置 `TLSSocketFactory.java`，强行开启 `TLSv1.1` 与 `TLSv1.2`：
  ```java
  public class TLSSocketFactory extends SSLSocketFactory {
      private SSLSocketFactory delegate;
      public TLSSocketFactory(TrustManager[] trustManagers) throws Exception {
          SSLContext context = SSLContext.getInstance("TLS");
          context.init(null, trustManagers, null);
          delegate = context.getSocketFactory();
      }
      @Override
      public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
          Socket socket = delegate.createSocket(s, host, port, autoClose);
          if (socket instanceof SSLSocket) {
              ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.1", "TLSv1.2"});
          }
          return socket;
      }
  }
  ```
* **证书信任链 (`CompositeTrustManager`)：** Android 4.3 的系统证书库早于 Let's Encrypt，不信任 ISRG Root X1。项目将 `res/raw/isrg_root_x1.pem` 内置根证书与系统信任链合并校验，主机名走系统默认校验器。**严禁** trust-all / 跳过主机名校验的写法（中间人风险）。

### 2.3 IPv6 优先 DNS 优化 (`IPv6FirstDns`)
* **原理：** Android 4.3 原生 `bionic` C 库对 IPv6 AAAA 记录解析存在优先尝试 IPv4 的超时缺陷（每次请求延迟 3~5 秒）。
* **解决方案：** 项目内置 `IPv6FirstDns.java`，实现 OkHttp 的 `Dns` 接口，强制将 `Inet6Address` 提升至解析列表最前端：
  ```java
  public class IPv6FirstDns implements Dns {
      @Override
      public List<InetAddress> lookup(String hostname) throws UnknownHostException {
          List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
          List<InetAddress> result = new ArrayList<>();
          for (InetAddress addr : addresses) {
              if (addr instanceof Inet6Address) {
                  result.add(0, addr);
              } else {
                  result.add(addr);
              }
          }
          return result.isEmpty() ? addresses : result;
      }
  }
  ```

### 2.4 第三方依赖库锁死卡位
* **OkHttp：** 强行锁定为 `com.squareup.okhttp3:okhttp:3.12.13`（支持 API 18 的最后一个维护版本）。
* **Gson：** `com.google.code.gson:gson:2.10.1`。
* **Glide：** `com.github.bumptech.glide:glide:4.12.0` (兼容低版本图片解码与圆角裁剪)。
* **AndroidX：** `androidx.appcompat:appcompat:1.3.1`, `androidx.recyclerview:recyclerview:1.2.1`。严禁引入 Material Design 3 或 Jetpack Compose！

### 2.5 NDK 与 Native 架构
* **只允许 32 位 `armeabi-v7a`：** 任何第三方 `.so` 必须只包含 `armeabi-v7a` 架构，严禁打入 64 位 `arm64-v8a`。

---

## 3. Jellyfin 实时在线播放与 API 规范

应用后端全面对接极空间 NAS 上运行的 **Jellyfin Docker** (端口 `8096` / 外网 `443` 反代)，纯 HTTP/HTTPS 实时流媒体在线播放：

```
[车机 App (ZSpaceCarPlayer)]
       │
       │ HTTP / HTTPS 实时请求 (Jellyfin REST API)
       ▼
[极空间 NAS (Jellyfin Docker)] ───► 实时边下边播音频流 (stream.mp3)
```

### 3.1 账号鉴权与 Header 构造
* **请求 Header 规范：**
  ```http
  X-Emby-Authorization: MediaBrowser Client="ZSpaceCarPlayer", Device="Geely-iMX6-Car", DeviceId="CAR-IMX6-001", Version="1.0.0", Token="{accessToken}"
  ```
* **登录接口：** `POST /Users/AuthenticateByName`
  * Body: `{"Username": "xxx", "Pw": "xxx"}`
  * 响应解析获取 `AccessToken` 与 `UserId`。

### 3.2 音乐库曲目检索
* **接口：** `GET /Users/{userId}/Items?IncludeItemTypes=Audio&Recursive=true&Fields=MediaSources,ParentId&SortBy=SortName&SortOrder=Ascending&Start={n}&Limit=500`
* **分页：** 客户端按 500 条/页循环拉取直至取完（`TotalRecordCount` 或返回不足一页为止），避免一次性解析超大 JSON 卡死老车机。
* **解析字段：** `Id`, `Name`, `Artists`, `Album`, `RunTimeTicks` (除以 10000 转换为毫秒)。

### 3.3 实时在线播放直链 (网络缓冲即点即播)
* **直链 URL：** `{serverUrl}/Audio/{itemId}/stream.mp3?api_key={accessToken}&static=true`
* **播放处理：** 直接将 URL 送入原生 `MediaPlayer.setDataSource(streamUrl)`，支持边下边播与分片 Seek 拖动，零本地存储占用。

### 3.4 高清封面与 LRC 动态歌词
* **封面 URL：** `{serverUrl}/Items/{itemId}/Images/Primary?quality=90`
* **LRC 歌词接口：** 优先 Jellyfin 10.9+ 官方接口 `GET /Lyrics/{itemId}`，失败自动回退旧接口 `GET /Audio/{itemId}/Lyrics`；两种响应结构（顶层 `Lyrics` 数组、嵌套 `{Lyrics:{Metadata, Lyrics:[...]}}`）均兼容解析。
  * 自动解析 `Lyrics` 数组中每行的 `Start` ticks 并转换 `[mm:ss.ms]`，驱动 UI 实时滚动高亮。

---

## 4. 1920×720 车载三栏式视觉与交互架构

针对车机带状特宽屏（`1920×640` 绘图区），采用**驾驶员导向的三栏式暗黑风 UI 布局**：

```
+-------------------+-----------------------------------+------------------------------------+
|  左侧导航 (220dp)  |    中间歌曲列表 (580dp)            |   右侧播放器与歌词区 (剩余空间)     |
|                   |                                   |                                    |
|  [ 全部歌曲 ]      | 1. 流行曲目 A       [ 03:45 ]     |         +----------------+         |
|  [ 刷新媒体库 ]    | 2. 摇滚经典 B       [ 04:20 ]     |         |  黑胶/专辑封面  |         |
|  [ 全屏沉浸 ]      | 3. 爵士轻音乐 C     [ 02:50 ]     |         |  (240x240 dp)  |         |
|  [ 服务器配置 ]    | 4. 城市民谣 D       [ 05:10 ]     |         +----------------+         |
|                   | (列表中条目高度 76dp，适合行车触控) |     [当前实时滚动高亮 LRC 歌词...]  |
|                   |                                   | [顺序] [|◀]  [ ▶ / || ]  [▶|]      |
+-------------------+-----------------------------------+------------------------------------+
```
* 按钮文字一律纯文本或 Unicode 1.1 老符号（见 §2.1 字形禁区），4.3 字库可正常渲染。

---

## 5. 车载超级桌面 (Launcher) 接管与全屏沉浸

### 5.1 桌面 Intent 接管
在 `AndroidManifest.xml` 中将 `MainActivity` 声明为 Android 系统级别 HOME 桌面：
```xml
<activity
    android:name=".ui.MainActivity"
    android:configChanges="orientation|keyboardHidden|screenSize"
    android:exported="true"
    android:launchMode="singleTask"
    android:screenOrientation="landscape">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
        <!-- 注册为车载超级桌面 (HOME Launcher) -->
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

### 5.2 全屏沉浸式体验
通过 `setSystemUiVisibility` 开启全屏沉浸：
```java
getWindow().getDecorView().setSystemUiVisibility(
    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    | View.SYSTEM_UI_FLAG_FULLSCREEN
    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
);
```

---

## 6. 后台保活服务与音频焦点避让

### 6.1 前台服务保活 (`AudioPlayerService`)
`AudioPlayerService` 启动时绑定前台通知（Android 8+ 自动创建 `car_player_channel` 渠道；小图标使用真实 PNG `ic_notification`，避免 shape drawable 在部分 ROM 上崩通知）：
```java
startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("ZSpace Car Player 运行中")
        .setContentText(currentSongTitle)
        ...
        .build());
```
* **开机自启：** `BootReceiver` 监听 `BOOT_COMPLETED`，车机重启后自动拉起播放服务。

### 6.2 高德导航播报自动降音避让
在 `AudioPlayerService.java` 中注册 `AudioManager.OnAudioFocusChangeListener`：
* 当高德地图 (`AMapAuto`) 播报导航语音发出 `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` 信号时，服务自动将音乐音量降至 20%。
* 导航播报结束，自动恢复 100% 音量。

---

## 7. 代码工程结构与一键编译部署

### 7.1 项目源码结构树
```
ZSpaceCarPlayer/
├── app/
│   ├── build.gradle                   # minSdk 18, targetSdk 28, OkHttp 3.12.13 依赖
│   └── src/main/
│       ├── AndroidManifest.xml        # HOME 桌面注册、权限、前台服务与开机自启声明
│       ├── java/com/ktools/zspacecarplayer/
│       │   ├── model/                 # SongItem, LyricLine 数据模型
│       │   ├── net/                   # JellyfinApiClient, TLSSocketFactory, IPv6FirstDns
│       │   ├── service/               # AudioPlayerService (MediaPlayer, 音频焦点避让), BootReceiver
│       │   └── ui/                    # MainActivity, SongAdapter, LyricAdapter
│       └── res/                       # 1920×720 三栏式布局与 drawable
│           ├── drawable/              # ic_launcher_car.png, ic_notification.png 等
│           └── raw/isrg_root_x1.pem   # 内置 Let's Encrypt 根证书 (4.3 系统证书库过老)
├── deploy_to_car.sh                   # 自动编译并 ADB 部署到 10.212.252.52:5555
└── README.md                          # 本文档
```

### 7.2 一键部署命令
在 Mac 终端中运行：
```bash
cd /Users/cpuser/Code/kTool/ZSpaceCarPlayer
./deploy_to_car.sh
```
该脚本会自动调用 Android SDK 编译 Debug APK，并通过网络 ADB 自动推送到 `10.212.252.52:5555` 车机并启动应用！
