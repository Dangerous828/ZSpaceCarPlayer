package com.ktools.zspacecarplayer.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.ktools.zspacecarplayer.R;
import com.ktools.zspacecarplayer.model.SongItem;

import java.util.ArrayList;
import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.SongViewHolder> {

    private List<SongItem> songs = new ArrayList<>();
    private int selectedIndex = -1;
    private OnSongClickListener listener;

    public interface OnSongClickListener {
        void onSongClick(SongItem song, int position);
    }

    public void setOnSongClickListener(OnSongClickListener listener) {
        this.listener = listener;
    }

    public void setSongs(List<SongItem> newSongs) {
        this.songs = newSongs != null ? newSongs : new ArrayList<SongItem>();
        notifyDataSetChanged();
    }

    public void setSelectedIndex(int index) {
        int oldIndex = selectedIndex;
        selectedIndex = index;
        if (oldIndex >= 0 && oldIndex < songs.size()) notifyItemChanged(oldIndex);
        if (selectedIndex >= 0 && selectedIndex < songs.size()) notifyItemChanged(selectedIndex);
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
        holder.tvArtistAlbum.setText(song.getArtist() + " · " + song.getAlbum());

        long sec = (song.getDurationMs() / 1000) % 60;
        long min = (song.getDurationMs() / 1000) / 60;
        holder.tvDuration.setText(String.format("%02d:%02d", min, sec));

        if (position == selectedIndex) {
            holder.itemView.setBackgroundResource(R.drawable.bg_item_selected);
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_panel);
        }

        Glide.with(holder.ivCover.getContext())
                .load(song.getCoverUrl())
                .placeholder(R.drawable.bg_cover_placeholder)
                .error(R.drawable.bg_cover_placeholder)
                .into(holder.ivCover);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onSongClick(song, position);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class SongViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        TextView tvSongTitle;
        TextView tvArtistAlbum;
        TextView tvDuration;

        public SongViewHolder(@NonNull View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.ivCover);
            tvSongTitle = itemView.findViewById(R.id.tvSongTitle);
            tvArtistAlbum = itemView.findViewById(R.id.tvArtistAlbum);
            tvDuration = itemView.findViewById(R.id.tvDuration);
        }
    }
}
