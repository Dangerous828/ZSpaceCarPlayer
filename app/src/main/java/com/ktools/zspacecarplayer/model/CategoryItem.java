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

    /** 缺少流派元数据时用明确标签展示，不把它伪装成实体流派名。 */
    public static String displayGenreName(String genre) {
        if (genre == null || genre.trim().isEmpty() || "未分类".equals(genre.trim())) {
            return "无流派标签";
        }
        return genre;
    }

    /** 按展示标题匹配原始流派值。 */
    public static boolean matchesGenreName(String categoryName, String genre) {
        if ("无流派标签".equals(categoryName) || "未分类".equals(categoryName)) {
            return genre == null || genre.trim().isEmpty() || "未分类".equals(genre.trim());
        }
        return categoryName != null && categoryName.equals(genre);
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
