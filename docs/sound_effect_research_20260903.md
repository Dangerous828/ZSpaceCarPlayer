# 车机音效（全景/环绕/沉浸）调研与接入方案 — 2026-09-03

目标：给 ZSpaceCarPlayer（吉利 8600 车机，Android 4.3 / API 18）添加 QQ 音乐车载版同类的
"全景 / 环绕" 音效。本文档是调研结论，作为实现的依据。

## 一、同类车机 app 是怎么做的（解包证据）

| App | 包名 / 版本 | 音效引擎 | 关键证据 |
|---|---|---|---|
| QQ音乐车载版 | com.tencent.qqmusiccar / 10200113 | **自研 SuperSound3**（libSuperSound3.so + libAUDIOPROCESSOR.so） | dex 内 effect 项：银河音效、沉浸座舱音效、座舱临境人声、全景声算法版、臻品全景声(5.1/7.1.4/9.1.6声道)、5.1环绕声；**零** Virtualizer/PresetReverb 引用 |
| 酷我音乐车机版 | cn.kuwo.kwmusiccar / 6.0.0.9 (minSdk 19) | **SuperSound3（与QQ同款）+ libkwaudioeffect.so**；dex 被 360 加固（com/stub/StubApp），Java 层不可分析 | 原生库清单直接可见；libusbaudio.so 另走 USB 音频 |
| 酷狗音乐车机版 | com.kugou.android.auto / 3.0.2.4 (minSdk 16) | **自有 VST 式插件框架**（libeffect0.so / libjengine.so / **libviper4android.so**） | jadx 反编译 `com.kugou.common.player.kugouplayer.effect.*`：AudioEffect 基类 `native_setup(effectId)` 直连自家 JNI，31 种效果（VIPER3D、SurroundEffect.setSurround(level)、StageEffect 声场、EnvironmentalReverb 自定义 preset 0..5、RayTraceReverb、VinylEngine 等）；UI 文案：模拟5.1全景声场、环绕立体音、汽车音效、演唱会声场 |

**结论 1**：TME 系（QQ/酷狗/酷我）车机版音效全部是**私有原生 DSP 引擎在 app 内处理解码后的 PCM**，
不是车机 DSP、也不是标准 audiofx。"蝰蛇音效"的营销名直接来自酷狗用的 ViPER4Android 引擎。
这类引擎（含音效预设下载、会员 gating）无法复刻，我们走标准 Android 音效链。

## 二、标准 Android 音效链怎么写（API 18 全部可用）

两个官方效果正好对应"全景"和"环绕/空间感"（均 API 9+，8600 的 API 18 满足）：

1. **Virtualizer（全景/立体声展宽）** — insert 效果，**必须绑播放器的 audioSessionId**（不要挂 session 0）：
   ```java
   Virtualizer v = new Virtualizer(0, mediaPlayer.getAudioSessionId());
   if (v.getStrengthSupported()) v.setStrength((short) (percent * 10)); // 0..1000
   v.setEnabled(true);
   ```
   原理：HRTF/串音消除类算法在扬声器间模拟更宽的声场，人声保持居中。
2. **EnvironmentalReverb（空间混响）** — **aux 效果，建在全局 session 0**，靠播放器把音频"发送"给它：
   ```java
   EnvironmentalReverb r = new EnvironmentalReverb(0, 0); // session 0 全局
   r.setRoomLevel(...); r.setDecayTime(...); /* 参数见下 */ r.setEnabled(true);
   mediaPlayer.attachAuxEffect(r.getId());
   mediaPlayer.setAuxEffectSendLevel(0.4f); // API 12+，0..1
   ```
   注意 **reset/切歌后 AudioTrack 重建，attach 关系会丢，必须在每次 onPrepared 重挂**。
   （MediaPlayer.attachAuxEffect API 9+；setAuxEffectSendLevel API 12+；都 ≤18。）

参数空间（毫贝/毫秒等，全部在 API 18 校验范围内，取值保守防爆音）：
- roomLevel/-HFLevel：-10000..0 mB；decayTime：10..20000 ms；decayHFRatio：100..2000‰
- diffusion/density：0..1000‰；reverbLevel：-9000..2000 mB（湿声增益，**务必 ≤ -800mB 防炸**）

参考来源：
- [EnvironmentalReverb 官方文档（aux + setAuxEffectSendLevel 用法）](https://developer.android.com/reference/android/media/audiofx/EnvironmentalReverb)
- [Virtualizer 文档（绑 session、setStrength 0-1000）](https://learn.microsoft.com/en-us/dotnet/api/android.media.audiofx.virtualizer)
- [StackOverflow: EnvironmentalReverb/PresetReverb + MediaPlayer 实战](https://stackoverflow.com/questions/54538386/adding-enviromentalreverb-and-presetreverb-to-mediaplayer)
- [虚拟环绕声原理 (CSDN)](https://blog.csdn.net/jsgaobiao/article/details/51142256)、[AudioEffect/Equalizer Java 侧解析 (CSDN)](https://blog.csdn.net/wkw1125/article/details/64443954)

## 三、8600 车机适配要点 / 风险

- 效果 HAL 支持度未知：模拟器必支持；车机若不支持，`new Virtualizer/EnvironmentalReverb` 会抛
  （或 setParameter 返回错误码）。**全部 try-catch 降级**，与现有 Equalizer/BassBoost 同套路，
  UI 不崩、日志可见。
- EQ/BassBoost 的现有生命周期问题：session 每次 reset 变化 → 每次 prepare 重建（已有逻辑）。
  Virtualizer 同生命周期处理；**Reverb 是全局 session 0，绝不能放进 releaseAudioFx 的 per-session 重建里**，
  只在 onDestroy 释放，prepare 时重挂 aux。
- 防爆音红线不变：不碰系统音量/静音；reverbLevel 与 sendLevel 取保守值（send 0.4，wet ≤ -800mB）。
- 音效设置与现有 EQ/Bass 一致保持内存态（不持久化，后续要做再统一做）。

## 四、实现清单（本仓库）

1. `AudioPlayerService`：Virtualizer 随 session 重建（percent×10 → strength）；
   EnvironmentalReverb 全局懒加载（模式：0关/1房间/2音乐厅/3影院，参数表见代码注释）；
   `handlePrepared` 里 `applyAuxEffect(mp)` 重挂 aux + sendLevel。
2. `dialog_eq.xml` + `MainActivity.showEqDialog`：新增"全景"SeekBar(0-100%) 与"空间混响"SeekBar(0-3 档，
   关/房间/音乐厅/影院)，接 binder getter/setter；EQ 引擎缺失时不再整弹窗拒绝，只停用 Spinner。
3. 验收：`testDebugUnitTest assembleDebug` 全绿；模拟器开音效播歌 logcat 无 effect 错误、UI 正常；
   车机实听效果待部署后由用户主观验收。

## 五、开源音效补充调研 (2026-09-03 第二轮)

- **蝰蛇 ViPER4Android 没有真正开源**：官方 FX 驱动一直闭源 (XDA 原帖确认)；社区逆向版
  [WSTxda/ViperFX-RE-Releases](https://github.com/WSTxda/ViperFX-RE-Releases) 和
  [AndroidAudioMods/ViPER4Android](https://github.com/AndroidAudioMods/ViPER4Android) (Iscle 逆向驱动)
  全部走 **root + Magisk 系统驱动** 路线，8600 车机 (API 18, 不可 root 交付) 不可用。
- **JamesDSP 是真正开源的同类** ([james34602/JamesDSPManager](https://github.com/james34602/JamesDSPManager), GPL-3.0)：
  Auto Bass Boost / Progenitor2 混响 / 分区卷积器 / 立体声展宽 / Crossfeed / EEL 可编程 DSP。
  但其 Android GUI 版只支持 Android 5-10 且按 root 模块接入；核心 `JamesDSPLib` (C) 可移植，
  **用进本 app 的唯一路径是把播放器改成自解码 PCM 管线 (像 QQ/酷狗那样) 再挂自定义 DSP** ——
  播放架构重写 + 功放红线高风险，作为后续大版本选项，本版不做。
- 本版"继续完善"落在：EQ 弹窗滑条车机化重绘 (见 S2/plan)、设置弹窗防软键盘遮挡；
  并以二分法确认模拟器慢网络 -19 重试循环与音效代码无关 (docs/deploy_plan_next_20260903.md 已知事项 A)。

