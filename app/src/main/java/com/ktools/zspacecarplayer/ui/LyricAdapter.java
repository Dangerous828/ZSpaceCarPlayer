package com.ktools.zspacecarplayer.ui;

import android.graphics.Color;
import android.graphics.Typeface;
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
            holder.tvLyricLine.setTextColor(Color.parseColor("#F1F6FB"));
            holder.tvLyricLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx * 1.25f);
            holder.tvLyricLine.setTypeface(null, Typeface.BOLD);
        } else {
            holder.tvLyricLine.setTextColor(Color.parseColor("#738195"));
            holder.tvLyricLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx);
            holder.tvLyricLine.setTypeface(null, Typeface.NORMAL);
        }
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
