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
* **UI 字形禁区：** Android 4.3 系统字库缺少绝大多数 emoji 与 Unicode 7 符号，按钮一律使用纯文本或 Unicode 1.1 老符号（▶ ◀ |）。

### 2.2 HTTPS 外网连接与 TLS 1.2 解锁 (`TLSSocketFactory`)
* **原理：** Android 4.3 原生 SSL 引擎默认禁用了 TLS 1.2 协议，导致访问外网 HTTPS (如 `https://your-jellyfin.example.com/music`) 报 `SSLHandshakeException`。
* **解决方案：** 项目内置 `TLSSocketFactory.java`，强行开启 `TLSv1.1` 与 `TLSv1.2`。
* **证书信任链 (`CompositeTrustManager`)：** Android 4.3 的系统证书库早于 Let's Encrypt，不信任 ISRG Root X1。项目将 `res/raw/isrg_root_x1.pem` 内置根证书与系统信任链合并校验，主机名走系统默认校验器。严禁 trust-all / 跳过主机名校验的写法。

### 2.3 IPv6 优先 DNS 优化 (`IPv6FirstDns`)
* **原理：** Android 4.3 原生 `bionic` C 库对 IPv6 AAAA 记录解析存在优先尝试 IPv4 的超时缺陷（每次请求延迟 3~5 秒）。
* **解决方案：** 项目内置 `IPv6FirstDns.java`，强制将 `Inet6Address` 提升至解析列表最前端。

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

### 3.1 账号鉴权与 Token 维护
* **请求 Header 规范：**
  ```http
  X-Emby-Authorization: MediaBrowser Client="ZSpaceCarPlayer", Device="Geely-iMX6-Car", DeviceId="CAR-IMX6-001", Version="1.0.0", Token="{accessToken}"
  ```
* **登录接口：** `POST /Users/AuthenticateByName`
* **Token 智能重试：** Token 失效时自动发起静默重登，无感刷新 Token。

### 3.2 媒体库曲目检索
* **接口与分页：** `GET /Users/{userId}/Items?IncludeItemTypes=Audio&Recursive=true&SortBy=SortName&Limit=500`
* **解析字段：** `Id`, `Name`, `Artists`, `Album`, `RunTimeTicks` (除以 10000 转换为毫秒)。

### 3.3 实时在线播放直链
* **直链 URL：** `{serverUrl}/Audio/{itemId}/stream.mp3?api_key={accessToken}&static=true`
* **播放处理：** 原生 `MediaPlayer.setDataSource(streamUrl)`，低 CPU 消耗边下边播。

### 3.4 高清封面与双端点 LRC 动态歌词
* **封面 URL：** `{serverUrl}/Items/{itemId}/Images/Primary?quality=90`
* **双端点歌词兼容：** 优先请求 Jellyfin 10.9+ 规范 `/Lyrics/{id}`，失败回退 `/Audio/{id}/Lyrics`，兼容量大主流 Jellyfin 服务端。

---

## 4. 1920×720 车载三栏式视觉与交互架构

针对车机带状特宽屏（`1920×640` 绘图区），采用**驾驶员导向的三栏式暗黑风 UI 布局**：

```
+-------------------+-----------------------------------+------------------------------------+
|  左侧导航 (220dp)  |    中间歌曲列表 (580dp)            |   右侧播放器与歌词区 (剩余空间)     |
|                   |                                   |                                    |
|  [ 全部歌曲 ]      | 1. 流行曲目 A       [ 03:45 ]     |         +----------------+         |
|  [ 刷新媒体库 ]    | 2. 摇滚经典 B       [ 04:20 ]     |         |  黑胶/专辑封面  |         |
|  [ 切换桌面模式 ]  | 3. 爵士轻音乐 C     [ 02:50 ]     |         +----------------+         |
|  [ 服务器设置 ]    | 4. 城市民谣 D       [ 05:10 ]     |     [当前实时滚动高亮 LRC 歌词...]  |
|                   | (列表中条目高度 76dp，适合行车触控) |  [|◀]     [  ▶ / ||  ]    [▶|]    |
+-------------------+-----------------------------------+------------------------------------+
```

---

## 5. 车载超级桌面 (Launcher) 接管与全屏沉浸

### 5.1 桌面 Intent 接管
在 `AndroidManifest.xml` 中声明 `MainActivity` 为系统 HOME 桌面（`<category android:name="android.intent.category.HOME" />`）。

### 5.2 窗口回焦与沉浸模式
实现 `onWindowFocusChanged` 自动补全，避免弹出系统弹窗或对话框后沉浸模式失效。

---

## 6. 后台保活服务与音频焦点避让

### 6.1 前台服务保活 (`AudioPlayerService`)
`AudioPlayerService` 绑定前台通知，兼容 Android 8.0+ NotificationChannel，在 Android 4.3 及新系统车机上均可防杀。监听 `BootReceiver` 实现开机后台启动。

### 6.2 高德导航播报自动降音与自动续播
* **CAN_DUCK (20% 降音)**：高德播报语音时，音量自动下调至 20%。
* **LOSS_TRANSIENT (暂停与自动续播)**：高德/语音助手抢占音频时自动暂停，广播结束自动续播。

---

## 7. 代码工程结构与一键编译部署

### 7.1 项目源码结构树
```
ZSpaceCarPlayer/
├── app/
│   ├── build.gradle                   # APK 优化精简 (4.1MB)
│   └── src/main/
│       ├── AndroidManifest.xml        # HOME 桌面、BootReceiver、前台服务
│       ├── java/com/ktools/zspacecarplayer/
│       │   ├── model/                 # SongItem, LyricLine (手写三向比较)
│       │   ├── net/                   # JellyfinApiClient, CompositeTrustManager, TLSSocketFactory
│       │   ├── service/               # AudioPlayerService (MediaPlayer, 焦点)
│       │   └── ui/                    # MainActivity, SongAdapter, LyricAdapter
│       └── res/                       # PNG 启动图标、老符号界面与 ViewBinding
├── deploy_to_car.sh                   # JDK/ANDROID_HOME 自检 + 自动构建部署脚本
└── README.md                          # 本文档
```

### 7.2 一键部署命令
```bash
cd /Users/cpuser/Code/kTool/ZSpaceCarPlayer
./deploy_to_car.sh
```
