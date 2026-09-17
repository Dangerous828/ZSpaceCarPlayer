# 实车开发记录 2026-09-13 ~ 09-17

本轮覆盖：双进度条缓冲显示、播放跟随歌单、手动诊断日志双入口上报闭环、Android 4.3 TLS 握手修复、OTA 远程升级全链路实车验收。代码基线：单元测试 318/318 全绿，APK versionCode 5 (v3.1.1)。
## 功能1 双进度条缓冲显示（2026-09-13）—— 实车通过

YouTube/视频加载式双层进度条：深色轨道上先长出**浅色半透明缓冲段**（`seek_buffer_bg.xml`，#66F1F6FB），青绿播放进度（`seekFill`）压在其上，视觉上只露出「缓冲超前播放头」的浅色尾巴。

### 实现要点
- 布局：`activity_main.xml` 进度条 FrameLayout 内、轨道与 `seekFill` 之间插 `seekBufferFill` View。
- UI（MainActivity）：
  - `onBufferingUpdate(percent, buffering)` 里调 `updateSeekBufferFill(percent)`，percent<0（总长未知）归零；
  - 宽度计算与 `updateSeekFill` 共用 `seekFillWidthFor(frac)`（终点对齐滑块中心的同一套数学）；
  - 缓冲条**不落后于播放头**（字节↔时间比例尺微小偏差兜底）；切歌同帧归零（`updateSeekBufferFill(-1)`）；
  - `applyFillWidth` 宽度确有变化才 setLayoutParams，稳定期不无谓重排。
- 服务端（AudioPlayerService `maybeReportBuffering`）：稳定播放后**仍每 500ms tick 持续上报 percent**（原来稳定后只报一次就停手）——缓冲层要像视频一样持续长条。文字指示靠 visibility 幂等隐藏不会闪烁。原 `bufferingReportedStable` flag 死亡清除。
- 诊断日志：`buffering report: percent=X buffering=B lead=Ns remaining=Ns`，percent 或状态变化时打一行。

### 实车证据（2026-09-14 08:48，「一生何求」）
截图 `v1.png`：滑块 02:58 处，右侧浅灰缓冲段明显延伸，文字「缓冲 68%」。日志 percent 61→100 单调推进与 UI 一致。

## 功能2 播放跟随歌单（2026-09-14）—— 实车通过（有一次异常未再现）

需求：播放时左侧不再显示全部歌曲 887 首大列表，而是显示**当前歌所在的歌单**并定位。

### 实现要点
- MainActivity 新增 `playbackSourceTitle/playbackSourceSongs`（播放队列来源），**所有 `setPlaylist` 挂载点在调用前记录**（`notePlaybackSource`）——setPlaylist 内部会**同步**回调 `onSongChanged`，记晚了跟随逻辑读到的是上一轮旧值（5 个挂载点：点歌/播放键兜底/自动续播/全库 fallback/mount-only）。
- `onSongChanged` 里先 `followPlayingSourceList()` 再 `syncPlayingHighlight(true)`：
  1. 队列来自具体歌单（红心/最多播放/文件夹/流派）→ 切回该歌单；
  2. 来自全部歌曲/搜索结果/未知 → 从当前曲反推所属歌单（`inferPlaylistTitleFor`：文件夹优先、流派兜底，与 buildCategories 归类规则一致），反推失败（元数据缺失）不盲目切；
  3. 搜索进行中（etSearch 可见且非空）/ 设置页打开 → 不抢焦点。
- 服务器刷新重建 allSongsList 后，同分类的 `playbackSourceSongs` 引用同步换新（fetchLibrary onSuccess）。
- **列表滚动定位修复**：`syncPlayingHighlight` 的 `smoothScrollToPosition` 改为 `rvSongList.post(...)` 下一帧执行——冷启动链路里 rvSongList 刚从 GONE 转 VISIBLE、尚未布局，同帧直接滚会丢失（2026-09-14 实车：列表停在顶部没定位到「一生何求」，修复后高亮居中）。
- 诊断日志：`follow:` 前缀，每个出口一行（skip 原因 / 已在目标列表 / 切换目标）。

### 实车证据
- 08:51（v2.png）：自动续播「一生何求」→ 左侧切到「粤语金曲」100 首，高亮行居中、上下是「一生不变」「一生中最爱」。
- 08:59（日志）：
  ```
  follow: song=一直很安静 folder='8090网络歌手歌曲合集' ... srcTitle='全部歌曲'
  follow: switch to inferred '📁 8090网络歌手歌曲合集' (187 songs)
  ...
  follow: song=一直很安静 folder='影视金曲' ...
  follow: switch to inferred '📁 影视金曲' (50 songs)
  ```
  ACTION_NEXT 切歌跟随正常。注意库里有**多首同名「一直很安静」**（folder 不同：8090合集/影视金曲），队列是全库时按 id 匹配的是播放器实例那份，folder 以服务端为准，反推以日志实测为准。

## 功能3 双入口现场诊断日志上报（2026-09-16 ~ 09-17）—— 实车通过

1. **播控栏快捷上报**：正在播放操作栏最左侧新增「上报」按钮，播放异常/卡顿时代入 `playback_bar` 来源一键异步打包上报；
2. **设置页手动上报**：关于与升级卡片内提供「立即上报诊断日志」，代入 `settings_page` 来源；
3. **日志持久化与降噪**：
   - `DiagLog.java` 滚动持久化两份 96KB 日志（`diag.log` / `diag.log.1`），断电不丢；
   - `CrashMonitor.readLogcat` 实施白名单过滤，剔除车机系统及高德导航（NaviServer）噪音，仅留播放核心栈；
4. **实车上报确认**：2026-09-17 08:41/08:42 播控栏与设置页各触发一次，中枢 NAS `/opt/data/Jarvis/data/crash-reports/crash-20260917.jsonl` 成功接收两条 103KB 完整记录。

## 功能4 OTA 远程升级实车闭环（2026-09-17）—— 实车通过

1. **TLS 握手修复 (`TlsCompat`)**：Android 4.3 Conscrypt 仅支持 ECDSA CBC，OkHttp 默认过滤导致无可用套件；通过 `ConnectionSpec.allEnabledCipherSuites()` 解除二次过滤，恢复与 Cloudflare ECDSA 握手；
2. **跨 UID 安装权限修复 (`makeReadableForInstaller`)**：下载至 `cache/updates/` 的 APK 默认 600/700 权限，导致系统安装器 UID 报 `EACCES`、界面报「解析程序包时出现问题」；改动后设置全局只读 `644/755`，系统安装界面正常唤起；
3. **实测路径**：降级到 `v3.1.0 (vc 4)` → 检查更新命中 `v3.1.1 (vc 5)` → 弹窗确认 → 下载 3.1MB 校验 SHA-256 → 唤起系统安装器覆盖升级成功。

## 遗留问题

### P1 缓冲卡死但左上角显示「已连接」（2026-09-14 09:00 前后）
现场已通过 `DiagLog` 滚动持久化与播控栏一键上报兜底，后续复现可直接由车主点击上报抓取。

## 部署信息

- 车机：`10.202.110.52:5555`（GEELY 06.03.08600.H53）
- 部署：`CAR_IP=10.202.110.52:5555 ./deploy_to_car.sh`
## 改动文件清单

- `app/src/main/res/drawable/seek_buffer_bg.xml`（新增）
- `app/src/main/res/layout/activity_main.xml`（seekBufferFill 层）
- `app/src/main/java/com/ktools/zspacecarplayer/ui/MainActivity.java`（缓冲条 + 播放跟随 + 定位 post 修复 + 诊断日志）
- `app/src/main/java/com/ktools/zspacecarplayer/service/AudioPlayerService.java`（稳定后持续上报 percent + 诊断日志 + 清理 bufferingReportedStable）
