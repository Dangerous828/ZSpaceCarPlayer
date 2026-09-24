package com.ktools.zspacecarplayer.ui;

import com.ktools.zspacecarplayer.model.SongItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 播放队列的重建与重映射。
 *
 * 全应用只有一套列表顺序 (SongDao 的 `name ASC, id ASC`)。这里的方法**只决定成员**,
 * 顺序一律沿用池 (pool) 自带的顺序 —— 一旦在这里按持久化的 id 先后重排, 就等于凭空
 * 造出第二套顺序, 表现为「冷启动恢复出来的列表」和「刷新后从库里读出来的列表」不一致。
 */
public final class QueueRestore {

    private QueueRestore() {}

    /**
     * 用持久化的 id 集合从池里挑出成员; 输出顺序 == 池顺序。
     * ids 为空或池为空返回空列表; 已从库中删除的歌曲自然落选。
     */
    public static List<SongItem> rebuildFromIds(String idsJoined, List<SongItem> pool) {
        List<SongItem> out = new ArrayList<>();
        if (idsJoined == null || idsJoined.trim().isEmpty() || pool == null || pool.isEmpty()) {
            return out;
        }
        Set<String> wanted = new HashSet<>();
        for (String rawId : idsJoined.split("\n")) {
            if (rawId == null) continue;
            String id = rawId.trim();
            if (!id.isEmpty()) wanted.add(id);
        }
        for (SongItem s : pool) {
            if (s != null && s.getId() != null && wanted.contains(s.getId())) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 媒体库刷新后把当前队列重映射到新库对象上, 而不是用全库覆盖队列。
     * 队列为空 / 本就是全库 → 直接用新全库; 重映射后全被删光 (异常) → 兜底新全库,
     * 绝不让队列变空。
     */
    public static List<SongItem> remapToLibrary(List<SongItem> currentQueue, List<SongItem> newLibrary) {
        if (currentQueue == null || currentQueue.isEmpty()) return newLibrary;
        if (newLibrary == null || newLibrary.isEmpty()) return currentQueue;
        if (currentQueue.size() >= newLibrary.size()) return newLibrary;
        StringBuilder ids = new StringBuilder();
        for (SongItem s : currentQueue) {
            if (s == null || s.getId() == null) continue;
            if (ids.length() > 0) ids.append('\n');
            ids.append(s.getId());
        }
        List<SongItem> remapped = rebuildFromIds(ids.toString(), newLibrary);
        return remapped.isEmpty() ? newLibrary : remapped;
    }
}
