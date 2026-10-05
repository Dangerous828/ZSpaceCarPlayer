# 发布与回滚包归档

> 本目录下 `*.apk` 受 `.gitignore` 排除，**只存在于构建机**（`/Users/cpuser/Code/kTool/ZSpaceCarPlayer`）。
> 本 README 记录每个包的来源与校验值，换机器后需按下述命令重建。

## ZSpaceCarPlayer-3.1.3-code7-debug.apk — 当前 OTA 分发版

| 项 | 值 |
|:---|:---|
| 版本 | `3.1.3` / versionCode `7`（`aapt2 dump badging` 实测：`minSdkVersion:'18'`、`targetSdkVersion:'28'`） |
| 大小 | 3,116,893 B |
| SHA-256 | `5d9fb25c595ff6efb105611e1183e4a2a833cf2e21266abcdc9968c3e6a9f333` |
| 来源 | `feat/v3-native-dsp-pipeline` 工作区构建 —— 5.1 多声道母带下混修复，见 `changelogs/3.1.3.md` |
| 构建时间 | 2026-09-20 23:35 |
| 构建类型 | **debug** —— 与车机已装包同签名，可 `adb install -r` 覆盖安装；项目无签名配置，release 包不可安装 |
| 分发 | 托管 `https://web.kentonnie.top/zspace/update/`（清单 `latest.json` + 同名 APK） |
| 包内实证 | `classes.dex` 含 `PcmDownmix` 类标识；装机后 `dumpsys package` 应为 `versionCode=7` |
| 实车 | **待验**，验收清单见 `changelogs/3.1.3.md` |

重建命令：

```bash
cd /Users/cpuser/Code/kTool/ZSpaceCarPlayer
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk apks/release/ZSpaceCarPlayer-3.1.3-code7-debug.apk
```

## ZSpaceCarPlayer-2.3.0-code2-debug.apk — v3.0.0 的回滚基线

| 项 | 值 |
|:---|:---|
| 版本 | `2.3.0` / versionCode `2`（`aapt2 dump badging` 实测） |
| 大小 | 2,501,228 B |
| SHA-256 | `2c858d6051b4657483072379e452b1f5f1a10717b54ef97e0fa439192a2bc256` |
| 来源 | `main` @ `8b1999d`（v3 分支的 merge-base），**独立 git worktree 构建**，未混入任何 v3 改动 |
| 构建时间 | 2026-09-08 |
| 构建类型 | **debug** —— 与车机实际安装形态一致（`deploy_to_car.sh` 走 `assembleDebug` + `adb install -r`，项目无签名配置，release 包不可安装） |

重建命令：

```bash
cd /Users/cpuser/Code/kTool/ZSpaceCarPlayer
git worktree add /tmp/zspace230-rollback main
cp local.properties /tmp/zspace230-rollback/ 2>/dev/null || true
cd /tmp/zspace230-rollback
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk \
   /Users/cpuser/Code/kTool/ZSpaceCarPlayer/apks/release/ZSpaceCarPlayer-2.3.0-code2-debug.apk
```

## ZSpaceCarPlayer-3.1.0-code4-debug.apk — vc4 分发包

| 项 | 值 |
|:---|:---|
| 版本 | `3.1.0` / versionCode `4` |
| 大小 | 3,101,460 B |
| SHA-256 | `03c548308092a185e5c3c8dfcc6f7b5027eb245d46276089058192e6031a5507` |
| 来源 | `feat/v3-native-dsp-pipeline` 工作区构建（远程升级 + 缓冲/预取 + 音量意图等，见 `changelogs/3.1.0.md`） |
| 构建时间 | 2026-09-13 |
| 构建类型 | **debug** —— 与车机已装包同签名，可 `adb install -r` 覆盖安装 |
| 分发 | 已托管 `https://web.kentonnie.top/zspace/update/`（清单 `latest.json` + 同名 APK），车机 vc≤3 启动静默检查即发现新版 |

重建命令：

```bash
cd /Users/cpuser/Code/kTool/ZSpaceCarPlayer
./gradlew assembleDebug   # JDK 17~21
cp app/build/outputs/apk/debug/app-debug.apk apks/release/ZSpaceCarPlayer-3.1.0-code4-debug.apk
```

## ZSpaceCarPlayer-3.1.1-code5-debug.apk / -3.1.2-code6-debug.apk — vc5 重号与纠正

**`versionCode 5` 存在两份内容不同的包**，这是 2026-09-20 排查「3.1.1 功能没上车」时查实的事故根因：

| 包 | 构建时间 | 大小 (B) | SHA-256 |
|:---|:---|:---|:---|
| vc5 半成品（09-16 出包后即发布上车） | 2026-09-16 08:38 | 3,111,784 | `601be848536c2f7b6e754a8afa79800374d268c1cf9ee431beb6971d70dff964` |
| vc5 全量（补完上报按钮/TLS/安装器，**从未部署**） | 2026-09-17 09:00 | 5,137,693 | `06e3b847b4c64b181df30cb81c8bafaba8f8be841ff8d8d6bf38ae93cebed95d` |
| vc6 = 3.1.2（全量内容重新出包并正式发布） | 2026-09-20 08:43 | 3,114,799 | `3533b349c93d3e7ce9d1b91b053d42ea0a9fec7b98e6f67d7231789e9ed83d39` |

同版本号使车机 OTA 判定「已最新」而永不自愈，故跳 vc6。vc5 全量包 5.1MB 的异常体积来自当时未 strip 的中间产物，vc6 干净构建回到 3.1MB。

## vc8 = 3.1.4 发布记录（2026-09-23 00:10）

正式发布物只有一个：**`ZSpaceCarPlayer-3.1.4-code8-clean-debug.apk`**，3,117,808 B，
`sha256=35de7751653206b31ca64b7f208a9c34c3afd615480583feef98a918761faf5f`，
`aapt dump badging` → `versionCode='8' versionName='3.1.4'`，`lib/` 下 `arm64-v8a` 与
`armeabi-v7a` 两份 `libzspacecarplayer_dsp.so` 齐全（1.18MB 未压缩）。

同一次代码另有两个**增量构建废包**，已移出可下载路径，落在
`web-zspace/update/_rejected-incremental-builds/`（仓内也各留一份）：

| 包 | 大小 (B) | SHA-256 | 废掉的原因 |
|:---|:---|:---|:---|
| `code8-incremental-1` (`c8d209b7…`) | 3,117,570 | `c8d209b714858602c8f272a7111bb399a13da74f11a09784ae2bf0fc7f7efb96` | 只跑了 `testDebugUnitTest assembleDebug`，未 clean |
| `code8-incremental-2` (`ca02dfe8…`) | 5,143,120 | `ca02dfe8f5af4974b54dfa1d5f0ab5107c884000b031ecf8f71c3b149a38becd` | 同上，且体积涨到 5.1MB —— 与 vc5 全量包同一异常特征 |

**再次印证：出包必须 `./gradlew clean ...`。** 增量构建会产出体积异常（5.1MB）的包，
zip 条目数与 `.so` 完全一致、看不出问题，只有体积暴露它 —— 与上文 vc5 那次同源。

**同一 versionCode 换内容必须换文件名**：`maxStreamingBitrate` 无关，APK URL 带
`cache-control: max-age=14400`（CF 边缘缓存 4 小时，`latest.json` 则是 `DYNAMIC` 不缓存）。
复用同名文件会让缓存里的旧包与新清单的 sha 对不上，车机校验后直接拒装。

另记一条排查坑：从 Mac 经共享盘写 `latest.json` 后**立刻**从公网拉，可能仍拿到旧内容
（源站刷新有秒级延迟）。判发布结果要轮询到内容变更为止，不能写完就看一眼就下结论。

## vc9 = 3.1.5 发布记录（2026-09-23 10:03）

正式发布物：**`ZSpaceCarPlayer-3.1.5-code9-2-debug.apk`**，3,118,945 B，
`sha256=84efd11b4e6896dde30c4070fca31b5d7c8136ec67c188d59906866b37b7dc54`，
`aapt dump badging` → `versionCode='9' versionName='3.1.5'`。`clean testDebugUnitTest assembleDebug` 出包，349 条 host 单测全绿；公网按清单 URL 实下载 sha256 与 size 逐字节一致。

同 versionCode 的首发件 `...-code9-debug.apk`（`57773b54…`，缺 `sort_index` 列缺失降级）已移到
`update/_rejected-superseded-builds/code9-1-no-column-guard.apk`，不再被任何清单引用。
换名原因见上文：**同名换内容会被 CF 边缘缓存（APK URL `max-age=14400`）配上新清单导致拒装**。

vc9 的内容要点（详见 `changelogs/3.1.5.md`）：无损 PCM 一律请服务端出 FLAC（已用 ffmpeg 逐字节验证无损）；
`songs` 表加 `sort_index` 让车机缓存只有服务端一份顺序，不再与 `name ASC` 码点序并存；刷新成功时
`saveLibrarySnapshot` 全量替换以清掉服务端已下架的幽灵条目。

## vc10 = 3.1.6 —— 曾短暂指向，已回滚，未成为正式发布物

**当前线上清单不指向 vc10。** 2026-09-24 10:18 曾把 `latest.json` 指到本包，随后因「未经用户看图确认就发版」回滚到 vc9；vc10 的版式（深色底 + 左右分栏）随后被 v3.2.0 整体取代。包体仍留在 `ZSpaceCarPlayer-3.1.6-code10-debug.apk`（3,118,446 B，`sha256=ecfa32bc23c6d74a6495c909b11a083243919b1b027c8ece603c3cce33f98089`，`versionCode='10' versionName='3.1.6'`），但不再被任何清单引用。

仍然有效的判断：移除与歌名重复的首字色块、进度条轨道 5→10dp、滑块 18→30dp、`saveLibrarySnapshot` 全量替换清幽灵条目。

教训一条：**发版前必须先出图给用户确认，"构建通过"不等于"可以发布"。** 回滚动作本身也说明清单是唯一的发布开关——改 `latest.json` 就是发版，改包体不是。

## vc11 = 3.2.0 —— 已发布（2026-09-24 23:50）

正式发布物：**`ZSpaceCarPlayer-3.2.0-code11b-debug.apk`**，3,121,850 B，
`sha256=60d70be162f01997f540428a536313d58372538be5cdcc0a000afbcffd782ce0`，
`aapt dump badging` → `versionCode='11' versionName='3.2.0'`。`clean :app:assembleDebug :app:testDebugUnitTest` 通过，371 条 host 单测全绿。包内已核验 `values-night` 资源与 `QueueRestore` / `LibraryOrder` / `NightModeManager` 三个新类均进 dex（不只看文件名）。

公网复核：按清单 `apkUrl` 实下载 → HTTP 200、`content-length` 与清单 `sizeBytes` 一致、下载件 sha256 与清单及本地包**三方一致**、`cf-cache-status: MISS`。发布前现网清单已备份为 `latest.json.bak-20260924-vc9`。

首发件 `ZSpaceCarPlayer-3.2.0-code11-debug.apk`（`9bd28452…`，3,121,885 B）**构建于传输方式接线修复之前**，已移到 `_rejected-superseded-builds/3.2.0-code11-pre-wiring-fix.apk`，不再被任何清单引用。换名而非覆盖的原因见下方铁律。

本版最重要的内容：`parseSongItem` 把 FLAC/下混裁定算进局部变量后从未传给 `SongItem`，导致 v3.1.2 起两条传输优化在所有已发布版本里完全空转（67 条真车上报无一条含 `audioCodec=flac`）。车机因此一直拉 172–173 KB/s 的原始 WAV，而蜂窝下载中位只有 169 KB/s。修复后 FLAC 实测 88–112 KB/s。详见 `changelogs/3.2.0.md` 第 6 节。

发布时源站清单刷新有秒级滞后（vc9/vc10 那次 poll1 仍回旧值、poll2 才变更），判发布结果要轮询到内容变更为止。

**发版铁律**：出包前先推进 `versionCode`；发布时把「构建时间 + 大小 + SHA-256 + `aapt2 dump badging` 的 package 行」记进本文件。判断"是否已部署"只认从设备 pull 回来的包哈希，不认版本号和 changelog。

**同一 versionCode 换内容必须换文件名**：APK 的 URL 在 Cloudflare 侧缓存 4 小时，沿用旧名会让清单是新的、包却可能命中旧缓存，两边 sha256 对不上时车机直接拒装（2026-09-24 vc11 就踩在这一步之前）。


## vc12 = 3.2.1 —— 已发布（2026-10-05 22:43）

正式发布物：**`ZSpaceCarPlayer-3.2.1-code12-debug.apk`**，3,123,085 B，构建于 2026-10-05 22:42:35，
`sha256=751a5960024aa9da8177fdfc5336a76c874c32f1fae36958630f3c405d391ae8`，
`aapt2 dump badging` → `package: name='com.ktools.zspacecarplayer' versionCode='12' versionName='3.2.1'`。
`clean assembleDebug testDebugUnitTest` 通过，**373 条 host 单测全绿（33 个结果文件 / 0 failures / 0 errors）**，
其中两条新增用例把退避边界钉死（6s/12s、第 3 次与预算外为 0、最坏累计静默 18s）。
沿用 debug 包的原因见下方铁律：`release` buildType 无 signingConfig，且车机覆盖升级要求同签名。

本版内容两条 fix：① 同曲重试加 6s/12s 退避并把恢复动作收口为唯一拥有者（此前三次尝试挤在两秒内烧光预算后跳歌，
且看门狗与退避链可在同一时刻各起一次播放）；② 升级检查的 TLS 底座装配移出主线程（Android 4.3 上 X.509 解析
+ TrustManagerFactory 初始化要几秒，`enqueue` 的异步原先只覆盖网络段），并补掉后台化引入的 `cancel()` 打空竞态。
详见 `changelogs/3.2.1.md`。

公网复核：清单轮询第 1 轮即返回 `versionCode=12`；按 `apkUrl` 实下载 → HTTP 200、
`application/vnd.android.package-archive`、`content-length` 与清单 `sizeBytes` 一致、
下载件 sha256 与清单及本地包**三方一致**。发布前现网清单已备份为 `latest.json.bak-20261005-vc11`；
vc11 包 `ZSpaceCarPlayer-3.2.0-code11b-debug.apk` 原地保留作回滚目标，未移入 `_rejected-*`。

⚠️ **设备侧未闭环**：按本文件铁律「判断是否已部署只认从设备 pull 回来的包哈希」，本次只做到公网三方一致，
**尚未从 8600 车机 pull 回落地的包核验**，也未做断网恢复的实车听感走表。接手时优先补这一步
（2026-10-05 22:48 车机 adb 与 ping 双不通，上报最后写入 21:18，物理上取不到）。

**发布包内容验证（比版本号强，比设备 pull 弱一级）**：解包 dex 后
`LC_ALL=C tr -c '[:print:]' '\n' < classes.dex > s.txt`，再 `grep -Fc`：
本次新增的 6 个串 `retry backoff wait=` / `retry backoff fired` / `update-check` /
`cancelled before bootstrap` / `cancelled during bootstrap` / `streamRetryBackoffPending` 全部命中，
正对照 `NETWORK_RECOVERY`、`STUCK heartbeat` 非 0（证明检测本身有效），dex 内认证头已是
`Version="3.2.1"`（CLIENT_VERSION 同步生效的硬证据）。
⚠️ 别用 `grep -ac "x" classes*.dex | awk -F: '{t+=$2}'`：只有一个 dex 时 grep 不输出 `file:` 前缀，
`$2` 取空 → 累计恒为 0，连旧版就有的串也会报 0，看着像"包里没有新代码"。
`Failed to instantiate extractor` 计数 0 属正常——那是系统 MediaExtractor 的异常消息，不是本 app 的字符串。

## vc13 = 3.2.2 —— 已发布（2026-10-05 23:15）

正式发布物：**`ZSpaceCarPlayer-3.2.2-code13-debug.apk`**，3,123,427 B，
`sha256=5b29b7d4e72fbb979cafc7d9b29892c6455f2e764fe97f7eb9358ce83143ffaf`，
`aapt2 dump badging` → `versionCode='13' versionName='3.2.2'`。
`clean assembleDebug testDebugUnitTest` 通过，373 条 host 单测 0 失败。

修的正是 vc12 漏掉的那一半：`MainActivity.onCreate` 里同步调的
`JellyfinApiClient.init() → initHttpClient()`（系统 trustmanager 枚举 + 内置 GTS 根 PEM 解析 +
OkHttp 建 `BasicTrustRootIndex` 遍历全部内置根 DN）——2026-10-05 当日 5 条 `main_thread_blocked`
里 4 条栈顶是它，而 vc12 修的 `UpdateChecker.ensureClient()` 只在点「检查更新」时才触发。
现 `init()` 只把装配排上 `tls-warmup` 单线程队列并立即返回，四处使用点走 `ensureClient()`
双检 + 同一把锁（构建只发生一次，预热与调用方兜底互斥）。

公网复核：清单轮询第 1 轮即 `versionCode=13`；按 `apkUrl` 实下载 → HTTP 200、3,123,427 B 与
清单 `sizeBytes` 一致、下载件／清单／本地包**三方 sha256 一致**。发布前清单备份为
`latest.json.bak-20261005-vc12`，vc12 包原地保留作回滚目标。

包内容验证：dex 抽可打印串后 `tls-warmup`、`tls client built on ` 均命中；正对照 `update-check`、
`retry backoff wait=`、`NETWORK_RECOVERY` 全部非 0（既证明检测有效，也证明 vc12 的两条修复
与 vc13 同在这一个包里，车机装 vc13 即一次拿到三项）。

⚠️ **仍未闭环（需车机上线）**：设备侧包哈希未 pull 核验；本版的终判据是升级后 `main_thread_blocked`
中 `initHttpClient` 栈顶计数归零，以及 logcat 里 `tls client built on <线程>` 是否仍出现在 `main`
（出现即说明第一批请求赶在预热之前，需把首次拉取也排到 warmup 之后）。vc13 **未走 host 单测**，
原因是被测对象为 Android 单例 + 线程编排 + Context 依赖，硬造接缝只能测到自己搭的假接口。

## vc14 = 3.2.3 —— 已发布（2026-10-05 23:34）

正式发布物：**`ZSpaceCarPlayer-3.2.3-code14-debug.apk`**，3,123,774 B，
`sha256=e1853801ac5bdf93137858c49c3bee57f62277e2ce57227d6655a2fdeb6d2e78`，
`aapt2 dump badging` → `versionCode='14' versionName='3.2.3'`。
`clean assembleDebug testDebugUnitTest` 通过，**374 条 host 单测 0 失败**（含本版新增的
`currentThreadStackDumpCoversOnlyTheCallingThread`，它钉住「dump 只含一个线程段」这个形状，
将来改回全线程遍历会当场变红）。

修的是 TLS 那条线之外的最后一处启动期主线程开销：`CrashMonitor.inspectPreviousSession()`
在 `ZSpaceApplication.onCreate → CrashMonitor.install` 上用 `Thread.getAllStackTraces()`
为 `abnormal_exit` 报告拍全线程栈。它既占主线程又**没有归因价值**——拍的是本次新进程的栈，
解释不了上次为什么退（当日那批 abnormal_exit 的 threadDump 全是 `installInternal` 自己的
启动栈，即直接证据）。改为新增的 `CrashReport.dumpCurrentThreadStack()`，字段保留、成本消失。

**另外两处全线程 dump 有意不动**（判据是「这一刻是否真需要所有线程现场」）：`:247` 主线程卡死
看门狗——必须看别的线程停在哪把锁上，且它本就跑在后台线程；`:543` Java 未捕获异常——进程正在死，
晚一帧现场就没了，必须同步抓完整。

公网复核：清单轮询第 1 轮即 `versionCode=14`；`apkUrl` 实下载 HTTP 200、3,123,774 B 与清单
`sizeBytes` 一致、远端／清单／本地三方 sha256 一致。发布前清单备份 `latest.json.bak-20261005-vc13`。
包内容验证：dex 内 `dumpCurrentThreadStack` 命中，正对照 `tls-warmup`／`update-check`／
`retry backoff wait=`／`NETWORK_RECOVERY` 全部非 0 —— 即 **vc12/13/14 的四项修复在同一只包里**，
车机装 vc14 一次拿全，不必逐级升。

⚠️ 设备侧仍未 pull 核验（车机 21:48 后离线）。本版起改由定时任务自动跟进，判据见下条。

## vc15 = 3.2.4 —— 已发布（2026-10-05 23:58）

正式发布物：**`ZSpaceCarPlayer-3.2.4-code15-debug.apk`**，3,124,523 B，
`sha256=e433065734e7f75f275743bb8802d267bb0a4a9470f4c13c3903ab7baac42981`，
`aapt2 dump badging` → `versionCode='15' versionName='3.2.4'`。
`clean assembleDebug testDebugUnitTest` 通过，**376 条 host 单测 0 失败**。

修两处（本次复盘列出的「已知未修」里的两项；退避期间 UI 状态经车主拍板明确不做）：

1. **原生 open 看门狗按「缓冲有无进展」判死**：旧实现 `sleep(15s)` 后无条件 abort，把慢但一直在下载的开流也掐死；而 8600 的 MediaCodec 没注册 FLAC/WAV，abort 后回退必然 `Failed to instantiate extractor`，重试耗尽就跳歌。现在轮询环形缓冲增量：有增长就顺延，连续 15s 零增长才收手，另有 45s 硬上限。判定收口 `BufferingPolicy.openShouldAbort()`。
2. **入库已知的时长接回播放管线**：流式 FLAC 容器 `frames=0` → `getDuration()=0` → `remaining=-1`，连带 `isBufferingStable` 恒 false（满屏 `STUCK heartbeat percent=-1` 即由此来）与预取 `nearEnd` 永不触发。时长一直在 `SongItem.durationMs` 里，新增 `IAudioPlayer.setKnownDurationMs()` 在 `setDataSource` 前喂入。

**有意不改**：`prefillTargetBytes` 的 768KB 退化——FLAC 约 100KB/s，768KB 就是约 7.7s 领先量，本来就够；`lead` 在 `percent<0` 时仍返回 -1，没有 contentLength 就不硬造下载头领先量。

公网复核：清单轮询第 1 轮即 `versionCode=15`；下载 HTTP 200、3,124,523 B 与 `sizeBytes` 一致、三方 sha256 一致。备份 `latest.json.bak-20261005-vc14`。
包内容验证：新串 `native open stalled no-progress`、`duration unknown in container` 命中；**旧措辞 `native open blocked` 在包里计数为 0**（证明换的是实现而不是并存一条新日志）；正对照 `tls-warmup`／`update-check`／`retry backoff wait=`／`dumpCurrentThreadStack` 全部命中 —— **vc12~vc15 的六项修复全在这一只包里，装 vc15 一次拿全**。

⚠️ 设备侧仍未 pull 核验（车机 21:48 后离线）。核验由 cron 任务「车机 vc15 升级与六项修复效果自动核验」每小时自动跑，七条判据写死在任务里，含「无法判定就直说、不许推测」。

## 回滚操作

versionCode 不可降级安装，必须先卸载 3.0.0：

```bash
adb connect 10.212.252.52:5555 && adb -s 10.212.252.52:5555 shell \
  "am startservice -a com.ktools.zspacecarplayer.ACTION_STOP_AND_RELEASE com.ktools.zspacecarplayer/.service.AudioPlayerService"
adb -s 10.212.252.52:5555 uninstall com.ktools.zspacecarplayer
adb -s 10.212.252.52:5555 install apks/release/ZSpaceCarPlayer-2.3.0-code2-debug.apk
```

卸载会清除本地 SQLite 曲库缓存与 SharedPreferences（含音效设置与崩溃熔断锁存），首启自动重新同步，服务端数据不受影响。回滚前建议先执行 `ACTION_STOP_AND_RELEASE`，让功放/DSP 音频通道优雅注销，避免 8600 通道锁死。

**引擎级回退不需要回滚包**：设置页「播放引擎」切回系统即绕过全部 v3 代码路径；崩溃熔断锁存时应用本身已处于该状态。
