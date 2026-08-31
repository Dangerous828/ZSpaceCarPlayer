package com.ktools.zspacecarplayer.model;

import java.io.Serializable;

public class SongItem implements Serializable {
    private String id;
    private String name;
    private String artist;
    private String album;
    private long durationMs;
    private String streamUrl;
    private String coverUrl;

    public SongItem() {}

    public SongItem(String id, String name, String artist, String album, long durationMs, String streamUrl, String coverUrl) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
        this.streamUrl = streamUrl;
        this.coverUrl = coverUrl;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
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
        this.streamUrl = streamUrl;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }
}
