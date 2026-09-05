package com.ktools.zspacecarplayer.model;

import java.io.Serializable;

public class CategoryItem implements Serializable {
    private String id;
    private String name;
    private int songCount;

    public CategoryItem() {}

    public CategoryItem(String id, String name, int songCount) {
        this.id = id;
        this.name = name;
        this.songCount = songCount;
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

    public int getSongCount() {
        return songCount;
    }

    public void setSongCount(int songCount) {
        this.songCount = songCount;
    }
}
