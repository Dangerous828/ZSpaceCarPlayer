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

CAR_IP="10.212.252.52:5555"
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"

echo "=========================================="
echo "ZSpaceCarPlayer 车机一键编译部署与调试脚本"
echo "=========================================="

echo "[1/6] 检查/连接车机 ADB: $CAR_IP..."
adb connect "$CAR_IP" || true
adb devices

echo "[2/6] 编译最新 Debug APK..."
./gradlew assembleDebug

echo "[3/6] 检查车机 SystemUI 状态..."
if ! adb shell "ps | grep -i systemui" > /dev/null; then
    echo "警告: SystemUI 进程不存在，正在尝试拉起 SystemUI..."
    adb shell "am startservice -n com.android.systemui/.SystemUIService" || true
    adb shell "am startservice -n com.geely.systemui/.SystemUIService" || true
else
    echo "SystemUI 进程正常运行中。"
fi

echo "[4/6] 安装最新编译的 ZSpaceCarPlayer APK..."
if [ -f "$APK_PATH" ]; then
    adb install -r "$APK_PATH"
    echo "安装成功！"
else
    echo "错误: 找不到 $APK_PATH，编译可能失败"
    exit 1
fi

echo "[5/6] 启动 ZSpaceCarPlayer..."
adb shell "am start -n com.ktools.zspacecarplayer/.ui.MainActivity"

echo "[6/6] 查询车载 Launcher 与系统应用..."
echo "--- 当前 HOME 桌面系统组件 ---"
adb shell "pm list packages | grep -iE 'launcher|home|systemui'" || true

echo "=========================================="
echo "部署完成！快捷提示："
echo "1. 设置为默认车载桌面: adb shell \"am start -a android.intent.action.MAIN -c android.intent.category.HOME\""
echo "2. 日志监控: adb shell \"logcat | grep -i ZSpaceCarPlayer\""
echo "=========================================="
