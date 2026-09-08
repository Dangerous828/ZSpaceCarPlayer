# 发布与回滚包归档

> 本目录下 `*.apk` 受 `.gitignore` 排除，**只存在于构建机**（`/Users/cpuser/Code/kTool/ZSpaceCarPlayer`）。
> 本 README 记录每个包的来源与校验值，换机器后需按下述命令重建。

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
