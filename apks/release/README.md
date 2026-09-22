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

**发版铁律**：出包前先推进 `versionCode`；发布时把「构建时间 + 大小 + SHA-256 + `aapt2 dump badging` 的 package 行」记进本文件。判断"是否已部署"只认从设备 pull 回来的包哈希，不认版本号和 changelog。


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
