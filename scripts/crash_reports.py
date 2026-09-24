#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""车机现场上报速查：读 NAS 落盘的 crash-*.jsonl，按 reportId 去重后给出可扫的摘要。

上报只存在 NAS 侧（手动上报当场 POST、不退避），而 crash-ingest 服务只有健康检查、
没有读取端点。速查走 Mac 本地的 ZFS 映射盘，不必派贾维斯：

    <映射根>/Jarvis/data/crash-reports/crash-YYYYMMDD.jsonl

映射根可用环境变量 CRASH_DIR 覆盖。默认路径是 mac-workstation 上的挂载点。

用法:
    scripts/crash_reports.py                 # 最近 15 条
    scripts/crash_reports.py --since 20260924
    scripts/crash_reports.py --stats         # 下载吞吐/欠载统计
    scripts/crash_reports.py --show 1a0d30df086-1bfb
"""
import argparse
import glob
import json
import os
import re
import statistics
import sys

DEFAULT_DIR = ("/Users/cpuser/Documents/nasTemp/ZSPACE/nvme11-15916258010"
               "/Jarvis/data/crash-reports")
RING_BYTES = 8 * 1024 * 1024
BUFFER_LINE = re.compile(
    r"^(\d+) \[buffer\] report percent=(\d+) buffering=(\w+) lead=(\d+)s remaining=(\d+)s")
KEY_CTX = ("ctx_songTitle", "ctx_playlistSize", "ctx_playlistIndex", "ctx_engine",
           "ctx_channelCount", "ctx_origin", "ctx_nativeOpenTimeout",
           "ctx_nativeStreamStalled", "ctx_lastErrorWhat")


def redact(s):
    return re.sub(r"api_key=[^&\s]+", "api_key=[REDACTED]", s or "")


def load(data_dir):
    files = sorted(glob.glob(os.path.join(data_dir, "crash-*.jsonl")))
    if not files:
        sys.exit("没找到 %s/crash-*.jsonl —— 映射盘没挂载? 用 CRASH_DIR=<路径> 覆盖" % data_dir)
    out, seen = [], set()
    dup = 0
    for path in files:
        with open(path, encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                row = json.loads(line)
                rep = row.get("report")
                if isinstance(rep, str):
                    try:
                        rep = json.loads(rep)
                    except ValueError:
                        continue
                rid = rep.get("reportId")
                if rid in seen:
                    dup += 1          # 同一份报告可能被重复投递, 按行数统计会虚高
                    continue
                seen.add(rid)
                out.append((os.path.basename(path)[6:14], row.get("received_at", ""), rep))
    return out, len(files), dup


def transport(rep):
    url = rep.get("ctx_streamUrl") or ""
    if "audioCodec=flac" in url:
        return "FLAC"
    if "stream.mp3" in url:
        return "直传"
    return "—"


def buffer_points(rep):
    pts = []
    for b in rep.get("breadcrumbs") or []:
        if not isinstance(b, str):
            continue
        m = BUFFER_LINE.match(b)
        if m:
            pts.append((int(m.group(1)), int(m.group(2)), m.group(3) == "true", int(m.group(4))))
    return pts


def row(rep):
    return "%-19s %-6s %-7s %-5s %-14s %4s/%-4s lead%-4s %s" % (
        rep["_received"][:19], rep.get("appVersionName") or "?", transport(rep),
        rep.get("ctx_engine") or "?", (rep.get("ctx_songTitle") or "?")[:14],
        rep.get("ctx_playlistIndex"), rep.get("ctx_playlistSize"),
        rep.get("_lead") or "?", redact(rep.get("ctx_lastErrorWhat") or "") or rep.get("kind") or "")


def cmd_list(reports, limit):
    print("唯一上报 %d 份 (按 reportId 去重后)\n" % len(reports))
    for _, received, rep in reports[-limit:]:
        rep["_received"] = received
        pts = buffer_points(rep)
        rep["_lead"] = ("%d-%d" % (min(p[3] for p in pts), max(p[3] for p in pts))) if pts else None
        print(row(rep))


def cmd_stats(reports):
    rows = []
    for _, received, rep in reports:
        pts = buffer_points(rep)
        if len(pts) < 6:
            continue
        span = (pts[-1][0] - pts[0][0]) / 1000.0
        if span < 10:
            continue
        growth = (pts[-1][1] - pts[0][1]) / 100.0 * RING_BYTES / span / 1024.0
        rows.append((received[:19], rep.get("ctx_songTitle"), growth,
                     min(p[3] for p in pts), max(p[3] for p in pts)))
    if not rows:
        print("没有含足够缓冲采样点的上报")
        return
    g = sorted(r[2] for r in rows)
    starved = sum(1 for x in g if x < 0)
    print("样本 %d 条 (窗口 >=10s 且缓冲点 >=6)\n" % len(rows))
    for t, title, growth, lo, hi in rows:
        print("  %s %-14s 净增 %7.1f KB/s  lead %d-%ds" % (t, (title or "?")[:14], growth, lo, hi))
    print("\n缓冲净增速率 KB/s: 中位 %.1f  最小 %.1f  最大 %.1f"
          % (statistics.median(g), g[0], g[-1]))
    print("净增长为负 (= 下载慢于播放, 会卡顿) 的样本: %d / %d" % (starved, len(rows)))
    print("\n注: 净增速率要加上该曲的源字节率才是实际下载速率。"
          "PCM 直传约 172-176 KB/s, 服务端 FLAC 实测 88-112 KB/s。")


def cmd_show(reports, rid):
    for _, received, rep in reports:
        if rep.get("reportId") != rid:
            continue
        print("received:", received)
        for k in ("appVersionName", "appVersionCode", "kind", "triggerSource",
                  "manufacturer", "model", "androidVersion", "deviceId"):
            print("  %-16s %s" % (k, rep.get(k)))
        for k in KEY_CTX:
            if k in rep:
                print("  %-16s %s" % (k, redact(str(rep[k]))))
        print("  streamUrl        %s" % redact(rep.get("ctx_streamUrl")))
        bc = rep.get("breadcrumbs") or []
        print("\n--- breadcrumbs %d 条 (尾部 40) ---" % len(bc))
        for b in bc[-40:]:
            print("  %s" % redact(b if isinstance(b, str) else json.dumps(b, ensure_ascii=False)))
        return
    sys.exit("没找到 reportId=%s" % rid)


def main():
    ap = argparse.ArgumentParser(description="车机现场上报速查")
    ap.add_argument("--dir", default=os.environ.get("CRASH_DIR", DEFAULT_DIR))
    ap.add_argument("--since", help="只看某天之后, 形如 20260924")
    ap.add_argument("--limit", type=int, default=15)
    ap.add_argument("--stats", action="store_true", help="下载吞吐/欠载统计")
    ap.add_argument("--show", metavar="REPORT_ID", help="展开一份上报的上下文与面包屑")
    a = ap.parse_args()

    reports, nfiles, dup = load(a.dir)
    if a.since:
        reports = [r for r in reports if r[0] >= a.since]
    print("读 %d 个文件, 重复投递 %d 条已合并\n" % (nfiles, dup))
    if not reports:
        print("筛选后没有记录")
        return
    if a.show:
        cmd_show(reports, a.show)
    elif a.stats:
        cmd_stats(reports)
    else:
        cmd_list(reports, a.limit)


if __name__ == "__main__":
    main()
