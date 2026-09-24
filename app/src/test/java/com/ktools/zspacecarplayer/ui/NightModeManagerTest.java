package com.ktools.zspacecarplayer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 夜间档判定。自动档的两个换档时刻是纯边界, 全部按「落在哪一侧」逐点断言,
 * 不接受「大概晚上」这种描述。
 */
public class NightModeManagerTest {

    @Test
    public void dayModeIsNeverNightAtAnyHour() {
        for (int hour = 0; hour < 24; hour++) {
            assertFalse("白天档在 " + hour + " 点不应转深色",
                    NightModeManager.isNightAt(NightModeManager.MODE_DAY, hour));
        }
    }

    @Test
    public void nightModeIsAlwaysNightAtAnyHour() {
        for (int hour = 0; hour < 24; hour++) {
            assertTrue("夜间档在 " + hour + " 点应恒为深色",
                    NightModeManager.isNightAt(NightModeManager.MODE_NIGHT, hour));
        }
    }

    @Test
    public void autoModeFlipsExactlyOnTheHour() {
        assertFalse(NightModeManager.isNightAt(NightModeManager.MODE_AUTO, 17));
        assertTrue("18:00 整即入夜", NightModeManager.isNightAt(NightModeManager.MODE_AUTO, 18));
        assertTrue(NightModeManager.isNightAt(NightModeManager.MODE_AUTO, 23));
        assertTrue("跨零点仍是夜", NightModeManager.isNightAt(NightModeManager.MODE_AUTO, 0));
        assertTrue(NightModeManager.isNightAt(NightModeManager.MODE_AUTO, 5));
        assertFalse("06:00 整即出夜", NightModeManager.isNightAt(NightModeManager.MODE_AUTO, 6));
        assertFalse(NightModeManager.isNightAt(NightModeManager.MODE_AUTO, 12));
    }

    @Test
    public void autoModeCoversEveryHourWithoutGaps() {
        int nightHours = 0;
        for (int hour = 0; hour < 24; hour++) {
            if (NightModeManager.isNightAt(NightModeManager.MODE_AUTO, hour)) nightHours++;
        }
        assertEquals("夜间时段应为 18 点至次日 6 点共 12 小时", 12, nightHours);
    }

    @Test
    public void everyModeHasADistinctLabel() {
        assertEquals("自动", NightModeManager.modeLabel(NightModeManager.MODE_AUTO));
        assertEquals("白天", NightModeManager.modeLabel(NightModeManager.MODE_DAY));
        assertEquals("夜间", NightModeManager.modeLabel(NightModeManager.MODE_NIGHT));
    }
}
