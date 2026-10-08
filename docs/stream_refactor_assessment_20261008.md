# 取流链路重构评估（2026-10-08，基于 vc21/vc22 真车上报）

回答一个问题：**这条链路是继续打补丁，还是形状本身要重做。** 判断依据只用实测数字和代码里
已经存在的事实，不用"感觉"。bug 层面的事都已修完（见 `changelogs/3.2.10.md`、`changelogs/3.2.11.md`），
这份文档不谈 bug，只谈形状。

---

## 1. 一次播放现在实际发生了什么

```
Jellyfin (:music 反代)
      │  一条 HTTP 请求 / 一个 Range          ← 每次付 0.75~1.44s 首字节（断网恢复时实测 8.7~11.7s）
      ▼
BufferedHttpSource                          ← 一条下载线程 + 8MB 环形窗
      │  ring[bufStart..bufEnd)
      ▼
本地 HTTP 代理 127.0.0.1:port                ← 原生解码器把它当"文件"打开
      ▼
NativeLosslessDecoder (dr_flac / 自研 PCM)   ← HttpSourceReader.readAt(position) 取字节
      ▼
AudioTrack → 8600 DSP → 功放
      ▲
      └── 旁路：StreamDiskCache（stream_<itemId+总长>.dat + .runs 账本，1GB LRU）
```

同一时刻的全局约束（代码里的常量）：`MAX_SOURCES = 2`、`MAX_IDLE_SOURCES = 0`、
环形窗 `8MB`、预取 eager 目标 `2MB`、磁盘缓存单文件 `100MB`。

> **读这份文档前先知道的一条取证坑**（今天踩的）：上报 JSON 里**字段类型不稳定**——
> `appVersionCode` 实际是字符串 `'21'`，按整数 `==21` 过滤会一条都匹配不上，我因此先读出过
> "今晚没有 vc21 上报"这种错误结论。版本与计数一律 `str()` 归一后再比。

关键：**下载线程写 ring、解码线程读 ring、磁盘缓存落盘，共用同一把 `BufferedHttpSource.lock`。**

---

## 2. 结构性问题（不是 bug，是形状）

### S1 "一条源"和"一次播放"不对齐
`HttpProxyServer.obtainSource()`：读者的位置若不被任何现有源窗口覆盖，就**新建一条源**
（key 变成 `url#fN`），**旧源不显式关闭**，只靠 LRU/`purgeIdleSources` 事后收。后果三条，全部有实测痕迹：

1. 指示器无法回答"这首歌到底用了几条连接"——vc21 上报里 `conns=4 socket=900627B` 与
   0.8 秒后的 `conns=1 socket=5209600B` 是**两条不同的源**，`conns` 是存活源之和所以还会倒退。
   vc22 用"读者数优先"选源（`pickReporterIndex`）+ 服务侧记累计最大值，那是**补丁不是解**。
2. `MAX_SOURCES = 2` 意味着当前曲 fork 出两条就占满表，代码注释自己写着
   "若当前曲自身 fork 出两个源占满表，**预取会被跳过**"。实测痕迹：20:30 之后 6 份 vc21 手动上报里
   `ctx_prefetchConns` **5 份根本没有键**（20:38 广寒宫、20:51/20:52 探窗、21:40 Wrap Me、
   21:41 Dream It），只有 20:53 那份有值。也就是"当时没有预取源在跑"是常态；而**那是被 fork 挤掉、
   还是预取门本来没触发，现有上报区分不了**——这本身就是本条要修观测缺口。预取失效是无声的，不报错。
3. 多源并存期间两条下载线程都可能活着，都从蜂窝网拉字节，都在往同一份磁盘缓存文件写。

### S2 一把锁三个主人
`lock` 同时是：网络线程填窗的锁、解码线程 `readAt` 等字节的锁、磁盘缓存落盘（曾带 fsync）的锁。
任何一路慢就传染全链路。vc21 的 A2 就是这个形状的实例：上报里出现
`buffered=8192KB/8192KB`（**窗是满的，字节都在**）与 `lead=0s`、音频一顿一顿并存——
读者拿不到锁。历史上另一个实例是 MediaCodec 分支的贪婪预读把"读者挨饿"和"播放抽干"混为一谈，
导致 `starve FATAL` 误判跳歌。

### S3 健康判据建立在 `percent` 上，而 percent 的语义是"下载头在文件的哪个位置"
它被同时用作：UI 缓冲长条、测速可采门（`percent<100`）、预取健康门、以及
`lead = (percent − 已播百分比) × 时长` 的原料。于是同一个量在三种场合说谎：

- 窗满被背压钉住 → `percent` 不涨 → `lead` 谎报 0（今晚）；
- 整首下完 → `percent=100` 后必然 0 新字节 → vc16 把它读成"带宽 0KB/s"，误降档当晚腰斩 4 首；
- chunked 转码流没有总长 → `percent=-1` → 时长/剩余/预取三道门同时失效。

T1 的口径是**单一 lead-秒**：Media3 `DefaultLoadControl` = 起播 1s / 缓冲目标 50s / 低于 2s 进
rebuffer，字节百分比只给 UI 看。我们恰好反着：字节百分比当判据，lead 是它推出来的二级量。

### S4 三套缓冲池没有主次
RAM 环 8MB、盘 1GB、服务端不缓存。现在是"过路字节在 RAM 里滚，盘只做命中补充"。
按 212,954 B/s 算，8MB = **39.4 秒**余量（16bit 那首 112,843 B/s 是 74.3 秒）；同一首歌在盘上
能放 45MB（>3 分钟）。
T1（ExoPlayer/Media3）的 `CacheDataSource` 是反的：**一切先过盘，RAM 只是 1~2MB 滑窗**。
所以我们手上明明有 1GB 的抗抖容量，却用它换不来链路掉速时的连续性。

### S5 字节率与链路之间只有两档，且没有"仍然无损但更省"的路
无损 = 原件直传；流畅 = 服务端转码（这台 Jellyfin 实测忽略码率参数，固定 256kbps≈32KB/s，
且 chunked、无总长、不支持 Range、`dur=0ms`）。中间那一层——"还是无损，但便宜一半"——
在**服务端**做不到（FLAC→FLAC 重压要么降位深就是有损），只能在**库侧**做。

今晚的实测需求 vs 供给：

| 曲目（夹） | required | 实测供给 | 窗状态 | 定性 |
|---|---|---|---|---|
| 广寒宫（古风，24bit/48k） | 212,954 B/s | 367KB/s 起，60 秒内 → 70KB/s | 满后抽干 | 链路掉速 |
| Wrap Me In Plastic（欧美，24bit） | 212,205 B/s | est 224,552 → ~102KB/s | **满 8MB 却 lead=0** | 我方锁（vc22 已改） |
| Dream It Possible（欧美，16bit） | 112,843 B/s | est 67,883 B/s | 1.3~2.1MB 未满 | 链路掉速 |

库侧事实（`jellyfin.db` 直查）：全库 **16bit 563 首平均 160KB/s、24bit 42 首平均 234KB/s、
mp3 191 首 30KB/s**；仍有 **435 首是 wav 原件**（172~183KB/s）；欧美那 134 首 flac 里
121 首 16bit（122KB/s）+ 13 首 24bit（208~216KB/s）；古风 33 首里 16 首 ≥180KB/s。

---

## 3. 候选方案（按爆炸半径从小到大）

### R1 把"一次播放"建成一个会话对象 —— 治 S1
`PlaybackStream`：一首歌一个对象，内含 ring、上游连接计数、磁盘句柄、TTFB/账本；
换曲、seek、预取晋升都在同一对象上 **mutate position**，不再新建第二条源；
`#f`/`#pf` 这套 key 与"按 URL 猜哪条在喂播放"的补丁全部删掉；`MAX_SOURCES` 不再是争夺资源，
预取不可能被 fork 挤掉。
- 涉及：`HttpProxyServer`（obtainSource / serveRange / purge / evict）、`BufferedHttpSource` 构造与登记。
- 风险：**中**。窗口近邻匹配存在的原因是 MediaCodec 回退分支与 seek 复用，重做时要保住等价行为。
- 收益立刻可见的部分：上报里 `conns`/`socket`/`srcs` 三者从此自洽，预取不再静默失效。

### R3 lead-秒单一健康口径 —— 治 S3（建议先做）
新纯函数 `leadSeconds(availableBytes, playedBytes, requiredBytesPerSec)` 成为**唯一**健康判据，
喂给 prefill / rebuffer / 预取门 / 测速可采门 / UI；`percent` 降级为只给缓冲长条看。
顺带把起播门槛从"攒够 `min(5s×字节率, 窗口 12%)≈1.0MB`"改成"够 1~2 秒即起播、边播边补"
——今晚实测 `doPrepare → prepared` 花了 **12.9 秒**静音，就是那个门槛在弱链路上算出来的。
- 涉及：`BufferingPolicy` + `AudioPlayerService` 采样/预取块。**纯函数，全部可单测。**
- 风险：**低**（配第五把回退闸，关掉即退回当前行为）。

### R2 三段解耦（生产者/环/消费者各走各的同步）—— 治 S2
网络线程 → 单生产者；解码读者 → 单消费者；磁盘写 → 独立有界队列（**队列满就丢，缓存不是正确性来源**，
fsync 只在收尾）。`lock` 拆成 `ringLock` 与 `ioLock`，读者不再被闪存写/网络停顿传染。
- 风险：**高**（并发正确性最难，Android 4.3 上没有现代并发工具，靠手写 wait/notify）。
- 触发条件：**vc22 上报里若"窗满却停顿"仍然出现**才动——那说明锁传染没拆干净。

### R4 盘为主、RAM 为滑窗（CacheDataSource 化）—— 治 S4，天花板最高但最贵
所有字节先落盘，读者从盘读，ring 缩到 1~2MB；抗抖余量从 38 秒变成分钟级，回拖/断流不再重连。
- 风险：**高**（`readAt` 语义、区间账本正确性、1GB 容量与闪存磨损、耗电）。
- 前置：必须已有 R1+R3，且真车上报先证明 `stored>0` 与 `disk=` 命中在稳定工作（vc22 才第一次能观测）。

### R5 明确不做（有据）
自动降档——车主否决；分段并发拉取——T1 无一家这么用，且今晚再次证明每请求固定成本才是主因；
服务端逐首转码——无总长/chunked/不支持 Range，已被证伪为"比现在更差"。

---

## 4. 推荐路径：先让数据分流，再决定动哪层

```
第 0 步（已做）  vc22 上线：锁挪出 + 仪表可读 → 下一份上报能把两种成因分开
第 1 步（等上报）判据分流：
   ├─ 仍见「buffered 满 + lead 0」        → 立刻 R2（锁传染未根治，其余都别排）
   ├─ 常「srcs≥2」或预取缺键              → 做 R1（源与会话对齐）
   └─ 两者都干净、卡的仍是 required > 供给  → **架构无罪**，转库侧与档位策略（见第 3 步）
第 2 步（低风险先行，与上面并行不冲突） R3：lead 单一口径 + 起播 1~2 秒，配回退闸
                    R6（QQ 一手证据支持）：链路差时抬高缓冲目标而非动音质
                    R7：卡顿自动上报，判据不再依赖车主按键
第 3 步（属库侧，不是重构，需车主点头） 435 首 wav 按欧美手法位精确转 FLAC（降到 ~120KB/s 档）；
                                        42 首 24bit 是否降位深 = 有损决定，我不擅自动
第 4 步（有条件）R1 落地后再评估 R4
```

一句话结论（含 §5 的 QQ 一手对照后仍成立）：**这条链路值得重构，但不该现在就大改。** 真正结构性的是 S1（源≠播放）和
S3（拿 percent 当健康），这两个都有小切口；S2 的锁传染刚被切掉一处实例，是否还有第二处
要等 vc22 的数据；S4 是天花板，动它之前必须先用 S1/S3 把观测做干净。

---

## 5. QQ 音乐车机版一手对照（2026-10-08 补，之前这份评估缺这一手）

先前这版评估的 T1 依据是 Media3/mpv/公开带宽口径。本轮直接拆了真包取证，可复核：

- 对象：`/Users/cpuser/Downloads/10200113.apk`，`com.tencent.qqmusiccar`，
  `versionCode=3140004 / versionName=3.14.0.4`，**`minSdk 21 / targetSdk 33`**，
  85,999,451 B，`sha256=d3c3f7ccb76615c2b69ee036808894e6f8b8441954be40e2452683d8aae113ca`。
- 手法：`strings` 取 `lib/arm64-v8a/libTPCore-master.so`（9,952,264 B，腾讯 **ThumbPlayer2** 内核）
  与五个 `classes*.dex` 的字符串；dex 里的中文必须按 **UTF-8 字节**直接搜。
  仓内旧文档 `geely_dsp_reverse_analysis.md` 只覆盖了音频焦点与 AudioTrack 序列，**没有一行网络行为**，
  所以这一节是新增证据，不是转述。

| 维度 | QQ 音乐车机版 3.14.0.4（一手串） | 我们（vc22） | 判定 |
|---|---|---|---|
| HTTP 形态 | `Range: bytes=%lu-`（**开放尾 Range，不到定长**）、`http_persistent`、`http_multiple`、`http_seekable`、`Content-Range` | 一条长连接 + 到末尾 Range（A1 改回来的） | **形状一致**，A1 方向被一手证据背书 |
| 缓冲水位口径 | 全是**时长**：`avplayer_buffer_duration_ms`、`buffer_packet_total_duration_ms`、`prepare_packet_total_duration_ms`、`buffering_filter_threshold_ms`、`buffering_timeout_ms`、`SetAudioLatencyLowWaterMarkUs`、`CalcBufferingEndThresholds: min/default/config/capacity/final(µs)`、`jitter_buffer_params`、`min_left_packet_queue_total_duration_ms_for_switch_data_source` | `percent`（下载头字节位置）当判据，`lead` 由它反推 | 直接证实 **S3**：人家没有一处拿字节当健康判据 |
| 弱网策略 | **两套缓冲目标**：`..._buffering_for_playback_fast_network_ms` / `..._slow_network_ms`（另见 `buffer_strategy`、`enable_strict_buffering_strategy`）→ 弱网**多缓冲** | 弱网只有 `bitrate deficit` 证据 + 车主手选档 | 见下面 R6，这是我们要补的那一层，而且**不动音质** |
| 预取 | Java 侧 `PreloadManager`、`preloadNext`、`预加载`(25 处)、`audio_preload` | prefetch 有，但会被 fork 挤占且**无声失效**（今晚 5/6 份上报无 `ctx_prefetchConns`） | 证实 **S1**：预加载在他们那儿是一等模块 |
| 磁盘缓存 | 内核层就有 `tp2_cache_dir_getter` / `tp2_android_cache_dir_getter`；App 层 `cacheSize`/`maxCache`/`clearCache`/`播放缓存` | `StreamDiskCache`（1GB LRU + 区间账本）刚起步 | 他们是**内核+App 两层**，我们是补充层 → 对应 R4 |
| 档位体系 | `SQ`(546)、`HQ`(114)、`臻品`(43)、`master`(88)、`hires`(36)、容器 `.flac`/`.ogg`/`.ape`/`.m4a`、`vkey`(83) 取流鉴权 | 两档手选（原件直传 / 服务端 256kbps 转码） | 他们按**文件形态**分档（不同码率不同容器，各自都是无损或有意的有损），不存在"同一首歌服务端现转还没预算"这条路 |
| 质量遥测 | 专用端点 `…/qmtm2/PlayPerformanceReport` + Beacon 埋点，**播放性能是自动上报的** | 只有车主**手动点上报**，不点就什么都没有 | 这是我们最实际的一条差距 → R7 |
| 解码 | 内置裁剪版 ffmpeg（该 build 可见 `--enable-decoder=mp3/aac`、`filter=pan/equalizer/anequalizer/loudnorm/dynaudnorm/volume/aresample`）+ `libQmNativeDataSource.so` + `libSuperSound3.so`；FLAC 具体走哪个库本轮未能定位 | `libzspacecarplayer_dsp.so`（vendored dr_flac/dr_wav）+ 自研 DSP | 立项要的"自建软解管线 + 自带音效"这条路是对的；**FLAC 路径未定位，不写成结论** |

**一条必须说清的限制**：QQ 的 `minSdk=21`，我们是 **18（Android 4.3）**。它们内核里那些现代并发/网络设施
（OkHttp 新版、QUIC `libXquic.so`、Mars `libTMEMars.so`）在 4.3 上不能直接搬，所以"照抄 TP2"不是选项，
能抄的是**口径与策略**（时长水位、fast/slow 两套缓冲目标、预取一等公民、播放性能自动上报）。

### R6 网络自适应的**缓冲时长**（从 QQ 一手证据来，不是从猜测来）
在 R3 的 lead 口径之上，把起播/目标缓冲做成两档，由**实测链路速率**选：链路好时按 T1 口径
（起播 1~2s、目标 20~30s）；链路掉速时把目标缓冲抬高（例如 12~20MB 折算秒数，或"够播 N 秒"里
N 变大），而不是去动音质。**它规避了车主否决的那条线**：换档=内容变差，多缓冲=只等更久不起播头几秒。
代价是弱网下起播更慢，所以必须和 R7（自动遥测）一起看效果。

### R7 卡顿自动上报（对齐 `PlayPerformanceReport`）
现在的判据依赖车主按键，不点就什么都看不见（今晚 5 次上报全是手动，且有一次上传失败）。
补一个被动触发器：满足"`lead` 触底 / `starve` / `readAt stall` / 窗满却 `lead=0`"任一条件时，
把**该会话**的上下文与窗口曲线自动发一条精简事件（不带堆栈），按曲 ID+时间去重、限频（如每 10 分钟
至多一条）。有了它，"到底是我方停顿还是链路"这类问题不必再等车主配合，cron 也能直接读。

---

## 5·五、想找"4.3 能跑的 QQ 音乐包"：外部来源普查结论（2026-10-08 夜，别重复跑）

先纠正本文早先的一处框定：**Android 4.3 不是取流/网络优化的障碍**。我写过"QQ 的 minSdk 21
搬不过来"，那只对它的 QUIC/Mars 二进制库成立；取流策略、缓冲口径、预取、重试全在 Java 层。
一手反证就在仓里：`apks/8600/XimalayaForCar.apk` = `com.ximalaya.ting.android.car` **1.6.1，
minSdk 11**，自带 native 播放内核 `lib/armeabi/libxmediaplayer.so` / `_x.so`，串里能看到
**自定义数据源与 seek 回调**（`DataSeekCallBack`、`dataSeekFromOut`、`AbsSeek`、`AVSEEK_SIZE`）
和 ffmpeg 的 `analyzeduration`/`probesize` —— 厂商在比 4.3 更老的系统上就自己管取流了。

外部找包的三条路都探过，结论是**网上没有比他手上更完整、可核验的 4.3 包来源**：

| 渠道 | 结果 |
|---|---|
| 镜像站程序化读取 | `apkpure.com` 连接失败、`apkpure.net` **403**、`apkcombo.com` 连接失败 —— 拿不到逐版本 minSdk 列表 |
| 下载站（ququyou / 7xz / 3h3 / qqtn / danji100 / onegreen / 91danji） | 元数据自相矛盾：同一款写 v3.1.0.9 且"安卓4.5+"（不是有效 API）、另站标"13.8.0.10"（那是手机版号）；下载按钮多为 `javascript:;` 占位；**无校验值、不可核验** |
| archive.org / GitHub 存档 | 搜不到带 checksum 的 `com.tencent.qqmusiccar` 存档；只有车主论坛帖（autohome 有人称在这类主机上装过酷我/QQ 音乐，但没有可下载的包） |

**真正的官方渠道在车上，不在网上**：`apks/8600/com.ecarx.appstore-1.apk` 里读到它的后端
`https://appstore-api.xchanger.cn/appstore/`（服务活着，返回 `{"message":"no Route matched..."}`），
客户端含 `SearchApiService` / `ApkDetailApiService` / `MyApkApiService` 等 Retrofit 接口与
`apkUrl` / `downloadUrl` 字段。**能装进这台 4.3 的那个版本，是由这个商店分发的。**
我只做了一次公开主机的只读 GET，拿不到路由就停 —— 继续猜别人内部接口路径不在我该做的范围内。

因此取一手证据的正确顺序（都不需要网上下载）：

1. 车连上 adb 时：`pm path com.tencent.qqmusiccar` → 拉 `base.apk`；**同时拉 `.odex`/`.vdex`**
   （预装件常把代码放在 odex：喜马拉雅那个 APK 里 536 个文件**没有 `classes.dex`**，只下载 APK 看不到 Java 逻辑）。
2. 或在车机应用中心里搜 QQ 音乐 → 安装/更新 → 再 pull。这条连信任问题都没有。
3. 我手上 `~/Downloads/10200113.apk`（3.14.0.4，**minSdk 21 / targetSdk 33**）**装不上这台 4.3**，
   所以它不是车上在跑的那版；它仍可作为"新版 QQ 音乐怎么做取流"的策略参照（本文 §5 的结论来自它，
   这些结论是策略层的，不受版本差异影响）。

---

## 5·六、喜马拉雅车机版一手对照（2026-10-08 夜，同平台 minSdk 11 架构取证）

先前评估只拿 QQ 音乐（ThumbPlayer2，minSdk 21）作对照，面临"Android 4.3 到底能不能做、怎么做"的疑虑。
本节直接拆解仓内车机实机预装包 `apks/8600/XimalayaForCar.apk`（`com.ximalaya.ting.android.car` 1.6.1，**minSdk 11**），
深入其底层原生内核 `lib/armeabi/libxmediaplayer.so` 与 `libxmediaplayer_x.so`，提取第一手符号与架构设计。

### 1. 喜马拉雅内核（libxmediaplayer.so）核心实现事实

```
                     ┌──────────────────────────────────────────────┐
                     │            Java 数据流 / 缓存调度层            │
                     └──────────────────────┬───────────────────────┘
                                            │ JNI 回调 (AVIO 适配器)
                     ┌──────────────────────▼───────────────────────┐
                     │              FileManagerThread               │ ◄── 专职网络拉流/文件 IO
                     │      (状态机: Preparing/Prepared/Started)     │     不参与实时音频解码
                     └──────────────────────┬───────────────────────┘
                                            │ 双向通道 (InnerMainCtl2FileManagerChn)
                                            │ Msg & Trigger 机制，无粗互斥锁争用
                     ┌──────────────────────▼───────────────────────┐
                     │                MainCtlThread                 │ ◄── 专职驱动软解 (FFmpeg)
                     │          (时钟同步 / 音视频解复用)             │     AVIO 读写由 Trigger 唤醒
                     └──────────────────────┬───────────────────────┘
                                            │ PCM 队列 (PTQueue)
                     ┌──────────────────────▼───────────────────────┐
                     │                OutputManager                 │ ◄── AudioTrack 输出调度
                     │        (OutputManagerResetPTQueueForSeek)    │
                     └──────────────────────────────────────────────┘
```

1. **极度收敛的软解层**：
   FFmpeg 明确按 `--enable-protocol=file --disable-everything` 裁剪。底层解码器完全不直接发起 Socket/HTTP 网络请求，彻底杜绝了原生层网络挂起导致 ANR 的问题。
2. **纯回调驱动的 IO（AVIO 机制）**：
   通过 `FillIoBufferCallBackWrapper` 与 `SeekIoBufferCallBackWrapper` 将读取请求派发给 Java 层注册的 JNI 函数（`dataStreamInputFunCallBackT`、`dataStreamSeekFuncCallBackT`、`dataStreamOutReadyFuncCallBackT`）。
3. **双线程通道解耦（彻底打破一把大粗锁）**：
   - `FileManagerThreadRun`：负责与 Java 层交互，管理数据拉流、缓冲水位与持久化状态机。
   - `MainCtlThreadRun`：专职负责解码调度与时间基准。
   - 两者之间通过无锁/条件变量的消息通道（`InnerMainCtl2FileManagerChn`）进行通信。解码器缺数据时挂起等待 Trigger，数据就绪后由 FileManager 线程发 Trigger 唤醒，**彻底避免了网络阻塞或磁盘 IO 慢传染给解码线程**。
4. **门槛事件驱动（Threshold Callback）**：
   具备 `bufferedDataReachThresholdCallBackT`。缓冲计算并非粗暴的死循环轮询，而是在数据达到特定时间/数据阈值（Threshold）时主动回调通知解码器恢复播放。
5. **Seek 重置队列而不推倒会话**：
   Seek 操作仅向通道派发 `Seek` 消息，底层调用 `OutputManagerResetPTQueueForSeek` 清理 PCM 时间队列，复用已有上下文，绝不 fork 新源或销毁重连。

---

## 5·七、融合 QQ 音乐与喜马拉雅后的 V3 完善架构（目标形态）

> **状态：设计目标，未实施**（目前仍保留 MAX_SOURCES=2 与 fork 逻辑，本节定义未来彻底治理 S1~S4 的架构演进终态）。

结合 QQ 音乐的**时长健康判据与弱网蓄水**，以及喜马拉雅的**双线程通道解耦与自定义输入**，形成彻底根治 S1~S4 的重构架构：

```
                           Jellyfin 服务器 (开放尾 Range)
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. 会话与传输调度层 (StreamSession)                                            │
│    - 单曲唯一会话：彻底消除 obtainSource 的 url#fN fork 分裂 (根治 S1)          │
│    - 预取一等公民：PreloadSession 享有受保护独立槽位，绝不被播放挤占失效           │
└──────────────────────┬──────────────────────────────┬───────────────────────┘
                       │                              │
                       ▼ 异步落盘队列                  ▼ 滑窗填充
            ┌──────────────────────┐      ┌───────────────────────────┐
            │ 磁盘缓存 (DiskSink)   │      │ 内存滑窗 (RAM RingBuffer) │
            │ 独立 IO 线程，满则丢弃 │      │ 纯字节搬运，无大互斥锁     │
            │ 不持有解码锁 (根治 S2)│      └─────────────┬─────────────┘
            └──────────────────────┘                    │
                                                        ▼ 自定义数据源适配
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. 线程隔离解耦层 (对齐 Ximalaya FileManager + MainCtl 通道模型)                │
│    - 数据流管理线程 (StreamIOThread)：拉流、计算时长水位、触发 Threshold 唤醒   │
│    - 解码消费线程 (DecoderReaderThread)：单向取流消费，阻塞只等 DataReady 信号  │
│    - 锁粒度切分：消灭 BufferedHttpSource.lock 粗锁，读者与写者无锁/细粒度同步   │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼ 门槛与状态调度
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. 门槛驱动与自适应蓄水 (对齐 QQ 音乐 Duration 水位 + 喜马拉雅 ReachThreshold) │
│    - leadSeconds 单一口径：彻底放弃 percent 字节位置假指标 (根治 S3)           │
│    - 起播门槛：预填 1.2 秒音频时长即开播 (68KB/s 弱网从 14.8s 降至约 3.8s)      │
│    - 弱网自适应：拉流速率 < 码率时，按容量夹紧抬高蓄水建议 (R6，不动音质)        │
│    - 自动上报：触底/卡顿自动记录 PlayPerformanceReport，消除观测盲区 (R7)       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. 每项的验收口径

| 方案 | 新增纯函数（必须单测） | 真车才能验的 | 回退方式 |
|---|---|---|---|
| R1 | `resolveSessionOwner(...)`（位置→会话归属） | 预取缺键消失、`srcs` 恒为 1 | 保留旧 `#f` 分支一个版本 |
| R3 | `leadSeconds(...)`、`startGate(...)` | 起播静音时长、二缓冲次数 | 设置页第五把闸（关=现行 1.0MB 门槛） |
| R2 | ring 的单生产/单消费游标 | 锁等待时长（需临时打点） | 闸：关掉即回到共用 `lock` |
| R4 | 盘读路径的区间/洞判定 | 命中字节 vs 网络字节比例 | 直接关磁盘缓存 |
| R6 | `bufferTargetSeconds(linkRate, required)` 纯函数 | 弱网起播耗时 vs 抽干次数的取舍 | 闸：关掉=单一缓冲目标 |
| R7 | 触发条件判定（窗满却 lead=0 / starve / readAt stall）+ 去重限频 | 事件是否真在车上落网 | 设置页开关，默认开 |
