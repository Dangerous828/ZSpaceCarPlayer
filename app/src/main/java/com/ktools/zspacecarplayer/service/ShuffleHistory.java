package com.ktools.zspacecarplayer.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 随机模式的播放历史栈（2026-10-08 T1 基准补齐）。
 *
 * <p>要解决的问题很具体：旧实现里随机模式的 {@code playPrevious()} 也是
 * {@code new Random().nextInt(size)}，所以按"上一首"会<b>跳到任意一首</b>（大概率是往后），
 * 用户观感就是"随机模式下上一首根本不能用"。市面 T1 的语义是：随机是一次<b>可来回走的序列</b>，
 * 上一首回到刚才那首，再按下一首又能走回去。
 *
 * <p>两个栈分工：
 * <ul>
 *   <li>{@code back}：已经播过的位置，按"上一首"弹出一个并压进 {@code forward}。</li>
 *   <li>{@code forward}：靠"上一首"退回去之后留下的来路，按"下一首"优先复用，
 *       这样"上→下"能回到原处，而不是又随机一次。</li>
 * </ul>
 *
 * <p>容量上限 {@link #MAX}：车机内存小且 60 首的可回溯深度已足够；超限时丢最旧的，
 * 因为最旧的几乎不会被按到。队列变化时必须 {@link #reset()}——栈里存的是<b>下标</b>，
 * 队列一变就全部失效，错用会把"上一首"变成跳到不相干的歌。
 */
public final class ShuffleHistory {

    /** 最多可回溯的深度。 */
    public static final int MAX = 60;

    private final List<Integer> back = new ArrayList<Integer>();
    private final List<Integer> forward = new ArrayList<Integer>();

    /** 离开当前位置之前记录它：只有"往前进"（下一首/自动续播）才产生可回溯历史。 */
    public void pushBack(int index) {
        if (index < 0) {
            return;
        }
        back.add(Integer.valueOf(index));
        if (back.size() > MAX) {
            back.remove(0);
        }
        // 往前进就说明用户不再需要"来路"了，留着会让下一首随机跳回旧位置
        forward.clear();
    }

    public boolean canGoBack() {
        return !back.isEmpty();
    }

    public boolean canGoForward() {
        return !forward.isEmpty();
    }

    /**
     * 按"上一首"：回到上一个播过的位置，同时把当前位置留作来路。
     *
     * <p>空栈返回 {@code -1} 且<b>不改动任何状态</b>：调用方应当先问 {@link #canGoBack()}，
     * 但这个类不能因为一次误调用就抛异常，更不能在抛之前已经把 {@code forward} 改坏
     * ——那会把"上一首"变成"下一首随机"，比原来的 bug 更难查 (2026-10-08 单测抓到)。
     */
    public int goBack(int currentIndex) {
        if (back.isEmpty()) {
            return -1;
        }
        Integer prev = back.remove(back.size() - 1);
        if (currentIndex >= 0) {
            forward.add(Integer.valueOf(currentIndex));
            if (forward.size() > MAX) {
                forward.remove(0);
            }
        }
        return prev.intValue();
    }

    /** 按"下一首"且存在来路：走回刚被"上一首"退掉的那首。空则返回 -1，同 {@link #goBack(int)}。 */
    public int goForward() {
        if (forward.isEmpty()) {
            return -1;
        }
        Integer next = forward.remove(forward.size() - 1);
        back.add(next);
        return next.intValue();
    }

    /** 队列被替换/重排时调用：下标全部作废。 */
    public void reset() {
        back.clear();
        forward.clear();
    }

    public int backSize() {
        return back.size();
    }

    public int forwardSize() {
        return forward.size();
    }
}
