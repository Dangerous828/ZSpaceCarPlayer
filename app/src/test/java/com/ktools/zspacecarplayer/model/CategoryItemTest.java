package com.ktools.zspacecarplayer.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CategoryItemTest {

    @Test
    public void missingGenreDoesNotCreateSeparateCategory() {
        assertFalse(CategoryItem.hasConcreteGenreName("未分类"));
        assertFalse(CategoryItem.hasConcreteGenreName(" "));
        assertFalse(CategoryItem.hasConcreteGenreName(null));
    }

    @Test
    public void actualGenreValuesRemainCategories() {
        assertTrue(CategoryItem.hasConcreteGenreName("R&B"));
        assertTrue(CategoryItem.hasConcreteGenreName("摇滚"));
    }
}
