# ZSpaceCarPlayer v3.0 架构升级规划：自研解码与独立软件 DSP 引擎

## 一、项目背景与当前痛点
1. **运行平台**：吉利 8600 车机（Geely E01/E02），亿咖通定制 Android 4.3（API 18），东软 Neusoft DSP / Audio HAL。
2. **后端服务**：极空间 NAS（Z4）上的 Jellyfin 音乐流媒体服务（支持 FLAC / APE / AAC / MP3 / WAV 串流）。
3. **当前实现**：
   - 依赖 Android 系统黑盒 `MediaPlayer` 与标准系统音效 `android.media.audiofx.*`（Equalizer, BassBoost, Virtualizer, EnvironmentalReverb）。
4. **致命痛点**：
   - **音效失效**：车机 Neusoft HAL 驱动极其偷懒，`Virtualizer.getStrengthSupported()` 返回 false（导致全景声场滑块拖动无反应，只以厂商固定极弱默认值运行）；底层 AudioFlinger 主混音线程未打通 Aux 辅助混响总线（`Aux Buf` 全空，`EnvironmentalReverb` session 0 aux effect 成为空桩，毫无混响感知）。
   - **硬件锁死**：系统底层 mediaserver 极其脆弱，`Virtualizer` 在 `setEnabled(false)` 时未拷贝 PCM 导致输出静音；覆盖安装强杀未走完整释放时，Neusoft 硬件混音通道直接 `blocked in write: 1` 彻底死锁，必须整机软重启。
   - **行业差距**：实车解包同类车机 App（QQ音乐车载版 `libSuperSound3.so`、酷狗车载版 `libviper4android.so` 蝰蛇音效、酷我车载版）发现，头部车机 App 100% 采用**自建软解码管线 + 应用内私有 C++ 纯软件 DSP 矩阵运算 + 裸 PCM AudioTrack 写入**，完全零依赖系统 audiofx 和 Neusoft 缺陷驱动。

## 二、本次规划目标
设计一份**完整、健壮、可落地的技术重构与大版本演进方案（v3.0）**：

1. **音频解码管线选型与改造**：
   - 评估自建解码管线方案（如轻量级 FFmpeg / 自解码管线 vs ExoPlayer 针对 API 18 的精简改造）；
   - 从 Jellyfin HTTP/HTTPS 串流接收音频数据包并解出标准 PCM（16-bit, 44.1kHz/48kHz Stereo）。

2. **软件 DSP 引擎集成（对标 QQ 音乐银河音效 / 酷狗蝰蛇）**：
   - 调研并设计集成成熟开源 C/C++ DSP 核心（首选推荐 `JamesDSP` 核心库 `JamesDSPLib`，或其它高品质轻量级 DSP）；
   - 实现三大核心音效：
     ① **全景声场 / 立体声展宽（Spatializer / BS2B / HRTF）**；
     ② **空间混响（高品质纯软件卷积/算法混响，如 Progenitor2，彻底摆脱系统 Aux 总线依赖）**；
     ③ **动态重低音增强（Dynamic Bass Boost）** 与 **高阶参量均衡器（Parametric EQ）**；
   - 暴露干净的 JNI 接口给 Java 层调用，支持实时平滑调节参数且防爆音/防截幅（Soft Limiter / Gain Envelope）。

3. **音频输出与车机兼容性设计**：
   - 使用标准 `AudioTrack` 写入处理后的 PCM；
   - 如何在 Android 4.3 (API 18) 上优雅保证缓冲区抗抖动、防止 Underrun 卡顿，并确保后台播放保活；
   - 如何无缝接驳吉利 8600 车机的物理方向盘切歌按键、`AudioManager` 音频焦点（AudioFocus 导航/语音打断 Ducking 降音量 20% 与恢复）、`RemoteControlClient` 媒体中心同步；
   - 进程退出/异常时的无缝资源释放，彻底杜绝车机通道锁死。

4. **实施里程碑与风险防范**：
   - 分阶段实施计划（阶段划分、产出物、验收指标）；
   - 关键风险评估（APK 体积膨胀控制、CPU 占用与车机发热、内存占用控制、API 18 编译兼容性）。
