#!/usr/bin/env bash

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# 环境自检: Gradle 8.9 需要 JDK 17~22 (本机默认 Java 25 会直接崩), ANDROID_HOME 也要就位
if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE '"(17|18|19|20|21|22)\.'; then
    for cand in /opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home \
                "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
                "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if [ -x "$cand/bin/java" ] && "$cand/bin/java" -version 2>&1 | grep -qE '"(17|18|19|20|21|22)\.'; then
            export JAVA_HOME="$cand"
            break
        fi
    done
fi
if [ -z "$ANDROID_HOME" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi

# 车机 IP 不固定 (DHCP/多网段, 2026-09-08 实测从 10.212.252.52 漂到 10.202.110.52):
# 环境变量 CAR_IP=host:port 优先; 否则按已知候选依次探测 5555 端口, 全部不通则报错退出
if [ -z "$CAR_IP" ]; then
    for cand in 10.202.110.52 10.212.252.52; do
        if nc -z -G 1 "$cand" 5555 >/dev/null 2>&1; then
            CAR_IP="$cand:5555"
            break
        fi
    done
fi
if [ -z "$CAR_IP" ]; then
    echo "错误: 未发现车机 (5555 端口), 请确认车机网络后用 CAR_IP=host:port ./deploy_to_car.sh 指定"
    exit 1
fi
export ANDROID_SERIAL="$CAR_IP"
echo "目标车机: $CAR_IP"
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "=========================================="
echo "ZSpaceCarPlayer 车机一键编译部署与调试脚本"
echo "=========================================="

echo "[1/7] 检查/连接车机 ADB: $CAR_IP..."
adb connect "$CAR_IP" || true
adb devices

echo "[2/7] 编译最新 Debug APK..."
./gradlew assembleDebug

echo "[3/7] 检查车机 SystemUI 状态..."
if ! adb shell "ps | grep -i systemui" > /dev/null; then
    echo "警告: SystemUI 进程不存在，正在尝试拉起 SystemUI..."
    adb shell "am startservice -n com.android.systemui/.SystemUIService" || true
    adb shell "am startservice -n com.geely.systemui/.SystemUIService" || true
else
    echo "SystemUI 进程正常运行中。"
fi

echo "[4/7] 安全停止旧实例并释放车机 DSP / 音频硬件通道 (防通道锁死)..."
# 1. 主动通知 AudioPlayerService 走优雅退出链路: 软静音 -> 放弃音频焦点 -> 释放 Session 0 混响 -> 释放 Virtualizer/EQ -> release MediaPlayer
adb shell "am startservice --user 0 -a com.ktools.zspacecarplayer.ACTION_STOP_AND_RELEASE com.ktools.zspacecarplayer/.service.AudioPlayerService" || true
sleep 1
# 2. 确保 mediaserver 与 AudioFlinger 完成通道注销与回收后再 force-stop 进程
adb shell "am force-stop com.ktools.zspacecarplayer" 2>/dev/null || true
sleep 0.5

echo "[5/7] 安装最新编译的 ZSpaceCarPlayer APK (通道已安全释放)..."
if [ -f "$APK_PATH" ]; then
    adb install -r "$APK_PATH"
    echo "安装成功！"
else
    echo "错误: 找不到 $APK_PATH，编译可能失败"
    exit 1
fi

echo "[6/7] 启动 ZSpaceCarPlayer..."
adb shell "am start -n com.ktools.zspacecarplayer/.ui.MainActivity"

echo "[7/7] 查询车载 Launcher 与系统应用..."
echo "--- 当前 HOME 桌面系统组件 ---"
adb shell "pm list packages | grep -iE 'launcher|home|systemui'" || true

echo "=========================================="
echo "部署完成！快捷提示："
echo "1. 本应用为常规自启应用 (非 HOME 桌面接管), 开机由 BootReceiver 自动拉起前台播放界面"
echo "2. 日志监控: adb shell \"logcat | grep -i ZSpaceCarPlayer\""
echo "=========================================="
