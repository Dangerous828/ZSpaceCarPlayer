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

**发版铁律（2026-10-07 车主新增）：清单里的 `notes` 一律留空。**
更新弹窗是 `wrap_content` 的竖向 LinearLayout，长文案会把「立即下载 / 稍后」两个按钮顶出
720p 横屏之外——vc17 那次我写了约 140 字，车主直接点不到更新按钮（原话「弹窗太高了，
导致没办法点不到更新按钮」）。变更内容只写仓内 `changelogs/<版本>.md`，不进清单。
客户端同时给说明区加了 `maxLines=2 + ellipsize=end` 作结构性兜底（`dialog_update.xml`），
因为规矩会被忘，布局不会自己限高。

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

⚠️ **设备侧核验归属**：按本文件铁律「判断是否已部署只认从设备 pull 回来的包哈希」，vc12 当时只做到公网三方一致，
**没有从 8600 车机 pull 回落地的包核验**（2026-10-05 22:48 车机 adb 与 ping 双不通，上报最后写入 21:18，物理上取不到）。
这一步**不是无主待办**：vc12~vc15 的六项修复同在最后一只包里，设备侧核验已由 vc15 段落记录的
cron 任务「车机 vc15 升级与六项修复效果自动核验」接管，实车听感走表归车主。本节因此不再单独挂这一步。

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

⚠️ **设备侧核验归属（非无主待办）**：本版当时没有 pull 到车机落地包（车机离线）。本版的终判据是升级后 `main_thread_blocked`
中 `initHttpClient` 栈顶计数归零，以及 logcat 里 `tls client built on <线程>` 是否仍出现在 `main`
（出现即说明第一批请求赶在预热之前，需把首次拉取也排到 warmup 之后）——这两条已写进 vc15 段落记录的
cron 任务判据 2 与判据 3，每小时自动跑。vc13 **有意未走 host 单测**，
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

## vc16 = 3.2.5 发布记录（2026-10-06）

发布物：**`ZSpaceCarPlayer-3.2.5-code16-debug.apk`**，3,126,331 B，
`sha256=107ef5896a582d2c6a0bdb23055f7c6c31b6f023b1041b1501d2f1213082c734`，
`aapt2 dump badging` → `versionCode='16' versionName='3.2.5'`。
`clean :app:testDebugUnitTest :app:assembleDebug` 全量出包，**389 条 host 单测 0 失败**；
zip 填充 78,630 B（正常形状；本轮另一次增量构建曾产出 5,157,896 B 的包，与 vc5/code8 那次同源）。

本版把"弱网播不好"拆成三类、各给一条判据（详见 `changelogs/3.2.5.md`）：
① 服务端 404/410 首次即终态（旧行为按网络故障退避 5 轮 + 同曲重试，一个失效 Id 烧约 90s）；
② 链路追不上无损码率时自动降 **128kbps 流畅档**（实测需 110KB/s 而链路只给 67KB/s 的场景），
   3 窗口降级 / 5 窗口回升的滞回，只作用下一首，换档不丢 EQ/Bass/声场/混响；
③ open 看门狗分档：**一个字节都没下来按 4s 快速失败**，有过首字节才按 15s stall（vc15 为救慢流
   放宽到 15s，结果给最需要快速失败的形态最长耐心，还白占唯一解码线程）。

发布动作：清单与包**由 Mac 经共享盘直写** `Jarvis/data/caddy/web-zspace/update/`
（= caddy 的 `/data/web-zspace`，无需贾维斯参与；本次先派了一条带全盘 `find` 的单子，22 分钟无果，
属于自己绕路——落点本来就在映射盘可见处）。发布前清单已备份 `latest.json.bak-20261006-vc15`。

公网复核：清单轮询**第 1 轮**即 `versionCode=16`；按 `apkUrl` 实下载 HTTP 200、3,126,331 B 与
`sizeBytes` 一致、远端/清单/本地**三方 sha256 一致**。
包内容验证：dex 片段计数 `no-first-byte=`／`stalled no-progress=`／`deadline=`／
`-> smooth(128k)`／`start on smooth tier`／`resource gone terminal`／`gone on server, skip`／
`maxStreamingBitrate` 各 1；旧措辞 `native open blocked` 为 0；vc12~vc15 的
`retry backoff wait=`／`tls-warmup`／`update-check`／`duration unknown in container` 全部仍在
——**装 vc16 一次拿全九项**。

⚠️ 设备侧：vc15 已在真车验证（`appVersionCode=15` 上报 + `native open stalled no-progress`／
`retry backoff fired`／`prepared dur=215527ms` 三条面包屑都在），vc16 需等下次上电自动升级后由
cron 任务核验。**同一条坑照旧**：核验 dex 时按片段搜，新串是运行时拼接的，按整串 `grep` 会得到 0。

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

## vc17 = 3.2.6 发布记录（2026-10-07）

发布物：**`ZSpaceCarPlayer-3.2.6-code17-debug.apk`**，3,128,967 B，
`sha256=07557715f0fa80e0efe6a68fc9872df287a304ac9e1009b4b5e9ca5263ee54e6`，
`aapt2 dump badging` → `versionCode='17' versionName='3.2.6'`。
`clean :app:testDebugUnitTest :app:assembleDebug` 全量出包，**400 条 host 单测 0 失败**；
zip 填充 78,628 B（正常形状，不是那个 5.1MB 的增量假体积）。

**这一版主要是收拾 vc16 自己造成的回归**（详见 `changelogs/3.2.6.md`）：vc16 那条
「链路追不上无损码率就自动降流畅档」在真车上线 9 秒内就误判——`percent` 爬到 100（下载完成、
链路健康、lead 稳 65s）之后的零增长被读成 0KB/s，于是整列歌换到一条**无总长、不支持 Range、
`dur=0ms`、seek 失效**的转码流上，4 首全部被腰斩，而且每次都以「假播完」静默跳歌并清掉续播点。

九项：① 测速改「本曲所需速率」+ 可采门（总长与时长已知**且 `percent<100`**）；② 自动换档默认关闭，
只留 `bitrate deficit #N` 证据；③ 不可续传的流不再因读者挨饿而重连/判死（重连在 chunked 流上
= 服务端从 0 重转 + 客户端丢弃已下字节，是自伤）；④ 假播完用「最后 tick 位置 + 闩锁的判死证据」
裁定，第一次带断点重试、第二次跳歌但不清续播点；⑤ 深断点 seek 预算拆成挨饿截止(8s)+总预算(30s)，
且 seek 期间不再走「前向顺序丢弃」（今晚 Bad Romance 断点 7.4MB 落在那条 8MB 上限内，等于下载
7.4MB 永不播放的字节，必败）；⑥「缓冲中」改双门（下载口径**或**出声进展任一说卡就显示，
不可判不再当成还在缓冲）；⑦ 指示器在错误/暂停/解绑时幂等熄灭；⑧ 断点定位期间显示
「正在回到上次位置…」；⑨ 连播失败合并成一句「已连续 N 首无法播放…」。

发版前用 `code-review` 两轴审过一遍，7 条发现全部收掉；其中两条是真错：只看位置判截断会误杀
元数据偏大的正常播完（补了 `fatalLatched` 闩锁证据），以及状态提示的幂等键从不清 + 首 tick
被当成"已前进"（合起来等于那句话根本显示不出来）。

发布动作：清单与包由 Mac 经共享盘直写 `Jarvis/data/caddy/web-zspace/update/`；
发布前清单已备份 `latest.json.bak-20261007-vc16`。
⚠️ 清单键名踩了一次：我先生成的是 `forced`，而 `UpdateManifest.java:140` 解析的是 `mandatory`
（vc16 那份也是）——发布前自己比对了键集合才发现，已按 vc16 的键顺序重生成。

公网复核：清单轮询**第 1 轮**即 `versionCode=17`；按 `apkUrl` 实下载 HTTP 200、3,128,967 B 与
`sizeBytes` 一致；**仓内 / 发布目录 / 公网下载 / 清单四方 sha256 一致**。
包内容验证：dex 片段 `premature eos at `／`bitrate deficit #`／`starve ignored: source not resumable`／
`正在回到上次位置…`／`已连续 `／`stream truncated before its end`／`isSourceFatalEver`／`hasLatchedFatal`
各 1；vc16 的 `retry backoff wait=`／`buffered source error: `／`remoteAcceptsRanges` 全部仍在。

⚠️ 设备侧未核验（车机需下次上电自动升级 + 车主确认安装）。核验交给 cron，判据换成 vc17 的十条
（见 `changelogs/3.2.6.md`「实车判据」），重点三条：**不该再有 `bitrate tier -> smooth(128k)`、
不该再有 `prepared dur=0ms`、不该出现无解释的自动跳歌**。

## vc18 = 3.2.7 发布记录（2026-10-07）

发布物：**`ZSpaceCarPlayer-3.2.7-code18-debug.apk`**，3,129,009 B，
`sha256=b568c4c0f0a84bafe0fb29cf7ed999c7328ed80b0018d2700b5432a85a05435d`，
`aapt2 dump badging` → `versionCode='18' versionName='3.2.7'`。
`clean :app:testDebugUnitTest :app:assembleDebug` → **400 条单测 0 失败**。

本版只动布局与发版纪律（清单 `notes` 留空），没有新增判定逻辑，因此包体只比 vc17 大 42 B。
布局改动**在二进制里核过**：`aapt2 dump xmltree --file res/layout/dialog_update.xml`
可见 `android:maxLines=2`、`android:ellipsize=3`（平台 attrs.xml 里 `end=3`，不是 middle）。
注意 `--file` 要写 `res/layout/...`，写 `layout/...` 会报 `failed to find file.`。

清单实况（发布后从公网回读）：`versionCode=18`、`sizeBytes=3,129,009`、**`notes` 长度 = 0**、
`mandatory=False`。备份 `latest.json.bak-20261007-vc17`。
公网复核：清单轮询第 1 轮即 18；按 `apkUrl` 实下载 HTTP 200、3,129,009 B；
**仓内 / 发布目录 / 公网下载 / 清单四方 sha256 一致**。

⚠️ 设备侧未核验：车机需下次上电自动升级并手动确认安装。这条布局修复的最终判据只能在真屏上看——
弹窗里「立即下载 / 稍后」必须完整可见（见 `changelogs/3.2.7.md` 实车判据 1）。

## vc19 / vc20 发布记录（2026-10-08，一次回滚）

- **vc19 = 3.2.8**：3,129,064 B，`sha256=41fb1b5099313e3e…`。上线约 20 分钟后真车连续两条
  `abnormal_exit`（08:48:34、08:51:30，`appVersionCode=19`，`stackTrace` 0 字节 = 崩在原生）。
  可疑改动是本版把时长兜底接进 MediaCodec 分支（放行了一次"对不支持 Range 的 chunked 流做 seek"）。
- **vc20 = 3.2.9**：3,129,291 B，`sha256=e6e272d1aa3efd714bbac60693c03ab38e311252e0db837cdca5a60b9a588565`，
  `aapt2` 核 `versionCode=20 versionName=3.2.9`，401 条单测 0 失败，zip 填充 78,626。
  内容 = 撤回 vc19 的时长兜底与自动换档 + 把测速口径改成 socket 真收字节（窗口饱和时不采）。
  包内核验：`duration unknown in MediaCodec stream` 计数 **0**（确认撤回），`getSocketBytes`/`socketBytes` 各 1。
- 两次发布清单 `notes` 长度都是 0（新铁律生效）。备份链：`latest.json.bak-20261008-vc18`、`latest.json.bak-20261008-vc19`。
- 公网复核：清单轮询第 1 轮即命中；按 `apkUrl` 实下载 200、字节数一致、**三方 sha256 一致**。

## vc21 = 3.2.10 出包记录（2026-10-08，**未发布**）

包：**`ZSpaceCarPlayer-3.2.10-code21-debug.apk`**，3,146,698 B，
`sha256=5f4f0d6202f9b390a0ef8ea1f32ad92a4a8c3c9fb8c869d650e3e381240f8b9b`，
`aapt2 dump badging` → `versionCode='21' versionName='3.2.10'`，minSdk 18，zip 填充 78,626（正常形状）。
`clean :app:testDebugUnitTest :app:assembleDebug` → **436 条 host 单测 0 失败**。

**状态：包与清单草稿（`latest-3.2.10-code21.json`，`notes` 依铁律留空）都已入仓，
但线上 `latest.json` 与发布目录里的包体一个字节都没动 —— 车主指令是「全部都做完才 commit」，
发版要另外点头。** 内容见 `changelogs/3.2.10.md` 与 `docs/audit_t1_baseline_20261008.md`。
同版本内还有第二批 commit `17d6f32`（`StreamTuning` 四把回退闸、设置页三行新 UI、曲库滤掉
236 条带视频流的文件、随机模式"上一首"历史栈、睡眠定时、拔耳机暂停），最终包即上面这只。
逐项"已做/不适用/缺依据/需车主决策"判定见 `docs/audit_t1_baseline_20261008.md` §4。

出包过程中第三次踩到同一个坑：不带 `clean` 的增量构建产出 5,172,820 B（填充 2,116,886）的胖包，
`clean` 后回到 3.14MB。规则照旧：**发版必须 `clean` + `assembleDebug`（release 无 signingConfig）**，
且系统默认 java 已是 JDK 25，必须显式 `JAVA_HOME=…/openjdk@21/…`，否则 gradle 报
`Unsupported class file major version 69`。
