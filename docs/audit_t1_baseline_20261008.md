# T1 基准重审（2026-10-08）

立项依据原文（三份，均为逐字引用）：

- `docs/ZSpaceCarPlayer_v3_Design_Plan.md:11`：头部车机 App 100% 采用「自建软解码管线 + 应用内私有 C++ 纯软件 DSP + 裸 PCM AudioTrack 写入」。`:20`：「软件 DSP 引擎集成（对标 QQ 音乐银河音效 / 酷狗蝰蛇）」。
- `docs/geely_dsp_reverse_analysis.md:6`：基于对 QQ 音乐车载版 APK（`10200113.apk`）的逆向，制定吉利车机 DSP 链路、AudioFocus、车载广播、生命周期与防爆音的演进计划。
- `docs/sound_effect_research_20260903.md:3-4`：目标是加"全景/环绕"同类音效；`:14-16` 结论——TME 系音效是私有原生 DSP，无法复刻，走标准音效链。

仓内未发现写有"T1"字样的立项文档；本文用「QQ 音乐 / Spotify / Apple Music / YouTube Music / Amazon Music + Media3(Spotify/Android 官方播放器内核) + mpv」作为对照面。

## 0. 本次实测（本文所有速率数字的口径）

同一天、同一台 Mac、同一条手机热点、同一首歌（Booty Music，原件 18.9MB）：

| 取法 | 结果 |
|---|---|
| 一条连接 `Range: 0-5242879` | 797 KB/s（首字节 1.44s，传体 3.46s） |
| 同样 5MB，20 段各 256KB，**复用同一条 keep-alive 连接** | 133 KB/s |
| 同样 5MB，20 段各 256KB，每次新建连接 | 91~133 KB/s |
| 每次分段请求的首字节耗时 | 0.75 ~ 1.44 s（与是否复用连接无关） |

两条自纠：绝对速率在这条链路上波动 3 倍以上（同日先测 246 后测 797），所以**只能引用比值**（分段 ≈ 长连接的 1/6，两次都复现）；上报里的 `api_key` 是 `[REDACTED]`，照它拼 URL 全部 0 字节，任何"我实测到 X"若没先证明探针拿到了字节，都作废。

## 1. 差距分层（按严重度）

### L1 取流架构：方向性偏离，数量级差距

对照面（一手）：Media3 `DefaultLoadControl` 默认 min/max buffer **50000ms**、起播水位 **1000ms**、rebuffer 后 **2000ms**；mpv `--cache=auto` + `--demuxer-readahead-secs`；ExoPlayer/mpv/VLC 的缓冲模型统一是**单条顺序读流 + 水位控制，Range 只在 seek 时发**。一手文档里找不到"把一首歌拆成多条短连接分段取"的任何做法。

我们现在（代码位置）：

| 事实 | 位置 |
|---|---|
| 代理对客户端恒发 `Connection: close`，一请求一 socket | `HttpProxyServer.java:485` |
| 读者全部摘窗 ⇒ 下载线程 `return` 并 `disconnect()`（真断 TCP） | `BufferedHttpSource.java:161-163, 1001-1003, 756-771` |
| 重定位把整窗清零（位置之后已下字节一起作废） | `BufferedHttpSource.java:807-833` |
| 无 seektable 的 FLAC 起播需二分中点跳读 ~9 次 = 9 次重定位 | `NativeLossless.cpp:85-88, 181-184` |
| 没有任何"每连接耗时 / 首字节时间"计时；`upstreamConnections` 不计失败重连 | `BufferedHttpSource.java:952-955` |

后果：每首歌起播白烧约 9 秒（9 × TTFB）；播放中被切成几十条短连接，把 760KB/s 的链路用成 130KB/s。**今天所有"链路不够"的结论都建立在这个被污染的读数上。**

### L2 音质档位与带宽策略：我们既没有档位，也没把选择权交给用户

对照面：YouTube Music **Wi-Fi 与蜂窝两套独立档位**（48/128/256kbps AAC&OPUS）+ 下载档；Spotify 24/96/160/320；Amazon 直接公布带宽预算 HD 1.5–2Mbps、Ultra HD 5–10Mbps；Apple 串流与下载分别选档，并说明"串流音质取决于歌曲可用性、网络条件与设备能力"。

我们现在：单一取流形态（原件直传 / 服务端 FLAC / 128k mp3 三档 URL 存在但只有第一、二档按入库规则自动选，`JellyfinApiClient.java:620-694`），**没有用户可选档位、没有分网默认、没有"这首歌需要 X KB/s"的明示**。曾按固定阈值自动降档（vc16）→ 误判；再开（vc19）→ 崩溃环。**车主 2026-10-08 明确否决自动降档**。收口方式不是"留一个关着的开关"，而是**把档位状态从治理器里整个删掉**：
`StreamRateGovernor` 现在只累计 `bitrate deficit` 证据，档位由 `StreamTier` + SharedPreferences 承载、只在设置页手选。这是决定，不是欠账。对照 T1 的做法，正确形态是"档位贴住链路 + 由用户选"，而不是"自动换档弹提示"。

### L3 磁盘缓存与离线：完全没有

对照面：Media3 把「边播边存」（`CacheDataSource` + `SimpleCache` + LRU 淘汰）与「离线下载」（`NoOpCacheEvictor` + `DownloadManager`，播放时只读不写）分成两套；弱网先吃已缓存字节。

我们现在：环形窗是纯内存，过路字节不留盘；`CacheSizeManager` 只裁剪 cacheDir。**回拖、重播同一首、弱网兜底全都得重新走网络。**

### L4 播放体验功能缺口

T1 一手确认有、我们没有：gapless 无缝播放（Spotify 独立开关、mpv `--gapless-audio`）、crossfade 交叉淡化（Spotify Premium 滑条；Apple 在 Hi-Res Lossless 下禁用它并写进帮助文档）、响度归一化（Spotify Loud/Normal/Quiet、Apple Sound Check）、EQ 自定义曲线可保存（Spotify 可拖频点）、播放队列可视化编辑（Spotify Play Queue）。

我们仓内盘点确认「未发现实现」共 **31 项**，其中属于基础盘的：封面/专辑图（Glide 只 import 未用、`cover_url` 是死列）、歌单增删改、艺术家/专辑维度浏览、睡眠定时、耳机拔出/蓝牙断开暂停、变速、跳过静音、24-bit 链路（全链路仅 16-bit）、上一首历史栈（随机模式下"上一首"是重随）、服务端搜索（只有本地拼音搜索）、多服务器配置、ALAC/OGG/M4A/DSD。

已有的可对标项：自研 C++ DSP（5 段参量 EQ + 17 预设 + 低音/环绕/混响 + 软限幅，两条播放路径都过 DSP）、歌词（服务端 `/Lyrics` + SQLite 缓存 + 滚动同步 + 偏移/字号）、断点续播（含脏断点清洗链）、OTA、崩溃取证。

### L5 车机标准接入：整块空白

Android Auto/AAOS 对媒体应用的硬性要求是 `MediaSession` + `MediaBrowserService/MediaLibraryService` + 语音动作 + 分心防护，且失败必须置 `PlaybackStateCompat.STATE_ERROR` 并给**面向用户的本地化文案**（`developer.android.com/training/cars/media`、`/errors`）。我们走的是自绘 UI + `RemoteControlClient`（Android 4.3 无 MediaSession，`AudioPlayerService.java:432` 有注释确认），错误呈现是 Toast + 自造文案。在 4.3 上这条只能"部分满足"，但**错误可读性**这一半是我们自己能做的。

### L6 曲库边界：215 首根本不是音频

全库 1032 首（可算的）按 `Size/时长` 分布：`<80KB/s` 192、`80-120` 55、`120-200` 530、`200-320` 40、**`>=320` 215**。那 215 首全是 `mp4`，是「4k60帧汤姆猫」这类**4K 视频混在音频库**里，单曲需 3.6MB/s——取流修好也永远播不动。T1 的做法是按码率分档并明示"该档不可用"；我们连判断都没有。

## 2. 整改分级

**已在本批落地（2026-10-08，`vc21=3.2.10`，包已出、未发布）**

- A1-1 越界 seek 判不可达：`HttpSourceReader.seek` 用 `contentLength` 校验目标，把 dr_flac
  自己的"到流尾了"判据还给它（`dr_flac.h:5888`）。总长未知（chunked）时保持放行，行为不变。
- A1-2 前向小缺口不再清窗重连（`readPlacementAction`，阈值 256KB）；摘窗宽限期 8 秒内继续填窗
  （`shouldKeepFillingWhileIdle`，并要求 `refCount>0`，切走的旧源立刻收手不抢新歌带宽）。
  `pumpIntoRing` 与 `writeToRing` 用同一条判定——否则一边放行一边判死，宽限是空话。
- A2 磁盘缓存 `StreamDiskCache`：键=`itemId+总长`（**绝不含 api_key**，token 会轮换）、按连续段
  区间表记账（跨洞一律不读）、chunked 流不缓存、LRU 按 mtime 且只删自己前缀的文件、
  上限 1GB 仍在 `getCacheDir()` 下沿用 `CacheSizeManager`。读侧在"窗外重定位"之前先查盘命中。
- A3 档位体系：设置页「音质」行两档循环（无损 / 流畅），SharedPreferences 持久化，只影响下一首，
  **没有任何自动切换路径**；放弃当前曲时若处于无损档，只在文案里给一句"可切流畅"的出口。
- 仪表：连接数改为"每次尝试都计"（旧口径只计成功建连，等于漏掉重连风暴）+ 每请求首字节耗时
  （最近/最大/平均）+ 盘命中字节，全进面包屑与 ctx 字段。

**故意留在本批之外（有据可查，不是遗漏）**

- A1 后续 B/C（夹紧 `dr_flac` 的 `byteRangeHi`、注入合成 seektable）：要改 vendored 文件或 C++
  内存布局，且**C++ 侧没有任何自动化覆盖**（`changelogs/3.0.0.md:41-42` 已记"只能真车 seek 验"），
  夹紧错误会退化成"顺序扫完整首"比现状更糟。等车上 `upstream conns`/`ttfbMax` 复测再决定。
- 代理 keep-alive：**已判定不做**——v3 原生路径由 `DspAudioTrackPlayer:241-242` 直接
  `acquireSource()` 交给 JNI 读，不经过本地代理 socket；`HttpProxyServer:485` 的恒
  `Connection: close` 只作用于 MediaCodec 回退分支，改了也不会改善主路径吞吐。
- L4 那 31 项功能补齐（gapless/封面/歌单/睡眠定时…）：独立一轮，不混进架构改造。

### 2.1 补测：这台 Jellyfin 忽略码率参数（决定了档位怎么标）

同一首歌（181.812s）实测七种写法，返回字节数**完全相同**：

| 请求参数 | 结果 |
|---|---|
| `maxStreamingBitrate=128000` / `320000` | 5,819,950B = **256.1kbps** |
| `audioBitRate=128000` / `320000` | 5,819,950B = 256.1kbps |
| `MaxStreamingBitrate`（大写） | 5,819,950B = 256.1kbps |
| `+enableTranscoding=true` | 5,819,950B = 256.1kbps |
| `+audioCodec+audioBitRate+audioSampleRate` | 5,819,950B = 256.1kbps |
| `static=true`（原件） | 18,869,283B = 830.3kbps（需 101.4KB/s） |

即这台服务的 mp3 转码固定 256kbps，**码率参数一概不认**。所以：流畅档的真实代价是
31.3KB/s（仍只有无损的约 1/3），仓里 `DEGRADED_TARGET_BITRATE=128000` 只是请求参数与 URL 标识；
**任何用户可见文案都必须写 256kbps，写 128 就是假广告**——原先那条按"128k=16KB/s"断言的单测
也因此是错的，已改成按实测常量断言。


**架构级（要重做，补丁解决不了）**

- **A1 取流改回单条顺序读 + 水位控制**：去掉代理侧恒 `Connection: close`；读者摘窗不再 `disconnect`（挂起持有连接）；重定位保住窗内有效字节；FLAC 无 seektable 时改为一次读尾部取表。目标是把"每请求 1 秒"的代价从每首歌几十次降到 1~2 次。
- **A2 磁盘缓存**：边播边存 + 弱网先吃本地字节；离线下载作为第二阶段。
- **A3 档位体系**：用户可选（无损/超高/高/流畅）+ Wi-Fi/蜂窝分别默认 + 起播前用入库码率明示"本曲需 X KB/s"。

**已在本批（vc21=3.2.10，架构外）**

- 断路器：一个字节没出声就 EOS 不再被误判为坏断点（不清续播点、不从零重播）。
- chunked 流不再按字节重连（省掉约 85 秒白重下）。
- 预取两修：chunked 上三道门全关（用入库元数据兜底剩余秒数）；`percent>=40` 会在 lead=0 时抢当前曲带宽（健康只认领先秒数）。
- 仪表：`upstream conns` / `discarded` / `socket` 进上报与面包屑。

**可独立小做**：曲库排除非音频容器；错误文案成句；睡眠定时；封面；EQ 自定义保存；上一首历史栈。

## 3. 归属与已知决定（不留无主待办）

| 项 | 状态 | 归属 |
|---|---|---|
| 自动降档 | **车主 2026-10-08 否决**；治理器的档位状态已整个删除（不是留个 false 开关） | 决定，不重开 |
| 退避期间 UI 状态变化 | 车主拍板不做（`docs/stream_retry_backoff_spec_20261005.md:92-94`） | 决定，不重开 |
| `prefill` 768KB 与 `lead=-1` | 论证后有意不改（`changelogs/3.2.4.md:10-14`） | 决定 |
| MediaSession / 标准车机接入 | Android 4.3 无该 API，**客观不可能项**，仅错误文案可改 | 限制 |
| A1 取流回单连接 | **已做**（commit `05e54ec` + `17d6f32` + `5fd815d`），四把回退闸在 `StreamTuning` | 效果待真车 `upstream conns`/`ttfbMax` 复测 |
| A2 磁盘缓存 | **已做** `StreamDiskCache`（单文件 100MB / 目录 1GB 两个口径，占用与清空已进设置页）；区间表已改为**必须持久化的 sidecar 账本**——首版用文件大小反推连续性会把跳写留下的零填充洞当音频读，2026-10-08 自查抓到并修（changelog 3.2.10 第四批 14/15） | 命中率待真车 `disk=` 复测 |
| A3 档位体系 | **已做** `StreamTier` + 设置页「音质」两档手选 | 服务端固定 256kbps，更多档位需先改 Jellyfin 转码配置 |
| L4 功能差距 | **逐项判定见 §4**：本次补 5 项（历史栈/睡眠定时/拔耳机/回退闸/缓存管理），其余判为不适用、缺依据或需你定交互 | 见 §4 各行归属 |
| FLAC 二分 B/C（vendored/合成表） | 未做，**故意**：无 C++ 自动化覆盖且退化风险更高 | 需真车数据后由车主拍板 |
| 离线下载 / 歌单增删改 / 队列编辑 | 未做：曲库列表与播放页无任何长按菜单可挂，入口与存储位置未定 | 需车主先定交互，属新特性非欠账 |
| 响度归一化 | 未做：**实测服务端 1051 条无 `Normalization`/`Loudness`** | 前置：先有 RG 元数据 |
| 435 条 wav 是否继续转 flac | 未决定（当时口径是"等听完再定"） | 车主 |
| QQ 音乐音效引擎（SuperSound/蝰蛇） | 一手结论：私有引擎无法复刻（`docs/sound_effect_research_20260903.md:14-16`） | 限制，非欠账 |

## 4. L4 功能差距逐项判定（2026-10-08 收尾，不留"含糊未完成"）

**本次已实现（同批 commit，均有单测）**

| 项 | 落点 |
|---|---|
| 随机模式"上一首"是重新随机（真缺陷） | `service/ShuffleHistory.java` + `AudioPlayerService.playPrevious/playNext` |
| 睡眠定时（到点立即暂停） | `service/SleepTimer.java` + 设置页「睡眠定时」行 |
| 拔出耳机/断开外放不暂停 | `AudioManager.ACTION_AUDIO_BECOMING_NOISY` 运行时接收器 |
| 回退闸：地基改动必须能在车上关 | `player/stream/StreamTuning.java` + 设置页「取流优化」「播放缓存」 |
| 缓存占用可见 + 一键清空 | `StreamDiskCache.dirBytes/clearDir` + 设置页 |
| 预取源的连接/字节代价不可见（自认盲区） | `AudioPlayerService.prefetchedNextUrl` + `ctx_prefetchConns`/`pfConns` |
| 错误文案漏 Java 异常原文（L5 可做的一半） | `AudioPlayerService` 起播失败分支改成人话；`STATE_ERROR` 那一半 4.3 无 API |
| 曲库混入视频（236 条，判据 `MediaStreams[].Type=="Video"`） | `JellyfinApiClient.hasVideoStream` 过滤 + 刷新后明示条数 |

**判定为"不适用"，附依据（不是遗漏）**

| 项 | 为什么不做 |
|---|---|
| 封面/专辑图 | 车主 3.2.0 主动删封面（`changelogs/3.2.0.md`），不是欠账 |
| 服务端搜索 | 本仓是全量镜像 + 本地 SQLite 拼音搜索（`SongDao`、`PinyinUtils`），库就 1051 条；服务端搜索解决的是"目录巨大不便全量拉"的问题，我们没有那个问题 |
| 多服务器配置 | 只有一个家里 NAS，无第二实例需求 |
| 逐字/卡拉OK 歌词 | Jellyfin 只提供行级歌词（`/Lyrics`），无逐字时间戳数据源 |
| 私有音效引擎（SuperSound/蝰蛇） | `docs/sound_effect_research_20260903.md:14-16` 一手结论：无法复刻，已用自研 DSP 路线 |
| MediaSession/AAOS 标准接入 | Android 4.3 无该 API（`AudioPlayerService:432` 注释确认），客观不可能项 |
| 退避期间 UI 状态 | 车主拍板不做（`docs/stream_retry_backoff_spec_20261005.md:92-94`） |

**判定为"缺依据，不做猜测式实现"**

| 项 | 缺什么 |
|---|---|
| 响度归一化 (ReplayGain) | 2026-10-08 实测这台 Jellyfin 的 1051 条**完全没有** `Normalization` / `Loudness` 字段（MediaSources 与 Audio 流两层都空）。没有可信增益值，凭猜做音量拉平会把歌做坏。前置条件：服务端或转换管线里先算出 RG 并落到元数据 |

**需要产品决策后才做（新特性，非本次"改坏/没改完"）**

| 项 | 要先定什么 |
|---|---|
| 离线下载 / 边播边存的"下载整首" | 曲库列表与播放页**都没有长按菜单**（全仓无 `OnLongClick`/`AlertDialog` 挂载点），要先定：入口在哪、下载到哪（`filesDir` 还是 cache）、UI 如何显示进度与已下载标记、如何管理/取消 |
| 歌单增删改 | 现在的"歌单"是库内派生视图（类别/红心/最多播放），要改成 Jellyfin `Playlists` 实体是数据模型变更 |
| 播放队列可视化编辑（拖拽/移除） | 涉及交互设计，车机 1920x720 上拖拽需另行验证 |

**只能真车验证（本批已实现但效果未证）**

- A1/A2 的全部效果：`upstream conns`、`ttfbMax`、`disk` 三个数必须来自车上一次真实上报。
- FLAC 无 seektable 的二分次数是否从 ~9 降到 1~2（决定是否动 vendored `dr_flac`，任务 33）。
- vc19 的"闪退"是否被断路器止住。
- 设置页新增三行（音质 / 取流优化+播放缓存 / 睡眠定时）的显示与点击：本次无设备接入，只验证了编译、`R.id` 生成与文案进包。
