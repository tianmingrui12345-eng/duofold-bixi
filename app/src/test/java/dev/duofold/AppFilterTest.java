package dev.duofold;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The only thing standing between "the effect is broken" and "the effect is everywhere".
 * Four combinations, two always-allowed packages, and the unknown-package case.
 */
public class AppFilterTest {
    private static final String WECHAT="com.tencent.mm";
    private static final String SETTINGS="com.android.settings";

    private static Set<String> set(String... values) {
        Set<String> result=new HashSet<>();
        Collections.addAll(result,values);
        return result;
    }

    @Test public void allModeAllowsEverythingWhenNothingIsMarked() {
        assertTrue(AppFilter.allows(AppFilter.MODE_ALL,set(),WECHAT));
        assertTrue(AppFilter.allows(AppFilter.MODE_ALL,set(),SETTINGS));
    }

    @Test public void allModeBlocksTheMarkedOnes() {
        Set<String> marked=set(WECHAT);
        assertFalse(AppFilter.allows(AppFilter.MODE_ALL,marked,WECHAT));
        assertTrue(AppFilter.allows(AppFilter.MODE_ALL,marked,SETTINGS));
    }

    @Test public void onlyModeAllowsNothingWhenNothingIsMarked() {
        assertFalse(AppFilter.allows(AppFilter.MODE_ONLY,set(),WECHAT));
    }

    @Test public void onlyModeAllowsTheMarkedOnes() {
        Set<String> marked=set(WECHAT);
        assertTrue(AppFilter.allows(AppFilter.MODE_ONLY,marked,WECHAT));
        assertFalse(AppFilter.allows(AppFilter.MODE_ONLY,marked,SETTINGS));
    }

    @Test public void systemUiAndThisAppAreAlwaysAllowed() {
        Set<String> marked=set("com.android.systemui","dev.duofold",WECHAT);
        for(int mode:new int[]{AppFilter.MODE_ALL,AppFilter.MODE_ONLY}) {
            assertTrue(AppFilter.allows(mode,marked,"com.android.systemui"));
            assertTrue(AppFilter.allows(mode,marked,"dev.duofold"));
        }
    }

    @Test public void unknownPackageFailsOpen() {
        for(int mode:new int[]{AppFilter.MODE_ALL,AppFilter.MODE_ONLY}) {
            assertTrue(AppFilter.allows(mode,set(WECHAT),null));
            assertTrue(AppFilter.allows(mode,set(WECHAT),""));
        }
    }
}
