# ZSpaceCarPlayer 实车安装与调试（新会话开场白）

工程 `/Users/cpuser/Code/kTool/ZSpaceCarPlayer`，目标是吉利 8600 车机（Android 4.3 / API 18，无线 ADB `10.212.252.52:5555`，IP 可能变，先 `adb devices` 确认）。

代码侧刚完成四轮音频链路重构（防爆音 + 播放状态机 + 功放唤醒安全化），**已在本机验证：40 个单测全绿、Debug APK 构建成功、每处修复都做过变异测试**。工作区未提交，HEAD 仍是 `62da90a`。现在需要你做的是**实车安装 + 走表验证**，不要重新审查或重构代码，除非实车暴露出问题。

## 先做这一步

```bash
cd /Users/cpuser/Code/kTool/ZSpaceCarPlayer && ./deploy_to_car.sh
```

脚本会自动选 JDK 21、连 ADB、编译、`adb install -r`、拉起 `MainActivity`。另开一个窗口盯日志：

```bash
adb shell "logcat -c" && adb shell "logcat | grep -iE 'ZSpaceCarPlayer|AudioPlayerService'"
```

## 实车走表（按优先级，前 3 项是本轮改动的核心）

1. **长暂停恢复有声**（本轮头号修复）。播放中暂停 ≥2 分钟，再按播放 → 必须出声。
   日志应看到 `Amp wake nudged, key=...`。若无声，先看这行在不在。
2. **静音红线**。车机媒体音量调到 0，然后起播 / 切歌 / 暂停恢复 → 音量必须**保持** 0，
   绝不能被自动解除静音或抬升。这是硬红线，出现即回滚。
3. **焦点被别的播放器抢**。打开车机自带音乐播放几秒，切回本应用按播放 →
   不应出现永久无声，也不应刷一串「正在自动恢复播放」提示。
4. **连续快速切歌**。快点下一曲 10+ 次 → 无可感知爆音、无 DSP 静音锁死。
5. **反复拖动进度条** → 无爆音；拖动不应把「播放最多」榜刷歪。
6. **语音助手打断**。「你好吉利」播报 → 音量平滑压低再平滑恢复，只恢复一次；
   播报期间按下一曲，用户命令要生效且不会跳回旧曲。
7. **弱网续播**。关 Wi-Fi 1 分钟再开 → 从断点续播。
   另测：切歌后立刻断网 → 新曲**不应**从上一首的进度恢复（本轮修的断点按曲目隔离）。
8. **方向盘按键**。PLAY 只播放、PAUSE 只暂停（幂等，不能反转），NEXT/PREV 正常。
9. **退出应用**。后台杀掉或退出 → 不应有爆音（退出前会同步静音再 release）。
10. **重启车机**。开机后断点续播；无历史记录时不应抢播、不应意外升音量。

## 已知的非问题，不要去追

- `lintDebug` 会 fail build，长期是 **2 errors / 113 warnings**（`SongAdapter.java:73`
  固定 adapter position、targetSdk 28）。验证时只跑 `testDebugUnitTest assembleDebug`。
- 编译期 Java 8 source/target 过时警告、Gradle 9 兼容性提示，都属既有噪声。
- Outbox/重复投递之类与本项目无关。

## 出问题时的排查入口

- 暂停久后恢复无声 → 日志找 `Amp wake nudged`；没有就看 `GeelyAmpWakeStrategy`
  的 5 秒进程级限频和「系统音量为 0 直接 return」是否把这次拦掉了。
- 反复弹「正在自动恢复播放」→ 说明看门狗在重建串流，检查 `watchdogAction()` 判成了
  `REBUILD_STREAM`（正常情况下焦点被拒应判 `RETRY_FOCUS`，静默重申请焦点、不弹提示）。
- 进度走但无声 → 看有没有 `Stream stalled at ...ms`（断流）或 `Watchdog auto-resume`（假播放兜底）。
- 爆音 → 记录发生在哪个动作（切歌 / seek / 暂停 / 退出），最好用手机在车内录一段，
  这类问题必须有音频证据才能定位，光看日志判断不了。

## 约束

- 只有我明确要求时才 `git commit`；工作区还有大量其他未提交改动，不要顺手清理或格式化。
- 改任何 `service/` 下的播放逻辑后，必须重跑 `./gradlew testDebugUnitTest assembleDebug`，
  并说明改动是否触碰了功放静音红线。
- 车机是共用设备，涉及重启 MCU、刷机、清数据这类操作先问我。

先跑部署，把 `adb devices` 和安装结果贴给我，然后我们按上面的表逐项过。
