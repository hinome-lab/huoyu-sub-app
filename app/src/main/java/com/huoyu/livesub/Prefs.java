package com.huoyu.livesub;

/** 全局配置键与默认值 */
public final class Prefs {

    public static final String NAME = "cfg";

    public static final String DEF_BASE = "http://123.60.104.22:6006/";
    public static final String DEF_TOKEN = "hy";

    public static final int DEF_FS = 17;
    public static final int MIN_FS = 11;
    public static final int MAX_FS = 40;

    public static final int DEF_LINES = 2;
    public static final int DEF_ALPHA = 175;

    public static final String KEY_BASE = "base";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_FS = "fs";
    public static final String KEY_LINES = "lines";
    public static final String KEY_ALPHA = "alpha";
    public static final String KEY_JA = "ja";
    public static final String KEY_LOCKED = "locked";
    public static final String KEY_X = "x";
    public static final String KEY_Y = "y";

    private Prefs() {
    }
}
