package dev.duofold;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which apps the fold effect is allowed to run in.
 *
 * Two modes, both kept in the same preference set:
 *  - MODE_ALL : the effect runs everywhere except the selected packages
 *  - MODE_ONLY: the effect runs only in the selected packages
 *
 * The lock screen and this app itself are always allowed: the first so the effect keeps working
 * while locked, the second so the settings page never fights its own layer.
 */
public final class AppFilter {
    public static final int MODE_ALL=0;
    public static final int MODE_ONLY=1;

    private static final String KEY_MODE="appFilterMode";
    private static final String KEY_SET="appFilterSet";

    /** Always-allowed packages: system UI (lock screen, notification shade) and ourselves. */
    private static final String[] ALWAYS_ALLOWED={"com.android.systemui","dev.duofold"};

    private static volatile int mode=MODE_ALL;
    private static volatile Set<String> selected=Collections.emptySet();

    private AppFilter() {}

    public static void load(Context context) {
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences("duo",0);
        mode=prefs.getInt(KEY_MODE,MODE_ALL);
        selected=new HashSet<>(prefs.getStringSet(KEY_SET,Collections.emptySet()));
    }

    public static int mode() {return mode;}

    public static Set<String> selected() {return new HashSet<>(selected);}

    public static void setMode(Context context,int value) {
        mode=value==MODE_ONLY?MODE_ONLY:MODE_ALL;
        persist(context);
    }

    public static boolean isMarked(String pkg) {return pkg!=null && selected.contains(pkg);}

    public static void mark(Context context,String pkg,boolean on) {
        if(pkg==null) return;
        Set<String> next=new HashSet<>(selected);
        if(on) next.add(pkg);else next.remove(pkg);
        selected=next;
        persist(context);
    }

    /** True when the effect may be shown over this package. */
    public static boolean allows(String pkg) {
        if(pkg==null || pkg.isEmpty()) return true;
        for(String allowed:ALWAYS_ALLOWED) if(allowed.equals(pkg)) return true;
        boolean marked=selected.contains(pkg);
        return mode==MODE_ONLY ? marked : !marked;
    }

    public static String summary() {
        int count=selected.size();
        if(mode==MODE_ONLY)
            return count==0?UiText.t("仅勾选的应用：还没选任何应用","Only selected apps: nothing selected yet")
                    :UiText.t("仅在 "+count+" 个应用中生效","Active in "+count+" app(s)");
        return count==0?UiText.t("所有应用都生效","Active in all apps")
                :UiText.t("排除 "+count+" 个应用","Excluded in "+count+" app(s)");
    }

    private static void persist(Context context) {
        context.getApplicationContext().getSharedPreferences("duo",0).edit()
                .putInt(KEY_MODE,mode)
                .putStringSet(KEY_SET,new HashSet<>(selected))
                .apply();
    }
}
