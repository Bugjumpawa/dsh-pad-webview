package net.atheris.dshpad;

import android.content.Context;
import android.content.SharedPreferences;

/** 唯一的状态来源：入口地址 + 附加 query + 屏幕常亮。 */
final class Prefs {
    static final String FILE = "dsh_pad";
    static final String KEY_URL = "url";
    static final String KEY_QUERY = "query";
    static final String KEY_KEEP_AWAKE = "keep_awake";

    /** 空 = 首次启动必须在设置页填地址（避免把作者的内网地址带进公开版本）。 */
    static final String DEFAULT_URL = "";
    // 注意：prts-safe=1 会让 PRTS 主题整个停用，只做排障兜底，默认不填。
    static final String DEFAULT_QUERY = "";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 空字符串表示尚未配置，首次启动会弹出设置页。 */
    static String url(Context c) {
        return get(c).getString(KEY_URL, "");
    }

    static void url(Context c, String v) {
        get(c).edit().putString(KEY_URL, v).apply();
    }

    static String query(Context c) {
        return get(c).getString(KEY_QUERY, DEFAULT_QUERY);
    }

    static void query(Context c, String v) {
        get(c).edit().putString(KEY_QUERY, v).apply();
    }

    static boolean keepAwake(Context c) {
        return get(c).getBoolean(KEY_KEEP_AWAKE, false);
    }

    static void keepAwake(Context c, boolean v) {
        get(c).edit().putBoolean(KEY_KEEP_AWAKE, v).apply();
    }
}
