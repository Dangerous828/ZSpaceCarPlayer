# 🚗 ZSpaceCarPlayer (极空间 / Jellyfin 车载桌面播放器) 全景技术规范与开发文档

> **文档版本：** v2.3.0 (SQLite 本地持久化 + 流派/红心歌单 + 车载 EQ 与方控按键 + 拼音搜索与断点续播全量支持)  
> **更新时间：** 2026-09-01  
> **目标系统：** 吉利 / 亿咖通 8600 经典固件 (`06.03.08600.H53.00060`, Android 4.3 / API 18)  
> **工程路径：** `/Users/cpuser/Code/kTool/ZSpaceCarPlayer`

---

## 目录
1. [设备环境与硬件/系统基线](#1-设备环境与硬件系统基线)
2. [Android 4.3 (API 18) 低版本兼容性全景规约](#2-android-43-api-18-低版本兼容性全景规约)
3. [Jellyfin 实时在线播放与 API 规范](#3-jellyfin-实时在线播放与-api-规范)
4. [1920×720 车载顶部工具栏 + 双栏式视觉与交互架构](#4-1920720-车载顶部工具栏--双栏式视觉与交互架构)
5. [常规自启应用定位与系统栏展示](#5-常规自启应用定位与系统栏展示)
6. [后台保活服务、方控按键与音频焦点避让](#6-后台保活服务方控按键与音频焦点避让)
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
* **纯净无管控拦截：** 相比 9500 固件，8600 系统无 `ApkAuth` 白名单校验，允许自由安装第三方应用。
* **三大金刚底栏原生稳定：** 底栏为原生系统级 View，不会因为应用崩溃而隐去或触发看门狗重启。
* **无限自启 ADB：** `/system/etc/install-recovery.sh` 已固化自动配置 `setprop persist.adb.tcp.port 5555`，开机即开启无线 ADB 调试。

---

## 2. Android 4.3 (API 18) 低版本兼容性全景规约

由于目标设备运行古老的 Android 4.3 (API 18) 与 Dalvik 虚拟机，必须严格遵守以下 5 大低版本兼容规约：

### 2.1 Java 语言规范与 API 级别限制
* **语言级别限制：** `sourceCompatibility 1.8`，但语法一律按 Java 7 风格书写，不使用 Lambda / Stream / try-with-resources。
* **API 禁区：** 严禁调用 API 19+ 才引入的方法。典型陷阱：`Long.compare` / `Integer.compare` / `Objects.equals` 是 **API 19** 才加入的，手写三向与判等比较。禁用 `java.time.*`、`java.util.Optional`。
* **UI 字形规约：** 按钮与输入框一律使用纯文本或 Unicode 标准老字符（▶ ◀ | ♥），杜绝危险 Emoji 渲染。

### 2.2 HTTP 明文直连与 TLS 1.2 解锁 (`TLSSocketFactory`)
* **原理：** Android 4.3 原生 SSL 引擎默认禁用 TLS 1.2，且系统 Conscrypt 仅支持 CBC 加密套件，与 Cloudflare 默认下发的 GCM/CHACHA20 无交集，走 HTTPS 必报 `SSL handshake aborted`。
* **解决方案：** 默认使用 `http://your-jellyfin.example.com/music` 明文公网直连，并在 Cloudflare 关闭「Always Use HTTPS」强制跳转；`TLSSocketFactory.java` 保留供自配 HTTPS 源时强制开启 `TLSv1.1/1.2` 并补齐 ECDSA GCM 套件。
* **证书信任链 (`CompositeTrustManager`)：** 自配 HTTPS 源时，将 `res/raw/gts_root_r4.pem` 内置根证书与系统信任链合并校验（4.3 系统证书库过老，不认 GTS Root R4）。

### 2.3 IPv6 优先 DNS 优化 (`IPv6FirstDns`)
* **原理：** Android 4.3 原生 `bionic` C 库对 IPv6 AAAA 记录解析存在超时缺陷。
* **解决方案：** 项目内置 `IPv6FirstDns.java` 强制提升 `Inet6Address` 优先解析。

### 2.4 第三方依赖库锁死
* **OkHttp：** 强行锁定为 `com.squareup.okhttp3:okhttp:3.12.13`（支持 API 18 的最后一个维护版本）。
* **Gson：** `com.google.code.gson:gson:2.10.1`。
* **Glide：** `com.github.bumptech.glide:glide:4.12.0`。
* **AndroidX：** `androidx.appcompat:appcompat:1.3.1`, `androidx.recyclerview:recyclerview:1.2.1`。

---

## 3. Jellyfin 实时在线播放与 API 规范

### 3.1 账号鉴权与 Token 维护
* **请求 Header 规范：**
  ```http
  X-Emby-Authorization: MediaBrowser Client="ZSpaceCarPlayer", Device="Geely-iMX6-Car", DeviceId="CAR-IMX6-001", Version="2.3.0", Token="{accessToken}"
  ```
* **登录接口：** `POST /Users/AuthenticateByName`
* **红心点赞接口：** `POST /Users/{userId}/FavoriteItems/{itemId}` 与 `DELETE`
  * **离线取舍：** 点赞请求失败时仅写入本地 SQLite；下次媒体库刷新成功后会以服务器收藏状态覆盖本地，离线期间新增的红心可能丢失。

### 3.2 媒体库曲目检索
* **接口：** `GET /Users/{userId}/Items?IncludeItemTypes=Audio&Recursive=true&Fields=MediaSources,ParentId,Genres,GenreItems&SortBy=SortName&SortOrder=Ascending&Start={n}&Limit=500`
* **分页：** 客户端按 500 条/页循环拉取直至取完；`Genres`/`GenreItems` 字段供中间流派 Tag 栏使用。

### 3.3 高清封面与双端点 LRC 动态歌词
* **无损直连串流：** `{serverUrl}/Audio/{itemId}/stream.mp3?api_key={accessToken}&static=true`（无损 Direct Stream）。
* **双端点歌词兼容：** 优先 `/Lyrics/{id}`，失败回退 `/Audio/{id}/Lyrics`。

---

## 4. 1920×720 车载顶部工具栏 + 双栏式视觉与交互架构

系统状态栏与底部三大金刚导航栏全程可见（不做沉浸接管），采用**顶部工具栏 + 中间列表 + 右侧播放器合并面板**的暗黑风 UI 布局：

```
+----------------------------------------------------------------------------------------------+
| ZSpace Player [全部歌曲] [♥ 红心收藏] [播放最多] [刷新媒体库] [EQ 音效] [服务器配置]   Server:…  |
+--------------------------------------+-------------------------------------------------------+
|    中间歌曲列表与搜索区                |  右侧合并面板 (歌曲信息+歌词+播控 同一面板)              |
|                                      |  +----------+  未播放曲目                              |
| [搜索: 歌曲/歌手/拼音 (如 ZJL)]  0 首  |  | 专辑封面  |  极空间 / Jellyfin                       |
| [全部] [流行] [摇滚] [爵士] [Tag]     |  +----------+                                         |
| 1. 流行曲目 A       [ 03:45 ]        |  实时歌词            [-0.5s] [+0.5s] [字号:中/大/超大]   |
| 2. 摇滚经典 B       [ 04:20 ]        |     当前实时滚动高亮 LRC 歌词 (48/56/64sp 三档)           |
| 3. 爵士轻音乐 C     [ 02:50 ]        |  00:00 ──────────●────────────── 03:45                |
| (条目高 76dp，含红心点赞触控按钮)      |    [顺序]    [|◀]    ( ▶ / || )    [▶|]   (QQ 音乐样式)  |
+--------------------------------------+-------------------------------------------------------+
```

---

## 5. 常规自启应用定位与系统栏展示

### 5.1 自启应用定位
在 `AndroidManifest.xml` 中保留标准 `LAUNCHER` 与 `DEFAULT` 类别，移除了系统 `HOME` 桌面 Intent 接管。作为常规车载播放器使用，结合 `BootReceiver` 实现开机后台启动。

### 5.2 系统状态栏与导航栏全程可见
布局已移除全屏沉浸模式（原「全屏沉浸」按钮与 `SYSTEM_UI_FLAG_*` 隐藏逻辑均已删除），顶部系统状态栏与底部三大金刚导航栏全程放出，不与车机系统 UI 争抢焦点。

---

## 6. 后台保活服务、方控按键与音频焦点避让

### 6.1 前台服务与车载方向盘硬按键 (`MediaButtonReceiver`)
`AudioPlayerService` 绑定前台通知，并通过 `MediaButtonReceiver` 监听系统 `android.intent.action.MEDIA_BUTTON` 广播，全量支持车载方向盘及中控切歌/播放按键；按键事件过滤长按自动连发 (RepeatCount)，防止长按切歌连环触发。

### 6.2 动态 AudioFx 车载 EQ 均衡器与低音增强
在 `AudioPlayerService` 中集成系统 `Equalizer` 与 `BassBoost`。在 `onPrepared` 回调中按最新的 `AudioSessionId` 动态重新绑定音效引擎，支持动态获取系统支持的音效 Preset 模式与 0-100% 重低音调节。未开始播放时 EQ 入口提示「开始播放后自动启用」；设备无 EQ 引擎时入口提示并拒绝打开。

---

## 7. 代码工程结构与一键编译部署

### 7.1 项目源码结构树
```
ZSpaceCarPlayer/
├── app/
│   ├── build.gradle                   # APK 优化精简 (4.1MB)
│   └── src/main/
│       ├── AndroidManifest.xml        # MediaButtonReceiver, BootReceiver, 前台服务
│       ├── java/com/ktools/zspacecarplayer/
│       │   ├── db/                    # DbHelper, SongDao (SQLite 本地持久化与拼音异步搜索)
│       │   ├── model/                 # SongItem, LyricLine, CategoryItem
│       │   ├── net/                   # JellyfinApiClient (含 CompositeTrustManager), TLSSocketFactory, IPv6FirstDns
│       │   ├── service/               # AudioPlayerService (MediaPlayer, AudioFx, 焦点), MediaButtonReceiver
│       │   ├── ui/                    # MainActivity, SongAdapter, LyricAdapter, CategoryAdapter
│       │   └── util/                  # PinyinUtils (拼音声母提取), CacheSizeManager (10GB LRU 缓存监控)
│       └── res/                       # 布局 XML、按钮 Drawable 与 ISRG 根证书
├── changelogs/
│   └── 2.3.0.md                       # 版本变更日志
├── deploy_to_car.sh                   # JDK/ANDROID_HOME 自检 + 自动构建部署脚本
└── README.md                          # 项目说明
```

### 7.2 一键部署命令
```bash
cd /Users/cpuser/Code/kTool/ZSpaceCarPlayer
./deploy_to_car.sh
```
