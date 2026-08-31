package com.ktools.zspacecarplayer.ui;

import android.graphics.Color;
import android.graphics.Typeface;
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

    public void setLyrics(List<LyricLine> newLyrics) {
        this.lyrics = newLyrics != null ? newLyrics : new ArrayList<LyricLine>();
        this.currentHighlightIndex = -1;
        notifyDataSetChanged();
    }

    public int updateHighlight(long currentMs) {
        if (lyrics == null || lyrics.isEmpty()) return -1;

        int newIndex = -1;
        for (int i = 0; i < lyrics.size(); i++) {
            if (lyrics.get(i).getTimeMs() <= currentMs) {
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

        if (position == currentHighlightIndex) {
            holder.tvLyricLine.setTextColor(Color.parseColor("#00E5FF"));
            holder.tvLyricLine.setTextSize(20);
            holder.tvLyricLine.setTypeface(null, Typeface.BOLD);
        } else {
            holder.tvLyricLine.setTextColor(Color.parseColor("#80FFFFFF"));
            holder.tvLyricLine.setTextSize(16);
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
