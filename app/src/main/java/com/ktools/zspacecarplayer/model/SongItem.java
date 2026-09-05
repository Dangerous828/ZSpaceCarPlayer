package com.ktools.zspacecarplayer.model;

import java.io.Serializable;

public class SongItem implements Serializable {
    private String id;
    private String name;
    private String artist;
    private String album;
    private String genre;
    private String folderName;
    private long durationMs;
    private String streamUrl;
    private String coverUrl;
    private boolean isFavorite;
    private int playCount;

    public SongItem() {}

    public SongItem(String id, String name, String artist, String album, String genre, long durationMs, String streamUrl, String coverUrl) {
        this(id, name, artist, album, genre, "未分类文件夹", durationMs, streamUrl, coverUrl, false);
    }

    public SongItem(String id, String name, String artist, String album, String genre, String folderName, long durationMs, String streamUrl, String coverUrl, boolean isFavorite) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : "未知曲目";
        this.artist = artist != null ? artist : "未知歌手";
        this.album = album != null ? album : "未知专辑";
        this.genre = (genre != null && !genre.trim().isEmpty()) ? genre : "未分类";
        this.folderName = (folderName != null && !folderName.trim().isEmpty()) ? folderName : "未分类文件夹";
        this.durationMs = durationMs;
        this.streamUrl = streamUrl != null ? streamUrl : "";
        this.coverUrl = coverUrl != null ? coverUrl : "";
        this.isFavorite = isFavorite;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null ? id : "";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name : "未知曲目";
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist != null ? artist : "未知歌手";
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album != null ? album : "未知专辑";
    }

    public String getGenre() {
        return (genre != null && !genre.trim().isEmpty()) ? genre : "未分类";
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getFolderName() {
        return (folderName != null && !folderName.trim().isEmpty()) ? folderName : "未分类文件夹";
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public void setStreamUrl(String streamUrl) {
        this.streamUrl = streamUrl != null ? streamUrl : "";
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl != null ? coverUrl : "";
    }

    public boolean isFavorite() {
        return isFavorite;
    }

    public void setFavorite(boolean favorite) {
        isFavorite = favorite;
    }

    public int getPlayCount() {
        return playCount;
    }

    public void setPlayCount(int playCount) {
        this.playCount = playCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SongItem songItem = (SongItem) o;
        return id.equals(songItem.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
