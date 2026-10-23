package com.ktools.zspacecarplayer.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CategoryItemTest {

    @Test
    public void missingGenreUsesClearDisplayNameAndStillMatchesSongs() {
        assertEquals("无流派标签", CategoryItem.displayGenreName("未分类"));
        assertEquals("无流派标签", CategoryItem.displayGenreName(" "));
        assertEquals("无流派标签", CategoryItem.displayGenreName(null));
        assertTrue(CategoryItem.matchesGenreName("无流派标签", "未分类"));
        assertTrue(CategoryItem.matchesGenreName("未分类", "未分类"));
        assertTrue(CategoryItem.matchesGenreName("无流派标签", null));
        assertFalse(CategoryItem.matchesGenreName("无流派标签", "摇滚"));
    }

    @Test
    public void actualGenreNameRemainsUnchanged() {
        assertEquals("R&B", CategoryItem.displayGenreName("R&B"));
        assertTrue(CategoryItem.matchesGenreName("R&B", "R&B"));
        assertFalse(CategoryItem.matchesGenreName("R&B", "未分类"));
    }
}
