package com.ktools.zspacecarplayer.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.SongItem;

import java.util.ArrayList;
import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    /** 首字色块的 6 组渐变背景, 按歌曲 Id 哈希轮转 (对应设计稿 hsl 渐变色板) */
    private static final int[] THUMB_BG = {
            R.drawable.bg_thumb_1, R.drawable.bg_thumb_2, R.drawable.bg_thumb_3,
            R.drawable.bg_thumb_4, R.drawable.bg_thumb_5, R.drawable.bg_thumb_6
    };

    private List<SongItem> songs = new ArrayList<>();
    private int selectedIndex = -1;
    private boolean showPlayCount = false;
    private OnSongClickListener listener;
    private OnFavClickListener favListener;

    public interface OnSongClickListener {
        void onSongClick(SongItem song, int position);
    }

    public interface OnFavClickListener {
        void onFavClick(SongItem song, int position);
    }

    public void setOnSongClickListener(OnSongClickListener listener) {
        this.listener = listener;
    }

    public void setOnFavClickListener(OnFavClickListener favListener) {
        this.favListener = favListener;
    }

    public void setSongs(List<SongItem> newSongs) {
        this.songs = newSongs != null ? newSongs : new ArrayList<SongItem>();
        notifyDataSetChanged();
    }

    public void setShowPlayCount(boolean showPlayCount) {
        this.showPlayCount = showPlayCount;
        notifyDataSetChanged();
    }

    public void setSelectedIndex(int index) {
        int oldIndex = selectedIndex;
        selectedIndex = index;
        if (oldIndex >= 0 && oldIndex < songs.size()) notifyItemChanged(oldIndex);
        if (selectedIndex >= 0 && selectedIndex < songs.size()) notifyItemChanged(selectedIndex);
    }

    /** 当前高亮行下标; -1 表示正在播的歌不在本列表 (调用方据此决定是否回顶部) */
    public int getSelectedIndex() {
        return selectedIndex;
    }

    @NonNull
    @Override
    public SongViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
        return new SongViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SongViewHolder holder, final int position) {
        final SongItem song = songs.get(position);
        holder.tvSongTitle.setText(song.getName());
        holder.tvArtistAlbum.setText(song.getArtist());

        // 首字色块: 渐变按歌曲 Id 稳定轮转, 展示歌名首个字符
        int variant = Math.abs(song.getId().hashCode()) % THUMB_BG.length;
        holder.tvThumb.setBackgroundResource(THUMB_BG[variant]);
        String first = firstDisplayChar(song.getName());
        holder.tvThumb.setText(first);

        // 流派小标签 (未分类不展示)
        String genre = song.getGenre();
        if (genre != null && !genre.trim().isEmpty() && !"未分类".equals(genre)) {
            holder.tvGenreTag.setText(genre);
            holder.tvGenreTag.setVisibility(View.VISIBLE);
        } else {
            holder.tvGenreTag.setVisibility(View.GONE);
        }

        long sec = (song.getDurationMs() / 1000) % 60;
        long min = (song.getDurationMs() / 1000) / 60;
        String durationText = String.format("%02d:%02d", min, sec);
        if (showPlayCount && song.getPlayCount() > 0) {
            durationText += " · " + song.getPlayCount() + "次";
        }
        holder.tvDuration.setText(durationText);

        if (song.isFavorite()) {
            holder.tvFavIcon.setText("♥");
            holder.tvFavIcon.setTextColor(Color.parseColor("#F3B34C"));
        } else {
            // 车机字体缺 ♡ (U+2661) 字形会渲染成方框, 统一用安全字符 ♥: 未收藏=淡灰
            holder.tvFavIcon.setText("♥");
            holder.tvFavIcon.setTextColor(Color.parseColor("#4D738195"));
        }

        boolean selected = (position == selectedIndex);
        // state_selected 驱动 bg_row_song 选择器 (高亮底 + 边框), 左侧指示条单独控制
        holder.itemView.setSelected(selected);
        holder.accentBar.setVisibility(selected ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onSongClick(song, position);
                }
            }
        });

        holder.tvFavIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (favListener != null) {
                    favListener.onFavClick(song, position);
                }
            }
        });
    }

    /** 色块展示字符: 跳过歌名前导的 - _ 空格 括号等符号, 全是符号时回退音符 */
    static String firstDisplayChar(String name) {
        if (name == null || name.trim().length() == 0) return "♪";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-' || c == '_' || c == ' ' || c == '.' || c == '·' || c == '【'
                    || c == '】' || c == '[' || c == ']' || c == '(' || c == ')'
                    || c == '《' || c == '》') {
                continue;
            }
            return String.valueOf(c);
        }
        return "♪";
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        TextView tvThumb;
        TextView tvSongTitle;
        TextView tvArtistAlbum;
        TextView tvGenreTag;
        TextView tvFavIcon;
        TextView tvDuration;
        View accentBar;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            tvThumb = itemView.findViewById(R.id.tvThumb);
            tvSongTitle = itemView.findViewById(R.id.tvSongTitle);
            tvArtistAlbum = itemView.findViewById(R.id.tvArtistAlbum);
            tvGenreTag = itemView.findViewById(R.id.tvGenreTag);
            tvFavIcon = itemView.findViewById(R.id.tvFavIcon);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            accentBar = itemView.findViewById(R.id.accentBar);
        }
    }
}
