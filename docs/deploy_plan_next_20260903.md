# 下次部署预案 — 2026-09-03（含本版全部变更与验收清单）

本版（自 v2.2 后累积、未部署的变更）：语音/焦点恢复无声修复、开机自启兜底、sw720dp 字号放大 1.5x、
进度条填充对齐、**元数据乱码修复 (TextRepair)**、**EQ 音效新增全景/空间混响**、EQ 弹窗滑条车机化重绘、
设置弹窗防软键盘遮挡。单测 60 全绿。

## 一、前置条件
1. 车机开机且与本机同网：`adb connect 10.212.252.52:5555`（IP 会漂移；不通先在车机上看设置页 IP）。
   共用设备：确认不影响他人使用再操作。
2. 构建：`./gradlew testDebugUnitTest assembleDebug`（必须全绿再部署）。
3. 本地基线已在模拟器 Car_1920x720(160dpi) 复核：字号/进度条/EQ 弹窗/设置弹窗均正常。

## 二、部署步骤
1. `./deploy_to_car.sh`（自动连 ADB → assembleDebug → install -r → 拉起）。
2. **回滚**：脚本目前不留旧版备份。部署前先手动存一份当前车机版本：
   `adb pull /data/app/com.ktools.zspacecarplayer-*/base.apk ~/Backup/zcp_prev.apk`（路径以 `pm path` 输出为准）；
   回滚 = `adb install -r ~/Backup/zcp_prev.apk`。

## 三、验收清单（按序执行，全部日志留档）
| # | 项目 | 操作 | 通过标准 |
|---|---|---|---|
| 1 | 开机自启 | 重启车机 | 不动手 app 自动拉起服务 (BootReceiver 三重兜底) |
| 2 | 字号 | 目视主界面/弹窗 | sw720dp 1.5x, 无小字 |
| 3 | 进度条 | 播放中观察 | 填充条终点始终对齐滑块中心 |
| 4 | 乱码自愈 | 点一次"刷新媒体库"，重进列表 | HISTORY（中文版）/飘雪/谭咏麟 等正常；拼音搜索同步修复。**含 ? 替换符的 ~200 行仍乱属预期** (字节已丢, 需服务端重写 ID3) |
| 5 | 音效 | EQ 音效 → 全景 40-60% + 混响"房间"起步实听 | 无爆音；车机 HAL 不支持则静默降级 |
| 6 | 语音/焦点 | "你好吉利" ≥4s 打断后回到 app | 自动恢复有声 (seek 重建 + 功放限频重试) |
| 7 | 功放红线 | 全程 `adb shell media volume --get` / logcat | 系统音量永远不被应用修改/抬升 |
| 8 | 慢网络观察 | 弱网场景听歌 | 见"已知事项 A" |

日志抓取：`adb shell "logcat | grep -iE 'ZSpaceCarPlayer|Virtualizer|Reverb|audiofx'"`。

## 四、已知事项（部署前知情）
- **A. 慢网络 -19 循环（模拟器复现，与音效无关）**：冷启动+服务器连接慢时，自动续播在
  缓冲中 start/seek 撞出 `MediaPlayer error (1, -19)`，以 ~10-15s 周期重试并弹"播放出错(Code 1)"，
  网络转好后恢复。**已用二分开关证明与音效代码无关**（旁路全部音效代码后 -19 照旧复现）。
  车机局域网直连 NAS (192.168.31.152:8080) 速度快，预计不触发；若车机弱网出现，后续给
  错误重试加缓冲退避（涉及 service/ 重试路径，单独做）。
- **B. EQ/音效设置为内存态**：进程被杀回 0。与既有 EQ/重低音行为一致，持久化单独排期。
- **C. 音效效果幅度**：标准 audiofx 比 QQ 音乐 SuperSound3 克制属预期；若车机 logcat 出现
  "Virtualizer init failed"/"EnvironmentalReverb init failed" 说明车机 HAL 无此效果，功能自动消失不崩。
- **D. 开源音效路线（本版不做）**：蝰蛇 V4A 驱动闭源，社区 RE 版全部要 root+Magisk；JamesDSP
  (GPL-3.0) 核心库可移植但需把播放器改成自解码 PCM 管线——大版本再议，详见
  docs/sound_effect_research_20260903.md。
