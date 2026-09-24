package com.ktools.zspacecarplayer.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.SpannableString;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.LyricLine;

import java.util.ArrayList;
import java.util.List;

public class LyricAdapter extends RecyclerView.Adapter<LyricAdapter.LyricViewHolder> {

    private List<LyricLine> lyrics = new ArrayList<>();
    private int currentHighlightIndex = -1;

    // 基础字号 (px), 首次绑定时从 dimens 读取: 手机紧凑档 24sp / 车机 48sp
    private float baseTextSizePx = -1f;
    private int offsetMs = 0; // 时间轴 Offset 微调

    public void setLyrics(List<LyricLine> newLyrics) {
        this.lyrics = newLyrics != null ? newLyrics : new ArrayList<LyricLine>();
        this.currentHighlightIndex = -1;
        notifyDataSetChanged();
    }

    public void setBaseTextSizeSp(float sizePx) {
        this.baseTextSizePx = sizePx;
        notifyDataSetChanged();
    }

    public float getBaseTextSizePx() {
        return baseTextSizePx;
    }

    public void setOffsetMs(int offsetMs) {
        this.offsetMs = offsetMs;
    }

    public int getOffsetMs() {
        return offsetMs;
    }

    /** 当前高亮句下标; -1 表示还没有歌词进入高亮 */
    public int getHighlightIndex() {
        return currentHighlightIndex;
    }

    public int updateHighlight(long currentMs) {
        if (lyrics == null || lyrics.isEmpty()) return -1;

        long adjustedMs = currentMs + offsetMs;
        int newIndex = -1;
        for (int i = 0; i < lyrics.size(); i++) {
            if (lyrics.get(i).getTimeMs() <= adjustedMs) {
                newIndex = i;
            } else {
                break;
            }
        }

        if (newIndex != currentHighlightIndex && newIndex != -1) {
            int oldIndex = currentHighlightIndex;
            currentHighlightIndex = newIndex;
            if (oldIndex >= 0 && oldIndex < lyrics.size()) notifyItemChanged(oldIndex);
            if (currentHighlightIndex >= 0 && currentHighlightIndex < lyrics.size()) notifyItemChanged(currentHighlightIndex);
        }
        return currentHighlightIndex;
    }

    @NonNull
    @Override
    public LyricViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lyric, parent, false);
        return new LyricViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LyricViewHolder holder, int position) {
        LyricLine line = lyrics.get(position);
        holder.tvLyricLine.setText(line.getText());

        if (baseTextSizePx < 0) {
            baseTextSizePx = holder.tvLyricLine.getResources().getDimension(R.dimen.lyric_base);
        }

        if (position == currentHighlightIndex) {
            holder.tvLyricLine.setTextColor(color(holder, R.color.ink));
            holder.tvLyricLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx * 1.25f);
            holder.tvLyricLine.setTypeface(null, Typeface.BOLD);
        } else {
            holder.tvLyricLine.setTextColor(color(holder, R.color.lyric_off));
            holder.tvLyricLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx);
            holder.tvLyricLine.setTypeface(null, Typeface.NORMAL);
        }
    }

    private static int color(RecyclerView.ViewHolder holder, int colorRes) {
        return holder.itemView.getContext().getResources().getColor(colorRes);
    }

    /**
     * 底部通栏的单行歌词: 上一句 / 当前句 / 下一句 拼成一行, 当前句用强调色加粗。
     * 与 rvLyrics 共用同一个 currentHighlightIndex, 不另立第二套「当前句」状态。
     *
     * @return 无歌词时返回 null, 由调用方决定占位文案
     */
    public CharSequence renderTicker(Context context) {
        if (lyrics.isEmpty() || currentHighlightIndex < 0) return null;
        List<String> parts = new ArrayList<>();
        boolean currentFirst = currentHighlightIndex == 0;
        if (!currentFirst) parts.add(lyrics.get(currentHighlightIndex - 1).getText());
        parts.add(lyrics.get(currentHighlightIndex).getText());
        if (currentHighlightIndex + 1 < lyrics.size()) parts.add(lyrics.get(currentHighlightIndex + 1).getText());
        int currentSlot = currentFirst ? 0 : 1;

        StringBuilder sb = new StringBuilder();
        int currentStart = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("   ");
            if (i == currentSlot) currentStart = sb.length();
            sb.append(parts.get(i));
        }

        String currentText = parts.get(currentSlot);
        int offColor = context.getResources().getColor(R.color.lyric_off);
        int onColor = context.getResources().getColor(R.color.accent);
        SpannableString sp = new SpannableString(sb.toString());
        sp.setSpan(new ForegroundColorSpan(offColor),
                0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(onColor),
                currentStart, currentStart + currentText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new StyleSpan(Typeface.BOLD),
                currentStart, currentStart + currentText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    @Override
    public int getItemCount() {
        return lyrics.size();
    }

    static class LyricViewHolder extends RecyclerView.ViewHolder {
        TextView tvLyricLine;

        public LyricViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLyricLine = itemView.findViewById(R.id.tvLyricLine);
        }
    }
}
