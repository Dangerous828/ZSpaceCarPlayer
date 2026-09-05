# 逆向分析成果报告与 ZSpaceCarPlayer 架构规划输入

## 1. 背景与车机运行环境
- **车机硬件/芯片**：吉利 8600 车机（亿咖通 / Geely），Android 系统，横屏（sw720dp 适配档位）。
- **已解决问题**：假播放看门狗（自动断流重连）、切歌进度分叉归零与 DSP 功放链路修复（车机已完成 1:1 走表验证并重启 MCU）。
- **本次任务**：结合刚刚对主流车载音乐 App（QQ 音乐车载版 APK：`/Users/cpuser/Downloads/10200113.apk`）的逆向工程分析成果，为 `ZSpaceCarPlayer` 梳理并制定一套专门针对**吉利车机**的 DSP 链路、音频焦点（AudioFocus）、车载硬件广播、后台生命周期与防爆音治理的落地演进计划。

---

## 2. QQ 音乐车载版 (com.tencent.qqmusiccar) 核心逆向发现

### 发现一：车机专属适配层 `ISpecialNeedInterface` & 音频焦点柔性降级
- 车载音乐 SDK 抽象出 `ISpecialNeedInterface` 接口对车机底层驱动做解耦。
- **关键设计**：
  - `needRequestFocus()`：是否强制依赖系统焦点。
  - `playWhenRequestFocusFailed()`：**焦点抢占失败兜底机制**。在吉利等车机中，语音助手（如“你好吉利”/领克语音）播报结束后，车机系统可能延迟发送 `AUDIOFOCUS_GAIN`。如果强依赖焦点回调，应用会一直处于静音阻塞状态。QQ 音乐的处理是在用户主动发起播放或切歌时，即使系统暂时没有给予焦点，依然强行允许音频流写入，强行拉起车机底层 DSP 功放通道。
  - 遇到 `LOSS_TRANSIENT_CAN_DUCK` 时，通过 `setVolumeRatio` 平滑压低音量（如降为 0.2），而非粗暴 stop，播报完毕再平滑淡入。

### 发现二：切歌 / Seek 时的 DSP 爆音与锁死防御序列 (`AudioTrackHandler.o(long)`)
- 车机 DSP 功放链路直接写入或在 Playing 状态下直接 `flush()` 容易导致 PCM 缓冲区残留脏数据，引起车机喇叭爆音、电流破音甚至功放硬件通道静音锁死。
- **QQ 音乐的标准安全序列**：
  ```java
  if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
      audioTrack.pause(); // 1. 先暂停
      audioTrack.flush(); // 2. 清空底层硬件缓冲区残留
      audioTrack.play();  // 3. 恢复播放
  } else {
      audioTrack.flush();
  }
  ```
- **音轨安全销毁三步走 (`AudioTrackHandler.y()`)**：
  必须按顺序执行且各自带独立的 `try-catch(Throwable)`：
  `audioTrack.stop()` -> `audioTrack.flush()` -> `audioTrack.release()`。

### 发现三：AudioTrack Buffer 动态衰减重试算法
- 车机 DSP/声卡驱动在不同采样率（如 44.1kHz、48kHz、96kHz Hi-Res）下，可能因为分配大 Buffer 导致 `audioTrack.getState() != STATE_INITIALIZED`。
- **处理策略**：计算初始 bufferSize（如 4~8 倍 minBufferSize），若初始化失败，采用步长衰减重试（`times -= 2`），直到 `STATE_INITIALIZED`，避免声卡初始化直接 crash 或哑巴播放。

### 发现四：车机专属广播中心与媒体控制 (`BroadcastReceiverCenterForThird`)
- 吉利车机除标准 `MEDIA_BUTTON` 键值外，系统语音助手、仪表盘、车机下拉快捷栏会广播专属 Intent：
  - 核心 Actions: `com.wedrive.action.COMMAND_SEND`, `com.tencent.qqmusiccar.action`
  - 关键操作码：`0: 播放`, `1: 暂停`, `2: 上一曲`, `3: 下一曲`, `7/8: 快进/快退`, `101/103/105: 循环模式切换`。

### 发现五：前后台生命周期与媒体按键保持 (`LifeCycleManager`)
- 进入后台保持 `MediaSessionCompat` 活跃，确保车机仪表盘与中控桌面 Widget 实时显示歌曲元数据与封面。
- 回到前台时触发 `registerMediaButtonWhenInForeground()` 强制夺回方向盘硬按键控制权。

---

## 3. 请 Opus 5 Subagent 执行的任务
1. **深度对比**：全面分析上述 QQ 音乐车载版逆向成果与当前 `ZSpaceCarPlayer` 项目实现（重点关注 `AudioPlayerService.java`、`MediaButtonReceiver.java`、`BootReceiver.java`、`MainActivity.java`）。
2. **吉利 8600 特化建议**：针对吉利 8600 车机的 DSP 特性与弱网环境，评估哪些机制最具落地价值（优先级 P0/P1/P2）。
3. **架构改造与演进计划**：制定一份详尽、可分步实施的代码改造计划（包括类名、方法改动建议与时序图说明）。
