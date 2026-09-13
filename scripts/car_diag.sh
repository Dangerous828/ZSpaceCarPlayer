#!/bin/bash
# scripts/car_diag.sh — 车机取证脚本 (断点体检 / 流式抓日志)
#
# 背景: 吉利 8600 车机的 logcat 环形缓冲只有几 MB, 还被 RPC-Server / TBox / GPS 的
# 高频日志刷爆, 应用日志几秒就被冲掉 —— logcat -d 回看基本抓不到东西。必须流式拉。
#
# 用法:
#   ./scripts/car_diag.sh progress [topN]   拉 song_progress 表做断点体检 (默认前 25 条)
#   ./scripts/car_diag.sh log [秒]          流式抓日志到本地 (默认 180 秒, 期间去复现问题)
#   CAR_IP=10.212.252.52:5555 ./scripts/car_diag.sh log 300    手动指定车机
#
# 典型流程 (排查「切到这首歌直接到歌曲尾部」):
#   1) ./scripts/car_diag.sh progress      看这首歌的断点是不是贴在曲尾
#   2) ./scripts/car_diag.sh log 180       开抓, 然后上车点那首歌
#   3) 看输出文件, 搜 "song clicked" / "resume point" / "realDuration"

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="com.ktools.zspacecarplayer"
DB="zspace_car_player.db"
OUT_DIR="${HOME}/zcp_diag"
mkdir -p "$OUT_DIR"

# 与 deploy_to_car.sh 一致: 不用 nc 探测, 直接 adb connect 握手 (macOS nc 会误报)
if [ -z "${CAR_IP:-}" ]; then
    for cand in 10.202.110.52 10.212.252.52; do
        if adb connect "$cand:5555" 2>&1 | grep -q "connected to"; then
            CAR_IP="$cand:5555"
            break
        fi
    done
fi
if [ -z "${CAR_IP:-}" ]; then
    echo "错误: 未发现车机 (5555 端口)。请确认车机已上电联网, 或用 CAR_IP=host:port 指定"
    exit 1
fi
if ! adb -s "$CAR_IP" get-state 2>/dev/null | grep -q "device"; then
    echo "错误: $CAR_IP 不是 device 状态 (可能 offline/未授权)"
    exit 1
fi
echo "目标车机: $CAR_IP"

APP_TAGS="AudioPlayerService|PlaybackStateMachine|MainActivity|DspAudioTrackPlayer|NativeLosslessDecoder|AndroidMediaPlayer|BufferedHttpSource|HttpProxyServer|CarRemoteControlClient|NativeDsp|SongDao|CrashMonitor"

cmd_progress() {
    local topn="${1:-25}"
    local stamp
    stamp="$(date +%Y%m%d_%H%M%S)"
    local localdb="$OUT_DIR/${DB%.db}_$stamp.db"

    echo "[1/2] 拉取车机数据库 -> $localdb"
    adb -s "$CAR_IP" exec-out run-as "$PKG" cat "databases/$DB" > "$localdb" 2>/dev/null
    if [ ! -s "$localdb" ]; then
        echo "      run-as 拉取失败, 尝试 root 直读..."
        adb -s "$CAR_IP" exec-out cat "/data/data/$PKG/databases/$DB" > "$localdb" 2>/dev/null
    fi
    if [ ! -s "$localdb" ]; then
        echo "错误: 数据库拉取失败 (需要 debuggable 包或 root)。可改用 adb shell sqlite3 方案。"
        rm -f "$localdb"
        exit 1
    fi
    echo "      $(du -h "$localdb" | cut -f1)"

    echo "[2/2] 断点体检 (progress 贴曲尾 = 点歌即瞬跳末尾的元凶)"
    echo "--------------------------------------------------------------"
    sqlite3 "$localdb" <<SQL
.mode column
.headers on
SELECT
    substr(COALESCE(s.name,'<未知曲目>'),1,28) AS 曲目,
    COALESCE(s.duration_ms,0)                   AS 时长ms,
    p.progress_ms                               AS 断点ms,
    CASE WHEN COALESCE(s.duration_ms,0) > 0
         THEN CAST(ROUND(p.progress_ms * 100.0 / s.duration_ms, 1) AS TEXT) || '%'
         ELSE '-' END                           AS 位置,
    CASE WHEN COALESCE(s.duration_ms,0) <= 0 THEN '元数据缺失'
         WHEN p.progress_ms > s.duration_ms THEN '越界'
         WHEN p.progress_ms >= s.duration_ms - 3000 THEN '贴曲尾!!'
         ELSE '' END                            AS 判定
FROM song_progress p
LEFT JOIN songs s ON s.id = p.song_id
WHERE p.progress_ms > 0
ORDER BY (CASE WHEN COALESCE(s.duration_ms,0) <= 0 THEN 3
               WHEN p.progress_ms >= s.duration_ms - 3000 THEN 2
               ELSE 1 END) DESC,
         p.progress_ms DESC
LIMIT $topn;
SQL
    echo "--------------------------------------------------------------"
    echo "判定列非空 = 可疑断点。'贴曲尾!!' 的点会在点歌时被 seek 到结尾 (旧版 2s/3s 双阈值空档)。"
    echo "数据库副本: $localdb"
}

cmd_log() {
    local secs="${1:-180}"
    local stamp
    stamp="$(date +%Y%m%d_%H%M%S)"
    local raw="$OUT_DIR/logcat_$stamp.raw.log"
    local app="$OUT_DIR/logcat_$stamp.app.log"

    echo "清空车机 logcat 缓冲区..."
    adb -s "$CAR_IP" logcat -c 2>/dev/null

    echo "流式抓日志 ${secs}s -> 现在就去复现 (切到那首歌)"
    echo "  全量: $raw"
    echo "  应用: $app"
    adb -s "$CAR_IP" logcat -v threadtime "*:V" > "$raw" 2>&1 &
    local logpid=$!
    local i
    for ((i = secs; i > 0; i--)); do
        printf "\r  剩余 %3ds (Ctrl-C 可提前结束) " "$i"
        sleep 1
    done
    echo ""
    kill "$logpid" 2>/dev/null
    wait "$logpid" 2>/dev/null

    grep -E "$APP_TAGS" "$raw" > "$app" 2>/dev/null
    echo "抓完: 全量 $(wc -l < "$raw" | tr -d ' ') 行, 应用相关 $(wc -l < "$app" | tr -d ' ') 行"
    if [ -s "$app" ]; then
        echo "--- 应用日志最后 30 行 ---"
        tail -30 "$app"
    else
        echo "警告: 未抓到任何应用日志 (缓冲区被冲掉或进程未起)。"
    fi
}

case "${1:-progress}" in
    progress) shift || true; cmd_progress "${1:-25}" ;;
    log)      shift || true; cmd_log "${1:-180}" ;;
    *)
        echo "用法: $0 {progress [topN] | log [秒]}"
        exit 1
        ;;
esac
