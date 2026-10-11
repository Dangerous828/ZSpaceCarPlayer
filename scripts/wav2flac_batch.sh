#!/bin/sh
# 曲库 WAV → FLAC 批量预转（2026-10-11）
#
# 为什么做：435 首 WAV 走的是服务端 `stream.flac?static=false` 实时转码，那条响应实测是
# `Transfer-Encoding: chunked` + `Accept-Ranges: none` + 无 Content-Length。后果（2026-10-11
# 真车上报为证）：
#   ① 磁盘缓存整层空转（StreamDiskCache 要求 contentLength>0，实测 stored=0B、discarded=2.85MB）
#   ② percent=-1 ⇒ lead/remaining 全线失真，缓冲健康度判不出
#   ③ 断流续传必须整条重下（服务端无视 Range）
#   ④ 字节率 172–176 KB/s（蜂窝下载中位 169、P25 154、最差 103 ⇒ 必然欠载）
# 换成 FLAC 原件走 `static=true` 实测：有 Content-Length、`206 Partial Content` + Content-Range，
# 字节率降到 ~88–112 KB/s，NAS 也不再逐次烧转码 CPU。
#
# 跑在哪：`wav2flac_batch` 容器（曲库唯一 rw 挂载：/tmp/zfsv3/.../public/Music → /m:rw）。
# jellyfin / navidrome 挂的都是 :ro，改不动。
#
#   sh wav2flac_batch.sh                                  # 全量（可重入，已转的自动 SKIP）
#   DRY_RUN=1 sh wav2flac_batch.sh                         # 只列清单，不写一个字节
#   LIMIT=3   sh wav2flac_batch.sh                         # canary：只处理前 3 首
#   MUSIC_DIR=/m OUT_DIR=/tmp/wav2flac sh wav2flac_batch.sh
#
# 四条铁律（都是踩过的坑，别改）：
#   1. **先校验再替换**：FLAC 先写 .part，ffprobe 解得开且时长与原曲差 <2s 才允许落地。
#      没校验就 mv，坏文件会当场把曲库里那首歌换掉。
#   2. **同名 + 原子替换**：`.flac` 与原 `.wav` 同 stem，旁边同名 `.lrc` 不用动。先把原 wav 移进
#      OUT_ROOT（保留相对路径）再 mv .part → .flac；落地失败必须把 wav 放回原位，
#      否则这首歌在库里凭空消失。
#   3. **非 1/2 声道一律跳过**：车机是 2ch，6ch/7ch 下混会持续削波出「滋滋」声
#      （2026-10-08「多幸运」6ch /「答案」7ch 实测峰值 clamp 到 0dBFS，占全曲 1.4%）。
#      那种要单独走浮点域降增益的专用流程，不在本脚本里顺手做。
#   4. **POSIX sh（容器里 /bin/sh 是 dash）**：dash 的 `read` **不支持 `-d`**
#      （2026-10-11 真机验证：`read -d ''` 报 "Illegal option -d" 后整条循环直接不跑）。
#      所以清单用换行分隔 + `IFS= read -r`。代价是不支持文件名含换行——本曲库不存在这种名字，
#      且下面有 NUL 残留检查兜着。同时计数必须在当前 shell 累加：`find | while` 的循环体
#      跑在子 shell 里计数全丢（10-06 那版日志末尾 DONE 数就是错的），故先落清单再 `< file`。
#
# 关于 seektable：ffmpeg 的 flac muxer **不写 SEEKTABLE**（2026-10-11 实测 10-06 那批 134 首
# 转出来全是 blocks=[STREAMINFO, VORBIS_COMMENT, PADDING]）。没有它 dr_flac 只能二分或从头
# 解码补位。容器里若装了 metaflac（Debian 包名 flac）会自动补 --add-seektable；没有也照跑——
# 本脚本的主要收益（Content-Length / Range / 字节率减半）与 seektable 无关。
#
# 退出码：0 全成功；1 有失败件（见 FAIL_LOG）。

set -u

MUSIC_DIR="${MUSIC_DIR:-/m}"
OUT_DIR="${OUT_DIR:-/tmp/wav2flac}"          # 只放日志；.part 一律写在目标同目录（见下）
# 备份落点：两条硬要求，缺一条就是不安全的，所以**不给默认值、必须显式传**——
#   ① 在 MUSIC_DIR **之外**（放库里：重入时 find 会把已备份的 wav 再捞一遍转第二次；
#      Jellyfin 也会把它们当成新歌扫进库，10-06 那版就是这样）。
#   ② 与 MUSIC_DIR **同一持久文件系统**（`dirname /m` 是 `/` = 容器可写层，
#      容器一删备份就凭空消失；原 wav 是唯一的回退依据，不能赌）。
# 本曲库的正确答案是 /p（宿主机 public 的 rw bind，与 /m 同一个 zfsv3）。
OUT_ROOT="${OUT_ROOT:-}"
LOG="${LOG:-$OUT_DIR/progress.log}"
FAIL_LOG="${FAIL_LOG:-$OUT_DIR/failed.log}"
LIST="$OUT_DIR/wav.list"
DRY_RUN="${DRY_RUN:-0}"
LIMIT="${LIMIT:-0}"
FLAC_LEVEL="${FLAC_LEVEL:-8}"
SEEKPOINTS="${SEEKPOINTS:-10000}"
# 必须与车机入库判定同源：JellyfinApiClient.SERVER_TRANSCODE_BYTES_PER_SEC = 300_000。
# 改了这里就等于改了"哪首歌走直传"，两边不一致会转出一批永远不会被直传的文件。
TRANSCODE_MAX_BYTES_PER_SEC="${TRANSCODE_MAX_BYTES_PER_SEC:-300000}"

mkdir -p "$OUT_DIR" 2>/dev/null || { echo "无法创建 $OUT_DIR" >&2; exit 1; }

if [ -z "$OUT_ROOT" ]; then
    echo "必须显式给 OUT_ROOT（备份落点），例：OUT_ROOT=/p/_wav2flac_bak_$(date +%Y%m%d)" >&2
    exit 1
fi
case "${OUT_ROOT%/}/" in
    "$MUSIC_DIR"/*) echo "OUT_ROOT 不能落在 MUSIC_DIR 之内，否则备份会被再转一遍并被扫进库" >&2; exit 1 ;;
esac
case "${MUSIC_DIR%/}/" in
    "${OUT_ROOT%/}"/*) echo "MUSIC_DIR 不能落在 OUT_ROOT 之内（同上的镜像情形）" >&2; exit 1 ;;
esac

# 同文件系统校验：stat -c %d 取设备号。不同 ⇒ 备份在别的挂载（很可能是容器可写层）。
# stat 不可用/取不到时按"不一致"处理，宁可停手。DRY_RUN 不写盘，跳过建目录与校验。
if [ "$DRY_RUN" != "1" ]; then
    mkdir -p "$OUT_ROOT" 2>/dev/null || { echo "无法创建 $OUT_ROOT" >&2; exit 1; }
    # GNU stat（NAS 容器）用 -c %d；BSD/macOS 用 -f %d。两个都试，取不到就当不一致。
    dev_m=$(stat -c %d "$MUSIC_DIR" 2>/dev/null || stat -f %d "$MUSIC_DIR" 2>/dev/null)
    dev_b=$(stat -c %d "$OUT_ROOT" 2>/dev/null || stat -f %d "$OUT_ROOT" 2>/dev/null)
    if [ -z "$dev_m" ] || [ -z "$dev_b" ] || [ "$dev_m" != "$dev_b" ]; then
        echo "OUT_ROOT($dev_b) 与 MUSIC_DIR($dev_m) 不在同一文件系统 ⇒ 备份可能落在容器可写层，停" >&2
        exit 1
    fi
fi

say() { echo "$(date +%H:%M:%S) $*"; }

for b in ffmpeg ffprobe; do
    command -v "$b" >/dev/null 2>&1 || { say "缺少 $b，终止"; exit 1; }
done
HAVE_METAFLAC=0
command -v metaflac >/dev/null 2>&1 && HAVE_METAFLAC=1
say "开始 MUSIC_DIR=$MUSIC_DIR OUT_DIR=$OUT_DIR OUT_ROOT=$OUT_ROOT metaflac=$HAVE_METAFLAC DRY_RUN=$DRY_RUN LIMIT=$LIMIT"

# `-not -path '*/.*'`：曲库里有历史备份的隐藏目录（.deleted_backup_20261003 /
# .volume_backup_20261003，2026-10-03 音量治理留下的）。Jellyfin 不扫隐藏目录，转它们
# 既拿不到任何播放收益，又会就地改掉"被删掉的原曲"，破坏那两份备份的还原价值。
find "$MUSIC_DIR" -type f -iname '*.wav' -not -path '*/.*' > "$LIST" 2>/dev/null
n_done=0; n_skip=0; n_fail=0; n_chskip=0; n_hires=0

# 清单走**独立的文件描述符 3**，而不是 stdin：任何子进程（ffmpeg 交互式读指令、
# 以后可能加进来的别的工具）都吃不到它。单靠 -nostdin 是对已知问题的定点封堵，
# FD 隔离从结构上断掉"清单被下游吞掉"这一整类事故。
while IFS= read -r wav <&3 || [ -n "$wav" ]; do
    [ -z "$wav" ] && continue
    # 行分隔清单的代价：文件名含换行会被切成两条假路径。本曲库不存在这种名字，
    # 但这里用逐条 -f 校验兜住（顺带覆盖"清单落定后文件又消失"），不做乐观假设。
    if [ ! -f "$wav" ]; then
        n_skip=$((n_skip + 1)); say "SKIP 路径不存在(疑似文件名含换行或已消失): $wav"; continue
    fi
    if [ "$LIMIT" != "0" ] && [ "$n_done" -ge "$LIMIT" ]; then
        say "LIMIT=$LIMIT 到达，停止"; break
    fi

    stem="${wav%.*}"
    flac="$stem.flac"
    rel="${wav#"$MUSIC_DIR"/}"

    if [ -f "$flac" ]; then
        n_skip=$((n_skip + 1)); say "SKIP 已有 flac: $rel"; continue
    fi
    if [ "$DRY_RUN" = "1" ]; then
        n_done=$((n_done + 1)); say "WOULD: $rel"; continue
    fi

    ch=$(ffprobe -v error -select_streams a:0 -show_entries stream=channels \
             -of default=nk=1:nw=1 "$wav" 2>/dev/null | tr -d '[:space:]')
    dur=$(ffprobe -v error -show_entries format=duration \
             -of default=nk=1:nw=1 "$wav" 2>/dev/null | tr -d '[:space:]')
    case "$ch" in
        1|2) : ;;
        "")  n_fail=$((n_fail + 1)); say "FAIL 读不到声道: $rel"; echo "NOCH $rel" >> "$FAIL_LOG"; continue ;;
        *)   n_chskip=$((n_chskip + 1)); say "SKIP 声道=$ch 非1/2(下混会削波): $rel"
             echo "CH$ch $rel" >> "$FAIL_LOG"; continue ;;
    esac

    # .part 必须与最终 .flac **同一目录/同一文件系统**（跨设备的 mv 会退化成复制+删除，
    # 不是原子操作）。后缀不能以 .flac 结尾，否则 Jellyfin 会把半成品当一首真歌扫进库。
    part="$stem.flac.part"
    say "转码($ch ch, ${dur}s): $rel"
    # -f flac 必须显式给：输出名以 .part 结尾，ffmpeg 靠扩展名推断 muxer 会直接失败
    # ("Unable to find a suitable output format")。
    # -nostdin **必须有**：ffmpeg 默认把 stdin 当交互指令读。本脚本的循环是
    # `while read … done < "$LIST"`，ffmpeg 一运行就从清单里咬掉一段字节，下一次 read
    # 拿到的是半行（从多字节中间开始）——2026-10-11 全量批次 217 条 "SKIP 路径不存在"
    # 全是这么来的（日志里 OK→SKIP→OK→SKIP 严格交替、且行首出现  乱码即为铁证）。
    if ! ffmpeg -nostdin -v error -y -i "$wav" -vn -map 0:a:0 \
            -acodec flac -compression_level "$FLAC_LEVEL" -f flac "$part" >/dev/null 2>&1; then
        rm -f "$part"; n_fail=$((n_fail + 1)); say "FAIL ffmpeg: $rel"; echo "FFMPEG $rel" >> "$FAIL_LOG"; continue
    fi

    fdur=$(ffprobe -v error -show_entries format=duration \
             -of default=nk=1:nw=1 "$part" 2>/dev/null | tr -d '[:space:]')
    ok=$(awk -v a="$dur" -v b="$fdur" 'BEGIN{ if(a==""||b==""){print 0;} else {d=a-b; if(d<0)d=-d; print (d<2.0 && b>1.0) ? 1 : 0} }')
    if [ "$ok" != "1" ]; then
        rm -f "$part"; n_fail=$((n_fail + 1))
        say "FAIL 时长不符 (wav=${dur}s flac=${fdur}s): $rel"; echo "DUR $rel" >> "$FAIL_LOG"; continue
    fi

    if [ "$HAVE_METAFLAC" = "1" ] && [ "$SEEKPOINTS" != "0" ]; then
        metaflac --add-seektable="$SEEKPOINTS" "$part" >/dev/null 2>&1 \
            || say "  (seektable 追加失败，继续落地)"
    fi

    # --- hi-res 预检：转完仍然"喂不上"就别换 ---
    # 入库侧的判定是 bytesPerSec > 300_000 ⇒ 继续走服务端转码（JellyfinApiClient
    # .SERVER_TRANSCODE_BYTES_PER_SEC）。转完还超这条门的，换了也换不来
    # Content-Length/Range 的收益，却照样付一次"条目 Id 重置"（收藏/断点/播放次数作废）。
    # 2026-10-11 实测：96kHz 的 Move Your Body (hires) 转完仍 376KB/s，正属此类。
    fsize=$(stat -c %s "$part" 2>/dev/null || wc -c < "$part" | tr -d ' ')
    fbps=$(awk -v s="$fsize" -v d="$fdur" 'BEGIN{ if(d>0) printf "%d", s/d; else print 0 }')
    if [ "${fbps:-0}" -gt "$TRANSCODE_MAX_BYTES_PER_SEC" ]; then
        rm -f "$part"; n_hires=$((n_hires + 1))
        say "SKIP hi-res 转完仍 ${fbps}B/s > ${TRANSCODE_MAX_BYTES_PER_SEC}B/s，换了也走转码: $rel"
        echo "HIRES $fbps $rel" >> "$FAIL_LOG"
        continue
    fi

    bak="$OUT_ROOT/$(dirname "$rel")"
    mkdir -p "$bak" 2>/dev/null || { rm -f "$part"; n_fail=$((n_fail+1))
        say "FAIL 建备份目录: $rel"; echo "MKDIR $rel" >> "$FAIL_LOG"; continue; }
    if ! mv "$wav" "$bak/$(basename "$wav")"; then
        rm -f "$part"; n_fail=$((n_fail + 1)); say "FAIL 备份原 wav: $rel"; echo "BACKUP $rel" >> "$FAIL_LOG"; continue
    fi
    if ! mv "$part" "$flac"; then
        mv "$bak/$(basename "$wav")" "$wav" 2>/dev/null
        rm -f "$part"; n_fail=$((n_fail + 1)); say "FAIL 落地: $rel"; echo "LAND $rel" >> "$FAIL_LOG"; continue
    fi

    n_done=$((n_done + 1))
    say "OK ($n_done) $rel flac=$(du -h "$flac" 2>/dev/null | cut -f1)"
    echo "OK $rel" >> "$LOG"
done 3< "$LIST"

say "汇总：转换=$n_done 已存在跳过=$n_skip 声道跳过=$n_chskip hi-res跳过=$n_hires 失败=$n_fail"
[ "$n_fail" = "0" ] || exit 1
exit 0
