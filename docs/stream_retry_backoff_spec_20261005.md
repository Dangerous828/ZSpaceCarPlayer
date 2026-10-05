# 车机同曲重试的退避节奏（stream retry backoff）

日期：2026-10-05　适用版本：v3.2.0 / vc11　状态：待实施

## Problem Statement

播放链路发生可恢复的网络错误时，服务层 `handlePlayerError` 对 `PLAIN_RETRY` 是**立刻**重放当前曲目
（`AudioPlayerService.java:1981-1986` → `reportPlaybackError` 后直接 `replayPendingSong`，全程无 sleep）。
蜂窝链路抖一下通常要几秒才回来，所以这三次尝试几乎都撞在同一个坑上：白烧下载流量、把
`MAX_STREAM_RETRY_ATTEMPTS=3` 的预算在两三秒内烧光，然后 `GIVE_UP` 跳到下一首。用户听到的就是
「歌自己跳了」，而实际上网络再过几秒就能撑起原来的曲子。

同一时刻还存在**第二个恢复驱动者**：假播放看门狗有自己独立的指数退避（`DEAD_RETRY_TICKS_START=12`
即 6s、翻倍、`DEAD_RETRY_TICKS_MAX=120` 即 60s 封顶，`AudioPlayerService.java:131-133` 与 `:517-538`），
它也会调 `autoResumeFromLastPosition` 起播当前曲目。两条路径各自计时、互不感知。

对比之下，下载层（`BufferedHttpSource.java:719` `sleepInterruptible(retry * RETRY_BASE_MS)`）和假播放
看门狗都有退避，**唯独 onError → 同曲重放这一段没有**，属于同类机制的缺口。

## Solution

把「同曲重放」从立刻执行改成**按固定节奏等待**，并明确恢复动作的唯一拥有者是服务层的退避链：
进入退避时立刻给用户一条提示，等待期间看门狗让位不再另起一次播放，等待到点后才真正重放，
断点沿用失败时的位置。节奏由 `PlaybackStateMachine` 里的纯函数决定，边界用 host JVM 单测钉死，
不靠真车撞阈值。

本次只修这一件事：不放宽原生 open 死线、不动 UI、不动传输方式裁定。

## User Stories

1. 作为在车里听歌的人，我希望经过地库或隧道断网后，歌曲自己等网络回来接着播，而不是马上跳到下一首。
2. 作为在车里听歌的人，我希望听到「网络波动，自动重试」这类提示后，接下来的等待是有节奏的，而不是连着几次卡顿叠加。
3. 作为在车里听歌的人，我希望重试最终放弃时，歌曲仍然会跳到下一首、并告诉我被跳过的是哪一首（这条现有行为必须保住）。
4. 作为在车里听歌的人，我希望退避之后的续播仍然从我被打断的那个位置开始，不要回到歌头。
5. 作为排查者，我希望上报记录里能看出两次重试之间实际隔了多久，从而判断退避是否真的生效。
6. 作为排查者，我希望退避期间不要再出现「同一个时刻两条路径各自起播」的重复起播现场。
7. 作为维护者，我希望退避时长序列是一个纯函数，能在 host JVM 单测里把 attempt 到等待毫秒的边界钉死。
8. 作为维护者，我希望退避期间看门狗的让位条件是显式的，和现有鉴权让位（`authWaitInProgress`）用同一种表达方式。
9. 作为维护者，我希望任何打断当前播放上下文的动作（手动切歌、自动切歌、放弃、释放、新一轮起播）都能取消掉尚未到点的退避，不让它打到下一首歌上。
10. 作为维护者，我希望退避不改变 `StreamRetryAction` 的分类语义——`AUTH_RETRY` 仍然先重新登录再重放，不给它加等待。
11. 作为开发者，我希望这条链在弱网下最坏总耗时是可算的（两次等待之和 + 各自起播耗时），并在文档里写明，便于以后上车实测时对照。

## Implementation Decisions

**做哪种重试的退避**
1. 只给 `PLAIN_RETRY` 加退避。`AUTH_RETRY`（第 3 次）走 `authenticateFromPrefs` 异步回调再重放
   （`AudioPlayerService.java:1989-2010`），本身就在等网络，再加延迟只会更差。
2. 序列取 **6s、12s**：与看门狗起点（6s）同档、逐次翻倍。最坏情况是两次等待共 18s，之后走 `GIVE_UP`
   提示 + 跳下一首。选这个值的理由不是"更保守"，而是**跳下一首在这台机器上不会更快**：3.2.0 走 FLAC
   流式后 `dur=0ms` 常态，`BufferingPolicy.shouldPrefetchNext` 在这些曲上永不触发（下一首预取彻底失效），
   切歌等于零缓冲从头起播，比原地多等 6s 更容易再次卡住。

**谁拥有恢复动作**
3. 服务层退避链是唯一拥有者。新增一个显式的退避挂起标志（表达上仿照既有 `authWaitInProgress`），
   看门狗的重建分支在挂起期间让位——沿用它在 `:520-526` 已有的「鉴权挂起时交给 auth 流程」同一模式。
4. 挂起标志的复位点是本次的主要风险，必须逐处覆盖并在验收里点名：退避到点执行时、用户或自动切歌时、
   `GIVE_UP` 时、服务 `release`/销毁时、任何新一轮 `startPlayback` 起播时。漏掉任一处会造成"永远不再重试"
   或"迟到的重放打到另一首歌上"。
5. 尚未到点的退避必须可取消（跟着上面那些复位点一起取消），不接受"靠 generation 校验在回调里丢弃"
   这一种做法兜住全部场景——因为 `release` 之后延迟任务仍可能残留在 handler 上。

**断点与提示**
6. 待重放的曲目与位置在**进入退避时**就捕获进延迟任务（`failedSong` 与已经算好的 `finalResumeMs`），
   执行时不得回读全局 `pendingSeekMs` 或 `getCurrentSong()`——调用点在 `:1982` 已把 `pendingSeekMs` 清成 -1，
   回读必然拿到错值。执行前按 `AUTH_RETRY` 那条路径的先例校验 generation。
7. 提示时机放在**进入退避时**（即现有那条「网络波动, 自动重试 <曲名>」文案提前到等待开始那一刻），
   而不是等到真正重放时。否则用户在 6s 静默里得不到任何解释。文案内容本身不改、不加倒计时、不加新状态。

**边界收口**
8. 「第 N 次重试该等多久」判定放进 `PlaybackStateMachine`（与既有 `streamRetryAction` 并列，
   `:416-430`），保持不依赖 Android 时钟与线程，好让 host JVM 单测钉死边界——这是仓内既定套路
   （`BufferingPolicy` / `GeelyAmpWakeStrategy` / `PlaybackStateMachine` 三处都是这么做的）。
9. 进入退避与到点执行各留一条 `CrashMonitor` 面包屑（含 attempt 与等待毫秒），使第 5 条 user story
   能在上报记录里被核对。注意现有 `prefill gate` 那类结论只写 logcat、不进 breadcrumb，因而极易丢失——
   本次两条必须走 breadcrumb。

## Out of Scope

- **`NATIVE_OPEN_TIMEOUT_MS` 15s 死线放宽或可配**（`DspAudioTrackPlayer.java:44-46`）。它只护 open 阶段
  （首 16 字节嗅探 + `drflac_open`），`abort()` 也只置取消标志、不杀连接（`NativeLosslessDecoder.java:325-329`），
  之后是回退系统 MediaCodec 而非重试；实测频率是每 5.1 次起播 1 次，不是「每首都跳」。留待单独评估。
- **`UpdateChecker.ensureClient()` 出主线程**——本次修复清单里的第二项，独立一轮。
- **退避期间的 UI 可见状态**（状态条「重试中 N 秒」或复用缓冲指示）。等实车确认 6s 静默是否可接受再说。
- **`dur=0ms` 连带缺陷**：prefill 门槛退化为 768KB+7s 超时、`isBufferingStable(-1,-1,-1)` 恒 false
  （满屏 `STUCK heartbeat percent=-1` 即由此而来，不是真卡住）、下一首预取永不触发。要单独一条线做。
- **传输方式裁定与 TLS**：`shouldUseServerFlac` / `audioCodec=flac` / `TlsCompat` 的信任锚策略一律不动
  （后者「只增加信任锚、绝不放宽主机名校验」是刻意的不变量）。
- 不动 `versionCode`、不发 OTA、不改 `latest.json`。

## Testing Decisions

**好测试的标准**：只测外部行为——给定重试轮次得到该等多久、以及重试分类语义不因退避而改变；
不去断言线程、真实时钟或 handler 调度。不测内部字段。

**主 seam（唯一必需）**：`PlaybackStateMachineTest`（已存在，host JVM 无 Android 依赖）。
覆盖：第 1/2 次分别对应 6s/12s；轮次超出预算时的返回与 `GIVE_UP` 边界不冲突；预算常量 3 的语义不变；
序列不得与看门狗的 tick 常量互相污染（两处 6s 是**各自独立**的起点，改一个不能动另一个）。

**次 seam（仅当接口形状需要）**：若「退避挂起期间看门狗让位」这条判定也能收口成纯函数，则在同一文件补
一个用例——输入挂起标志与看门狗状态，输出必须是不重建。否则不强求，避免把 Android 时钟塞进单测。

**明确不动的 seam**：`StreamStarvationTest` / `StreamBufferLayerTest` / `BufferingPolicyTest`——本次不改
涓流判定与缓冲门槛；`StreamTransportChoiceTest` / `StreamTransportWiringTest`——不改传输裁定。若这些
测试变红，说明改动越界了，应回退而不是改测试。

**先写红测**：实施走 TDD，先让「attempt → 等待毫秒」的边界用例失败，再补纯函数与接线。

**实车部分（后续，不在本次交付判据内）**：停车断热点观察是否「不再连打三次、6s 后第二次、12s 后第三次、
然后跳下一首」；再点一次一键上报，用 `scripts/crash_reports.py --show <reportId>` 核对面包屑里两次
`NETWORK_RECOVERY` 的实际间隔是否等于 6s/12s。
