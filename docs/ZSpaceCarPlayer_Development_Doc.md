# 🚗 ZSpaceCarPlayer (极空间 / Jellyfin 车载桌面播放器) 全景技术规范与开发文档

> **文档版本：** v3.0.0 (自研 C++ 原生 DSP 引擎 + 原生无损软解 + 抗抖动流式缓冲 + 双引擎可回退 + 崩溃监视与引擎熔断)  
> **更新时间：** 2026-09-08  
> **目标系统：** 吉利 / 亿咖通 8600 经典固件 (`06.03.08600.H53.00060`, Android 4.3 / API 18)  
> **工程路径：** `/Users/cpuser/Code/kTool/ZSpaceCarPlayer`  
> **过程记录：** v3 的逐阶段现状盘点、缺陷定界与实车走表见 `docs/v3_dev_plan_20260907.md`，本文只保留稳定后的架构规范。

---

## 目录
1. [设备环境与硬件/系统基线](#1-设备环境与硬件系统基线)
2. [Android 4.3 (API 18) 低版本兼容性全景规约](#2-android-43-api-18-低版本兼容性全景规约)
3. [Jellyfin 实时在线播放与 API 规范](#3-jellyfin-实时在线播放与-api-规范)
4. [v3 音频架构总览：双引擎 + 抗抖动缓冲 + 原生软解 + C++ DSP](#4-v3-音频架构总览双引擎--抗抖动缓冲--原生软解--c-dsp)
5. [C++ 原生 DSP 引擎与音效链](#5-c-原生-dsp-引擎与音效链)
6. [原生无损软解 (dr_*) 与格式策略](#6-原生无损软解-dr_-与格式策略)
7. [流式抗抖动层与涓流饥饿检测](#7-流式抗抖动层与涓流饥饿检测)
8. [崩溃监视、归因与原生引擎熔断](#8-崩溃监视归因与原生引擎熔断)
9. [1920×720 车载顶部工具栏 + 双栏式视觉与交互架构](#9-1920720-车载顶部工具栏--双栏式视觉与交互架构)
10. [常规自启应用定位与系统栏展示](#10-常规自启应用定位与系统栏展示)
11. [后台保活服务、方控按键与音频焦点避让](#11-后台保活服务方控按键与音频焦点避让)
12. [代码工程结构与一键编译部署](#12-代码工程结构与一键编译部署)
13. [第三方组件与许可](#13-第三方组件与许可)

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

### 1.3 v3 必须绕开的两项系统缺陷
| 缺陷 | 表现 | v3 对策 |
|:---|:---|:---|
| **MediaCodec 解码器被阉割** | 未注册 FLAC/WAV 解码器，无损直传必崩 (`Failed to allocate component`) | 无损解码整体下沉自有 `.so`（dr_* 软解，见 §6），不再依赖系统解码器 |
| **audiofx session 0 Virtualizer** | 挂在 session 0 时会 bypass 静音 | v3 引擎下**全部系统 audiofx 断开**，音效一律在 PCM 链路内自算（见 §5） |

---

## 2. Android 4.3 (API 18) 低版本兼容性全景规约

由于目标设备运行古老的 Android 4.3 (API 18) 与 Dalvik 虚拟机，必须严格遵守以下低版本兼容规约：

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

### 2.5 原生 (JNI/NDK) 边界规约 — v3 新增
车机现场「一放歌就闪退」在这台设备上**没有第二次取证机会**，因此 JNI 侧按硬性规约处理：
* **越界必须在 C++ 拦：** 数组长度、奇偶偏移、采样率与声道范围的校验一律放在 `Get*ArrayElements` **之前**。JNI 越界访问表现为 SIGSEGV 或静默堆破坏，Java 侧 `catch (Throwable)` 接不住。
* **异常绝不穿透 JNI：** 桥接层统一折叠异常（`ExceptionCheck` + `ExceptionClear`）后以返回值/错误码告知 Java 侧；异常穿透即进程级崩溃。
* **禁止裸 `new` 失败即崩：** 大块缓冲分配使用 `new (std::nothrow)` 并检查空指针。
* **崩溃归因优先于崩溃恢复：** 任何在冷启动阶段无视引擎偏好强行跑 native 路径的测试代码（例如曾经的 `MainActivity` NativeDsp 自检块）都必须移除——它会污染崩溃归因，把系统引擎的会话算到 v3 头上。

---

## 3. Jellyfin 实时在线播放与 API 规范

### 3.1 账号鉴权与 Token 维护
* **请求 Header 规范：**
  ```http
  X-Emby-Authorization: MediaBrowser Client="ZSpaceCarPlayer", Device="Geely-iMX6-Car", DeviceId="CAR-IMX6-001", Version="3.0.0", Token="{accessToken}"
  ```
  > `Version` 由 `JellyfinApiClient.CLIENT_VERSION` 单点提供，发版时必须与 `build.gradle` 的 `versionName` 同步提升。
* **登录接口：** `POST /Users/AuthenticateByName`
* **登录单飞 (single-flight)：** Activity 与 Service 并发触发登录时合并为一次请求共享结果，避免同 `DeviceId` 重复登录吊销前一个 Token。
* **红心点赞接口：** `POST /Users/{userId}/FavoriteItems/{itemId}` 与 `DELETE`
  * **离线取舍：** 点赞请求失败时仅写入本地 SQLite；下次媒体库刷新成功后会以服务器收藏状态覆盖本地，离线期间新增的红心可能丢失。
* **起播 URL 一律由最新 Token 动态重构**，绝不回退 SQLite/持久化里的旧 `streamUrl`（空或过期 `api_key` 会导致 401 点击无反应）。

### 3.2 媒体库曲目检索
* **接口：** `GET /Users/{userId}/Items?IncludeItemTypes=Audio&Recursive=true&Fields=MediaSources,ParentId,Genres,GenreItems&SortBy=SortName&SortOrder=Ascending&StartIndex={n}&Limit=500`
* **分页参数必须是 `StartIndex`：** Jellyfin 会静默忽略未知的 `Start`，第二页永远返回第一页数据（887 首被虚增成 1000 首）。
* **缓存修复：** 网络解析侧、缓存写侧与**缓存读侧**三处都要过 `TextRepair.repair`，否则修复逻辑上线前写入的旧行在进程重启后会先显示 GBK 乱码。`repair` 对已修复文本是恒等操作（有幂等单测锁定），因此读侧再套一层是安全的。

### 3.3 无损直传与双端点 LRC 动态歌词
* **串流 URL 形态（`JellyfinApiClient.getStreamUrl`）：** base 恒为
  `{serverUrl}/Audio/{itemId}/stream.mp3?api_key={accessToken}`，`directPlay=true` 时**再追加 `&static=true`** 令服务端不转码、按原码率直传原文件。
* **双引擎共用同一个 static URL：** 生产调用点只有一处（`AudioPlayerService` 用无参重载，`directPlay` 恒为 `true`），代码里**没有**「系统引擎切转码」的分支。两条消化路径的区别只在客户端：v3 经原生 `dr_*` 软解出 PCM 送 `NativeDsp`，系统引擎交给 `MediaPlayer`。
  > `directPlay=false` 分支目前仅存在于 API 层，无人调用；若实车证实系统引擎吃不下 static FLAC，这里就是降级兜底的接入点（走表 #1/#2）。
* **双端点歌词兼容：** 优先 `/Lyrics/{id}`，失败回退 `/Audio/{id}/Lyrics`。

---

## 4. v3 音频架构总览：双引擎 + 抗抖动缓冲 + 原生软解 + C++ DSP

v3 是**音频链路重构**，UI、服务生命周期、网络与持久化沿用 v2.3。核心是把「解码 + 音效 + 抗抖动」三件事从车机系统手里接管过来，同时保留一条**随时可切回的系统引擎**兼容通道。

```
                     Jellyfin  {serverUrl}/Audio/{id}/stream.mp3?api_key=…&static=true
                                          │  (原码率直传，服务端不转码)
                                          ▼
                    ┌─────────────────────────────────────────────┐
                    │  BufferedHttpSource  (2MB 环形滑动窗口)      │  ← §7
                    │  断流退避续传 / 涓流饥饿检测 / 窗口回收         │
                    └───────────────┬─────────────────┬───────────┘
                       经回环代理读  │                  │  按绝对字节位直读
                                    ▼                  ▼
              ┌────────────────────────────┐  ┌────────────────────────────────┐
              │  系统引擎 (默认)            │  │  v3 引擎 (设置页可开)             │
              │  AndroidMediaPlayerWrapper │  │  DspAudioTrackPlayer           │
              │  ─ HttpProxyServer:8xxx    │  │  ─ FLAC/WAV: NativeLossless    │
              │    127.0.0.1 回环 Range/206│  │      Decoder (dr_flac/dr_wav)  │
              │    NuPlayer → 硬解          │  │      经 HttpSourceReader 桥     │
              │  ─ 全部 v2.3 加固/看门狗/    │  │    MP3/AAC: MediaExtractor 软解 │
              │    焦点/功放策略零变化       │  │      → NativeDsp C++ 音效链     │
              └─────────────┬──────────────┘  └───────────────┬────────────────┘
                            │                                 │ 裸 PCM
                            ▼                                 ▼
                        AudioFlinger  ────────►  车机功放 (需 §11.3 唤醒策略)
```

### 4.1 抽象与切换契约
* **`IAudioPlayer`** 为唯一播放抽象；`AudioPlayerService` 不感知具体引擎。
* **系统引擎 = `AndroidMediaPlayerWrapper`**：包装原生 `MediaPlayer`，v2.3 全部加固（假播放看门狗、三级重试阶梯、音频焦点、功放唤醒 `GeelyAmpWakeStrategy`）**零改动**继承。
* **v3 引擎 = `DspAudioTrackPlayer`**：解码 → `NativeDsp` 音效 → 裸 `AudioTrack` 直写 AudioFlinger。
* **惰性重建：** 引擎只在切歌时按偏好重建，车机一次只变一个变量；**默认系统引擎**。
* **禁止双重代理嵌套：** 服务层与播放层只能有一层包 `HttpProxyServer`。嵌套会递归建源、每层各持一份环形缓冲，直接把 1.6GB 车机的堆打爆。v3 的原生无损路径因此**直取 `BufferedHttpSource`**（`acquireSource` + `extractRemoteUrl`），不再套代理。

### 4.2 错误码契约
v3 引擎向服务层上报**负值停滞码**（如 `STREAM_STALL`），`handlePlayerError` 一律按可恢复处理并**同曲断点重试**，绝不 `GIVE_UP`，也绝不伪装成 `onCompletion` 去「自动切下一首」（那是 8:40 现场「假报切歌」的直接成因）。慢网 `(what=1, extra=-19)` 归入可恢复传输错误，且传输层错误下的 `REAUTH_RETRY` 降级为 `PLAIN_RETRY`（重登录对传输问题无意义，只会白等 120s 鉴权冷却）。

---

## 5. C++ 原生 DSP 引擎与音效链

源码位于 `app/src/main/cpp/`，经 CMake 交叉编译为 **`libzspacecarplayer_dsp.so`**（编译单元 `native-lib.cpp` + `NativeLossless.cpp`），Java 侧封装为 `dsp/NativeDsp.java`（`System.loadLibrary("zspacecarplayer_dsp")`）。

| 处理节点 | 文件 | 算法要点 |
|:---|:---|:---|
| 参量均衡 | `Equalizer.h` + `BiquadFilter.h` | 5 段 RBJ Biquad（双二阶滤波器），逐段 gain/Q/fc |
| 重低音 | `BassBoost.h` | LowShelf @ **100Hz / +14dB**（贴近车载 sub 频段） |
| 声场展宽 | `StereoWidener.h` | 纯 Mid-Side 展宽（无交叉馈送），100% 时 side 增益 **3.2** |
| 混响 | `ReverbEffect.h` | Freeverb，wet 三档 **0.45 / 0.62 / 0.78**，激励 0.022 |
| 限幅 | `SoftLimiter.h` | **Tanh 软饱和，上限 1.0**，兜住前级全部过冲，不产生硬削顶爆音 |

### 5.1 参数下发与线程安全
* 参数写入与渲染线程读取由 `std::recursive_mutex` 全覆盖，采样率变更路径安全。
* **帧数换算必须按实际声道数**：早期按 4 字节/帧硬算，导致单声道内容只有半数样本经过 DSP 链。

### 5.2 参数持久化与重放（「音效拉满没效果」的三层根因）
1. `nativeInit` 会**重建 C++ 引擎并把参数清回默认**（每次 `prepare` 与流格式变化都会触发）→ init 后自动重放最近一次设置。
2. 服务端音效设置（EQ 预设 / 低音 / 全景 / 混响）持久化到 `SharedPreferences`，冷启动恢复 + `handlePrepared(v3)` 起播时重下发。
3. C++ 强度按车载实听上调（见上表加粗值）。

> **回归红线：** 崩溃重启后 DSP 参数不得归零或被关掉——启动即回放持久化参数（`d284500`）。这正是 8:40 现场「再打开 APP 时 DSP 音效已经自动关掉」的修复点。

---

## 6. 原生无损软解 (dr_*) 与格式策略

### 6.1 为什么下沉
吉利 8600 的 `MediaCodec` **未注册 FLAC/WAV 解码器**，无损直传给系统引擎必然 `Failed to allocate component`。v3 把无损解码整体做进自有 `.so`：`dr_flac` / `dr_wav` / `dr_mp3` 三个单头库 + `NativeLossless.cpp`。

### 6.2 零落盘字节位桥
`dsp/NativeLosslessDecoder.java` 通过 `bridgeRead` / `bridgeSeek` **按绝对字节位**从 `BufferedHttpSource` 环形缓冲读取，全程**不写本地文件**（车机存储与 4.3 的 `content://` 权限模型都不适合落盘）。

### 6.3 格式策略（单一裁定点）
* 文件头**魔数嗅探**判定格式；只有 **FLAC / WAV** 进原生软解。
* **MP3 刻意不进原生**（`NativeLosslessDecoder.isNativeSupportedFormat`）：系统有 `audio/mpeg` 硬解，软解等于白烧 i.MX6Quad 的 CPU。
* **方向安全的已知误判：** ADTS AAC（`0xFF 0xF1`）会被帧同步掩码嗅成 `FMT_MP3`，但 `FMT_MP3` 不进原生路径，最终仍由系统硬解接手——错的方向是「交给系统」而不是「喂给 dr_*」。

### 6.4 seek 精度与弱网风险
* 原生 seek 成功后按请求毫秒重算 presentation 时间戳，误差上限为一个 FLAC frame（4096 samples ≈ **93ms @44.1kHz**），进度条「填充与滑块不分叉」的契约未回退。
* **已知未收口风险（实车走表项）：** native seek 跑在渲染线程且**无超时保护**。无 SEEKTABLE 的 FLAC 做 `drflac_seek_to_frame` 需回溯读，每次跨出窗口都会触发一次 Range 重定位下载，弱网下一次 seek 可能阻塞渲染线程数秒 → `stopRenderingThread` 的 `join(3000)` 超时。已埋 `teardown done in Xms` 面包屑用于现场定界。

### 6.5 内存预算
`AndroidManifest` 已开启 `largeHeap`，为环形缓冲（2MB）+ JNI 中转缓冲预留堆预算。`dr_*` 软解在 i.MX6Quad 上的 CPU/RSS 占用**尚未实车采样**，是 v3.0.0 的头号风险项；超线的降级兜底为「FLAC 降采样转码」或「v3 引擎只用于有损格式」。

---

## 7. 流式抗抖动层与涓流饥饿检测

### 7.1 组件
* **`BufferedHttpSource`**：环形滑动窗口下载器。当前 `DEFAULT_CAPACITY_BYTES = 2MB`（初版 8MB，为车机堆预算收敛），`CHUNK_SIZE = 64KB`，连接超时 10s、读超时 15s、断流退避 `800ms × 2^n` 最多 5 次。
* **`HttpProxyServer`**：**仅绑 `127.0.0.1`** 的回环 HTTP 代理，实现完整 `Range`/`206` 语义以贴合 `NuHTTPDataSource`。

### 7.2 三条血泪契约
1. **远端提前 FIN 必须按截断处理**，不得标 EOF——误标会让 MediaPlayer 静默停摆（表现为「播着播着没声且不报错」）。
2. **连接按「近邻共享/远端分家」选数据源**：窗口外的读者（如 NuPlayer 探测文件尾时长）必须独立建源。共享窗口被两个相距数 MB 的读者反复 reset 会乒乓锁死（模拟器实测 12 分钟 2129 条 reset 零进展）。
3. **空闲源即刻回收**（最多保留 1 个供窗口内 seek 秒开），无读者时自动休眠省带宽。

### 7.3 涓流饥饿检测（8:40 现场根因）
「涓流」= **连接活着但供数远低于码率**。旧检测器全盲：下载侧每秒几百字节也算有进展、`read` 总在 15s 内返回，于是环形缓冲被抽干后渲染线程永久 `wait`，UI 假播、无报错、不上报。

改为**读者视角**滑窗判定（纯静态函数，`StreamStarvationTest` 覆盖）：

| 参数 | 值 | 语义 |
|:---|:---|:---|
| `STARVE_WINDOW_MS` | 20s | 结算窗口 |
| `STARVE_LIMIT_MS` | 18s | 窗口内累计挨饿阈值 |
| `MAX_STARVE_STALLS` | 2 | 前两次只强制重连续传（窗口保留），第三次升级 `STARVE_FATAL` |

* 窗口内**喂得上就把挨饿计数清零**——防误报（网络抖一下不算饥饿）。
* `STARVE_FATAL` 经 `HttpSourceReader.failed → hasStreamFailed → onError(STREAM_STALL)` **如实上报断点**；`obtainSource` 会收割带 fatal 的死源，避免新读者复用已被堵死的源而丧失自愈能力。

### 7.4 弱网取证工具备注（模拟器）
* `svc wifi/data disable` 会让 DNS 立即失败（`UnknownHostException`），走的是旧的重试耗尽路径，**模拟不出涓流**；要用 `iptables` 黑洞（`OUTPUT DROP 80/443`）制造「连接活着但零供数」。
* 真涓流最终靠上游自然故障复现验证到完整升级链。

---

## 8. 崩溃监视、归因与原生引擎熔断

代码在 `crash/` 六件套：`CrashMonitor` / `CrashBreadcrumbs` / `CrashReport` / `CrashReportStore` / `CrashUploader` / `NativeEngineGuard`。在 `ZSpaceApplication.onCreate()` 尽早 `install()`（越早越能捕获启动期崩溃）。

### 8.1 采集面
* **`KIND_JAVA_CRASH`**：链式 `UncaughtExceptionHandler`（先落盘再交回系统 handler）。
* **`KIND_NATIVE_CRASH`**：读 `logcat -b crash` 找 `Fatal signal` / SIGSEGV / SIGABRT / tombstone 线索。
* **`KIND_ABNORMAL_EXIT`**：会话标记存活但无崩溃证据（用户上滑清理、LMK 回收）——**绝不计数**。
* **主线程看门狗**、**ANR 面包屑环**、关键 context（`engine` / `song` / `generation` / `streamUrl` / `lastError*`）。

### 8.2 会话标记与归因
标记格式 **`pid|startWallMs|engine`**（`engine` ∈ `v3` / `sys`），由 `CrashMonitor.markV3EngineActive(wantV3)` 在引擎切换时重写。只有标记为 `v3` 且类型为 native/java 的崩溃才计入原生引擎。

### 8.3 熔断：单向锁存
* 累计 **`CRASH_LIMIT = 3`** 次**可归因**崩溃后自动回退系统 `MediaPlayer`。
* **单向锁存**：`shouldAutoDisable(count, alreadyAutoDisabled) = alreadyAutoDisabled || count >= 3`。**不能只看当前连击数**——熔断后引擎已是系统，之后的会话必然「不可归因」而把连击清零；若标志跟着清零，用户正常关一次 APP 就能让 v3 悄悄复活，回到「一放歌就闪退」的死循环。（这个缺陷是模拟器实测抓到的，已修并加回归用例 `aCleanSessionAfterTheBreakerTripsKeepsItLatched`。）
* 清除途径**只有一个**：用户在设置页显式重开（`resetEngineGuard()`）。
* 策略裁定放在**零 Android 依赖**的 `NativeEngineGuard`（可 JVM 单测），`CrashMonitor` 只负责 prefs 读写，且必须用 `commit()` 而非 `apply()`——**进程可能正在死**。
* prefs 键：`engine_v3_crash_count` / `engine_v3_auto_disabled` / `engine_v3_auto_disabled_at`。

### 8.4 云端上传
* 端点 `https://your-jellyfin.example.com/crash`，本地报告目录最多留 **20 份**，失败退避 **1h → 24h**。
* **服务端 ingest 尚未部署**（现状：该路径被 Hub 前端 SPA 静态回退占用，`GET` 返回 `index.html`、`POST` 一律 `405`；Hub JSON API 在 `/api/*` 下，`/api/crash` 为 `404`）。部署属共享基础设施改动，需人工确认；在此之前客户端**静默降级**，不影响播放。

### 8.5 UI 反馈
设置页「播放引擎」开关按**有效态**翻转：`pref && !autoDisabled`。锁存时药丸显示 **「系统 (崩溃保护)」**（该 `Button` 必须 `wrap_content` + `textAllCaps="false"`，固定 58dp 宽度会把文案裁掉）。

---

## 9. 1920×720 车载顶部工具栏 + 双栏式视觉与交互架构

系统状态栏与底部三大金刚导航栏全程可见（不做沉浸接管），采用**顶部工具栏 + 中间列表 + 右侧播放器合并面板**的暗黑风 UI 布局：

```
+----------------------------------------------------------------------------------------------+
| ZSpace Player [全部歌曲] [♥ 红心收藏] [播放最多] [刷新媒体库] [EQ 音效] [服务器配置]   Server:…  |
+--------------------------------------+-------------------------------------------------------+
|    中间歌曲列表与搜索区                |  右侧合并面板 (歌曲信息+歌词+播控 同一面板)              |
| [搜索: 歌曲/歌手/拼音 (如 ZJL)]  0 首  |  +----------+  未播放曲目                              |
| [全部] [流行] [摇滚] [爵士] [Tag]     |  | 专辑封面  |  极空间 / Jellyfin                       |
| 1. 流行曲目 A       [ 03:45 ]        |  +----------+                                         |
| 2. 摇滚经典 B       [ 04:20 ]        |  实时歌词            [-0.5s] [+0.5s] [字号:中/大/超大]   |
| 3. 爵士轻音乐 C     [ 02:50 ]        |     当前实时滚动高亮 LRC 歌词 (48/56/64sp 三档，居中)      |
| (条目高 76dp，含红心点赞触控按钮)      |  00:00 ──────────●────────────── 03:45                |
|                                      |    [顺序]    [|◀]    ( ▶ / || )    [▶|]   (QQ 音乐样式)  |
+--------------------------------------+-------------------------------------------------------+
```

* **歌词高亮居中：** 原 `smoothScrollToPosition` 只滚到「刚好可见」，高亮行永远贴视口底部；改为高亮行居中（上下均保留歌词），换歌/手动 seek 先跳转再一次性居中，**用户手动拖动时暂停自动居中不抢滚动**。
* **进度条自绘细轨：** 车机魔改框架忽略 `layer-list` 的 `inset` 且默认 `maxHeight=24dp`，原生 `progressDrawable` 渲染不出细轨道；改为 4dp 静态轨道 + 动态宽度渐变填充 + 透明 `SeekBar` 叠加。填充条直接读 `SeekBar` 自身 `progress/max`，并在进度 tick 时把 `max` 同步为播放器真实 `getDuration()`——元数据时长与真实时长不一致会造成填充与滑块分叉。
* **设置页：** 服务器与账号配置 → **播放引擎**开关（`v3=自研软解+C++音效, 系统=车机原生兼容`），熔断锁存时显示「系统 (崩溃保护)」。设置页在 `ScrollView` 内，需滚动才能到达。

---

## 10. 常规自启应用定位与系统栏展示

### 10.1 自启应用定位
在 `AndroidManifest.xml` 中保留标准 `LAUNCHER` 与 `DEFAULT` 类别，移除了系统 `HOME` 桌面 Intent 接管。作为常规车载播放器使用，结合 `BootReceiver` 实现开机后台启动。

### 10.2 系统状态栏与导航栏全程可见
布局已移除全屏沉浸模式（原「全屏沉浸」按钮与 `SYSTEM_UI_FLAG_*` 隐藏逻辑均已删除），顶部系统状态栏与底部三大金刚导航栏全程放出，不与车机系统 UI 争抢焦点。

### 10.3 退出必须彻底释放（v3 加固）
双击返回确认退出即走 `stopAndReleaseAllAudioResources` 完整释放链：停止播放 → 释放双引擎播放器 → 释放系统音效 → 回收环形缓冲源 → 放弃音频焦点 → 注销媒体键与 `RemoteControlClient` → 移除通知并 `stopSelf`，并置 `DesiredPlayback=STOP` 防止看门狗复活，`onDestroy` 结束进程做到零后台残留。

---

## 11. 后台保活服务、方控按键与音频焦点避让

### 11.1 前台服务与车载方向盘硬按键 (`MediaButtonReceiver`)
`AudioPlayerService` 绑定前台通知，并通过 `MediaButtonReceiver` 监听系统 `android.intent.action.MEDIA_BUTTON` 广播，全量支持车载方向盘及中控切歌/播放按键；按键事件过滤长按自动连发 (`RepeatCount`)，防止长按切歌连环触发。

### 11.2 音效：系统 AudioFx 与 NativeDsp 二选一
* **系统引擎下**：沿用 `Equalizer` + `BassBoost`，在 `onPrepared` 里按最新的 `AudioSessionId` 动态重绑音效引擎；未开始播放时 EQ 入口提示「开始播放后自动启用」，设备无 EQ 引擎时提示并拒绝打开。
* **v3 引擎下**：系统 audiofx **全部断开**（§1.3 的静音缺陷），EQ/低音/全景/混响全部由 `NativeDsp` 在 PCM 链路内实时运算。

### 11.3 功放通道唤醒 (`GeelyAmpWakeStrategy`)
车机物理静音解除后功放 DSP 媒体通道会保持静默（Android 侧音量/静音计数正常但无声）。起播与恢复时：重新下发一次媒体音量唤醒功放通道 → 解除可能卡住的流静音标志（需 `MODIFY_AUDIO_SETTINGS`）→ 音量加一减一强制向 HAL 重下发（同值设置会被 AudioService 短路）→ 音频焦点弃掉重申请促使车机路由重开媒体通道；音量为 0 时自动恢复至 60%。

### 11.4 假播放看门狗与重试阶梯
`isPlaying` 但进度连续 10 秒零位移（断流时 NuPlayer 保持 playing 却无数据输出）即从断点重建连接。播放错误分三级重试：首次原 Token 直接重试（断流多为网络抖动）→ 连续失败才重新登录且带 2 分钟冷却 → 不可恢复才 `GIVE_UP`。`MEDIA_ERROR_SYSTEM` 纳入重试范围。同一轮起播未成功期间**只提示用户一次**，重复错误降为 `Log.w` + 面包屑。

---

## 12. 代码工程结构与一键编译部署

### 12.1 项目源码结构树
```
ZSpaceCarPlayer/
├── app/
│   ├── build.gradle                   # minSdk 18 / targetSdk 28 / versionCode 3 / versionName 3.0.0
│   │                                  # abiFilters armeabi-v7a + arm64-v8a, ndkVersion 21.4
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    # BootReceiver, MediaButtonReceiver, 前台服务, largeHeap
│       │   ├── cpp/                   # CMake 原生工程
│       │   │   ├── DspEngine.h / Equalizer.h / BiquadFilter.h / BassBoost.h
│       │   │   ├── StereoWidener.h / ReverbEffect.h / SoftLimiter.h   # ← §5 DSP 链
│       │   │   ├── dr_flac.h / dr_wav.h / dr_mp3.h                    # ← §6 第三方单头库
│       │   │   ├── NativeLossless.cpp / native-lib.cpp                # JNI 桥 (边界校验 + 异常折叠)
│       │   │   └── CMakeLists.txt
│       │   ├── java/com/ktools/zspacecarplayer/
│       │   │   ├── ZSpaceApplication.java   # CrashMonitor.install 尽早挂钩
│       │   │   ├── crash/                   # CrashMonitor / Breadcrumbs / Report / Store / Uploader
│       │   │   │                            # + NativeEngineGuard (零 Android 依赖的熔断策略)
│       │   │   ├── dsp/                     # NativeDsp, NativeLosslessDecoder (含 HttpSourceReader)
│       │   │   ├── player/                  # IAudioPlayer, AndroidMediaPlayerWrapper, DspAudioTrackPlayer
│       │   │   │   └── stream/              # BufferedHttpSource (2MB 环), HttpProxyServer (127.0.0.1)
│       │   │   ├── service/                 # AudioPlayerService, PlaybackStateMachine, MediaButtonReceiver,
│       │   │   │                            # BootReceiver, CarRemoteControlClient, GainEnvelope,
│       │   │   │                            # GeelyAmpWakeStrategy
│       │   │   ├── net/                     # JellyfinApiClient (CompositeTrustManager), TLSSocketFactory, IPv6FirstDns
│       │   │   ├── db/                      # DbHelper, SongDao (SQLite 持久化 + 拼音异步搜索)
│       │   │   ├── model/                   # SongItem, LyricLine, CategoryItem
│       │   │   ├── ui/                      # MainActivity, SongAdapter, LyricAdapter, CategoryAdapter
│       │   │   └── util/                    # PinyinUtils, CacheSizeManager (10GB LRU), TextRepair
│       │   └── res/                         # 布局/Drawable、values-sw720dp 双档尺寸、ISRG 根证书
│       └── test/                            # 20 个 JVM 测试类 / 195 用例
├── changelogs/                       # 2.3.0.md, 3.0.0.md
├── docs/                             # 本规范文档 + v3_Design_Plan + v3_dev_plan + field_test_* + DSP 逆向
├── apks/                             # 8600/ 系统 APK 取证备份, release/ 发布与回滚包 (gitignore)
├── gradle.properties                 # 机器相关路径改走 ~/.gradle/gradle.properties
├── deploy_to_car.sh                  # JDK/ANDROID_HOME 自检 + 自动构建部署脚本
└── README.md                         # 项目说明 + §7.3 构建 JDK 要求
```

### 12.2 构建环境要求（硬性）
* **Gradle 8.9 只允许 JDK 17 ~ 21。** JDK 25 会在 **daemon 启动阶段**直接崩溃，`build.gradle` 根本来不及执行，因此**无法用构建脚本自检拦截**，只能靠环境约定。
* macOS + Homebrew 固定 JDK 21：
  ```bash
  export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home
  ```
* `deploy_to_car.sh` 会自行探测候选 JDK；`gradle.properties` 里可用 `org.gradle.java.home` 指到本机 JDK 17~21。

### 12.3 一键部署与验证命令
```bash
cd /Users/cpuser/Code/kTool/ZSpaceCarPlayer
./deploy_to_car.sh                                   # 构建 + 安装到已连接设备
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home \
  ./gradlew testDebugUnitTest --console=plain        # JVM 单测 (当前 195 例全绿)
```

### 12.4 APK 体积红线（实测，勿凭印象引用旧值）
| 目标 ABI | native 增量（未 strip） | 包内压缩后 |
|:---|---:|---:|
| arm64-v8a | 708,744 B | 278,257 B |
| armeabi-v7a | 466,588 B | 220,469 B |

红线为 **3MB / ABI**，实测远低于线，因此 **`dr_mp3` 保留不裁剪**。

### 12.5 模拟器取证注意事项
* UI 真值以**截图**为准（`uiautomator` 对设置面板不可见）；Java 线程 dump 走 JDWP + `jdb`。
* 注入 native 崩溃用 `adb root` + `kill -11 <pid>`（crash buffer 里 `Fatal signal` 行带 app UID，重启后归因仍成立）；注入前**必须**先起一个后台 `adb logcat > /tmp/xxx.log`，因为 APP 会在 ~1.5s 后自动重启并清掉缓冲区。
* **严禁以 root 直接 `sed -i` 改 `shared_prefs/*.xml`**：会改掉属主与 SELinux 标签，APP 随即 `Attempt to read preferences file ... without permission` + `avc: denied { rename }`，prefs 读成空值——这看着像功能失效，其实是测试自己造的假故障。补救：`chown u0_aNN:u0_aNN` + `chmod 660` + `restorecon`。请一律走真实交互路径改设置。
* 现场出现残留的「ZSpaceCarPlayer keeps stopping」系统对话框会持续重建并阻塞 UI 导航，先 `am force-stop` 再 `am start`。

---

## 13. 第三方组件与许可

| 组件 | 版本 | 许可 | 用途与说明 |
|:---|:---|:---|:---|
| **dr_flac** | v0.13.4 | **Public domain / MIT-0 双许可任选** | FLAC 软解码 (`app/src/main/cpp/dr_flac.h`)，作者 David Reid，<https://github.com/mackron/dr_libs> |
| **dr_wav** | v0.14.6 | **Public domain / MIT-0 双许可任选** | WAV 软解码，同上 |
| **dr_mp3** | v0.7.4 | **Public domain / MIT-0 双许可任选** | MP3 软解码，**已编译进 `.so` 且能解**（`DR_MP3_IMPLEMENTATION`），但由 `NativeLosslessDecoder.isNativeSupportedFormat` 刻意不放行——MP3 走系统硬解更省 CPU。因体积红线未裁剪（§12.4） |
| OkHttp | 3.12.13 | Apache-2.0 | 最后一支持 API 18 的维护线，禁止升级 |
| Gson | 2.10.1 | Apache-2.0 | Jellyfin JSON 解析 |
| Glide | 4.12.0 | BSD-3 + Apache-2.0 (依赖) | 封面加载 |
| AndroidX appcompat / recyclerview | 1.3.1 / 1.2.1 | Apache-2.0 | UI 基础件 |
| Freeverb 算法 | — | 无第三方源码引入 | `ReverbEffect.h` 按 Freeverb（Jezar at Dreampoint，4 Allpass + 8 Comb）自行实现，非 vendored 代码 |

> 本仓库以 **Public domain** 选项使用 `dr_*` 系列，无需在发行包内附带许可文本；仍保留其文件头原文以尊重作者署名。

---

## 附：v3.0.0 验收状态

* **已在模拟器验证：** 无损 FLAC/WAV 起播与 seek、涓流饥饿完整升级链（含防误报与如实报错）、崩溃熔断与单向锁存、双引擎切换与回退、退出彻底释放、列表与 UI 回归、JVM 单测 195 例全绿。
* **必须实车、尚未收口：** i.MX6Quad 上 `dr_*` 软解 30 分钟 CPU/RSS 采样、四音效实听、功放红线回归、语音 Ducking、长暂停焦点、车机媒体键联动、开机自启真实重启、tombstone 归因确认。
* **本版本发布流程现状：** 物料就绪，**未合入 `main`、未打 tag**；合入与打 tag 以实车走表全绿为前置条件。
