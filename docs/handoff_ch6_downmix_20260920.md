# 多幸运 (ch=6) 刺耳问题 — 6 声道下混

日期：2026-09-20
状态：**已实现并发布为 v3.1.3 (versionCode 7)，公网 OTA 清单已切换；实车听感复验待做。**

## 问题现象

- 车机（吉利 8600，Android 4.3，Neusoft）播放「多幸运」时声音刺耳；
- 同一歌单（抖音热歌）其他歌正常，切到下一首「差一步」(ch=2) 立刻正常；
- 上报数据在 NAS：`/opt/data/Jarvis/data/crash-reports/crash-20260920.jsonl` 第 43 条（reportId `1a0bf0bf99c-fa91`，manual_diag，21:40:38 收到，app 3.1.2）。

## 根因

「多幸运」id=`1277770fe963b48f46eb0e65c066489a`，RIFF/WAV **6 声道**，走 v3 native lossless 直解：
`[v3] native pipeline up fmt=2 sr=44100 ch=6 dur=249219ms`。解码、prefill、AudioTrack start 全程无报错，所以"日志零异常"是表象。三处叠加构成实害：

1. **DSP 对 6 声道整块透传**：`app/src/main/cpp/DspEngine.h:71` `DspEngine::process()` 只有 `channels == 2`（:76）与 `channels == 1`（:109）两个分支，ch=6 时一个样本都不处理（无 EQ / bass / widener / reverb / limiter）。`app/src/main/cpp/native-lib.cpp:18` 的 `MAX_CHANNELS = 8` 放行了 ch=6 的 `nativeInit`。
2. **AudioTrack 只能立体声**：`DspAudioTrackPlayer.java:379` 以 `renderChannels` 选 `CHANNEL_OUT_MONO/STEREO`，系统不支持 6 声道裸 PCM 轨。
3. **渲染循环按母带声道数写立体声轨**：写入长度取 `framesGot * 母带声道数`。

错配的量化机制（`scripts/verify_ch6_downmix.mjs` 输出）：

| 项 | 数值 |
|---|---|
| 立体声帧的 L/R 实际来源 | `(FL,FR) → (FC,LFE) → (BL,BR)` 三帧一循环 |
| 相邻样本跳变均值 | 错配 25618 vs 下混 1144（**22.4 倍**） |
| 时长 | 同样内容被拉成 **3 倍时长**（4s → 12s），进度条与听感脱节 |
| LFE | 被送进全频单元 |

相位抵消 + LFE 上全频 + 每 3 帧换一次声道，听感即"刺耳"。

## 修复

### 新增 `app/src/main/java/com/ktools/zspacecarplayer/player/PcmDownmix.java`

纯 Java、零 Android 依赖（可直接 JUnit）。

- `effectiveChannels(ch)`：`ch > 2 → 2`，`1/2` 原样。全链路唯一的裁定入口。
- `toStereo(short[])` / `toStereoBytes(byte[])`：native 无损路径与 MediaCodec 回退路径各一个入口，共用同一份权重（`gainL` / `gainR`），数学完全一致。
- 权重取 ITU-R BS.775 的 Lo/Ro 式：`L = FL + 0.707·FC + 0.707·环绕 + 0.5·LFE`，R 对称。0.707 是等功率声像法则。**LFE 按 0.5 并入而非丢弃**——车机没有独立低音炮，LFE 只能由全频单元发声，丢弃会让 5.1 母带低频偏薄；但 LFE 常是大动态纯低频，按 0.707 并入会把下级 BassBoost 推到频繁限幅。
- 索引 3 只在 `channels >= 6` 时按 LFE 计权，3/4/5 声道布局里那是又一个环绕声道，按 0.707 计权。
- **Q13 定点**累加：8 声道全满幅同相时 worst = 1.35e9，仍留在 int32 范围内（脚本第 [6] 节逐档验证 3~8ch）。取 Q14 会溢出。
- **0.95 满幅起 tanh 软膝**（`KNEE = 31129`，与 `SoftLimiter.h` 的 threshold 同值同曲线）。膝点必须从 `KNEE` 起压：若写成"超过满幅才起压"，则 v=32767 原样透传、v=32768 被拉回膝区起点，单样本向下跳 1600——比硬钳位更响的爆音。`PcmDownmixTest.saturationCurveIsMonotonicAndContinuousAcrossTheKnee` 按 amp 扫过整个膝区专抓这类错误。
- 入口边界闸：`channels` 不在 3~8、`frames <= 0`、src/dst 容量不足一律抛 `IllegalArgumentException`。渲染循环的 `catch (Exception)` 会把它转成 `DECODE_FAILED` 上报，比"继续放刺耳声"可见。

### 接线 `DspAudioTrackPlayer.java`

| 位置 | 改动 |
|---|---|
| `:86` | 新增 `renderChannels` 字段（与 `channelCount` 分开：PCM 读取缓冲必须按母带声道数分配，不能跟着缩） |
| `:362-379` `initAudioTrackAndDsp()` | 唯一下混裁定点。`NativeDsp.init(sampleRate, renderChannels)`、AudioTrack 的 `channelConfig` 都改读 `renderChannels`；`Log.i` + `CrashMonitor.breadcrumb("v3", "downmix 6ch->2ch sr=…")` + `putContext("downmixTo", 2)` 三处留痕，下次上报能直接看出下混是否生效 |
| `:529-607` `nativeRenderLoop()` | 循环外分配同等帧数的立体声 scratch 复用；`readSamples → toStereo → processShorts → write(framesGot * outChannels)` |
| `:738-820` `codecRenderLoop()` | 字节入口 `toStereoBytes`；**分块改为整帧对齐**（`chunkCap = 8192 - 8192 % (2*ch)`）——8192 对 6 声道不整除，切在帧中间会让下混读到半个样本 |
| `:850-861` `INFO_OUTPUT_FORMAT_CHANGED` | 同步重算 `renderChannels`，scratch 置 null 交下一轮按新帧长重分配 |

**ch=1 / ch=2（绝大多数歌）路径逐值等价**：`needsDownmix` 为 false 时 `dspBuf` 就是 `pcmBuf`、写入长度仍是 `framesGot * channelCount`；`effectiveChannels(1)=1`、`effectiveChannels(2)=2` 与原 `channelCount == 1` 判定同结果；codec 路径 ch=1/2 时 `chunkCap` 都等于原来的 8192。

## 验证

1. **单测**：`PcmDownmixTest` 12 例 + 基线 318 = **330 例全绿，0 skipped**。覆盖单位激励权重表、中心声像不偏移、全通道满幅不回绕、7.1 累加余量、byte/short 双入口逐样本一致、src/dst offset、容量不足必抛、`ch <= 2` 与 `ch > 8` 必拒。
2. **突变验证**（确认断言有判别力，不是同义反复）：把 LFE 权重 0.5 改成 1.0 → `unitExcitation…` 失败；把软膝起点从 `KNEE` 改回 `CEILING` → 单调连续性用例在 `amp=19195` 精确失败。两处均已复原。
3. **本地验算**：`node scripts/verify_ch6_downmix.mjs`。第 [7] 节按真实母带电平分配（FL/FR −6dBFS、FC −8、LFE −14、环绕 −12，每通道独立相位，20 万帧）扫描 headroom：不预留衰减时峰值 1.13~1.17 满幅，**越过满幅的样本仅 0.01%，超过 1.35 满幅的 0%**，全部落在软膝可覆盖的浅度超载区。据此**不加预衰减**——−3dB 会让 5.1 曲目比同歌单的立体声曲目明显轻，而收益接近零。
4. **未做**：实车播放复验（需要上车，见下）。

## 发布与上车

已发布 **v3.1.3 (versionCode 7)**：`build.gradle` 版本已推进，`changelogs/3.1.3.md` 与 `apks/release/` 归档、`latest.json` 清单齐备，物料由贾维斯 `docker cp` 落位 `jarvis-caddy:/data/web-zspace/update/`，发布后从公网复核过清单与包哈希（详见 changelog 的发布记录）。

上车两条路任选：

- **OTA**：车机设置页「检查更新」→ 拉到 v3.1.3 → 安装（明天可直接点这条）。
- **adb**：`./deploy_to_car.sh`（车机 `10.202.110.52:5555`，走 `assembleDebug` + `install -r`）。

实车验收点：抖音热歌播「多幸运」，确认听感正常，且日志在 `native pipeline up … ch=6` 之后紧跟一行 `downmix 6ch->2ch sr=44100`；再抽一首 `ch=2` 与一首 `ch=1` 确认无 `downmix` 留痕且音量/音效无变化；整曲走完 4:09 不中途乱跳。

## 待办（另开线）

1. AMP 功放唤醒重试（`Amp wake retry attempt=1/2`）与音量 index 12↔13 抖动——疑似相关，排查 `AudioPlayerService` 的 Amp wake 逻辑；
2. 网络侧：`network stall over 30000ms` / `STUCK heartbeat`，查 caddy→jellyfin 链路（IPv6 回落或连接池空闲超时）；
3. crash-ingest 服务端：`source_ip` 被 caddy 反代吞掉（应读 `X-Forwarded-For`）；历史上的 `TZ` NameError 曾致 502 丢包，现版本已修但值得复核；
4. 「多幸运」文件本身是 5.1 WAV，库侧统一转码成立体声是治本，客户端下混是兜底。

## 构建环境备注

`./gradlew` 必须用 JDK 21（`/opt/homebrew/Cellar/openjdk@21/21.0.9/...`）；JDK 25 下 Gradle 8.9 在 daemon 启动阶段即挂。该路径带 `com.apple.provenance` 扩展属性，偶发被判定为 invalid directory，重试即可。管道 `| tail` 会吞掉真实退出码，验证构建结果要用 `> log 2>&1; echo $?`。
