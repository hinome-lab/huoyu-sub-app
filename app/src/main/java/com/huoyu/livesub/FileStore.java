package com.huoyu.livesub;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 把导出的字幕文本落到「下载/火羽字幕/」目录：
 *  - Android 10（API 29）及以上：MediaStore.Downloads + RELATIVE_PATH，无需任何存储权限；
 *  - Android 7.0-9（API 24-28）：公共下载目录 + WRITE_EXTERNAL_STORAGE 运行时权限。
 * 重名自动加 -2 / -3 后缀（Q+ 由 MediaStore 自动改名）。
 */
public final class FileStore {

    /** 下载目录下的子目录名 */
    public static final String DIR_NAME = "火羽字幕";

    private FileStore() {
    }

    /**
     * 保存文本到下载目录。
     *
     * @return 方便展示的保存位置，如「下载/火羽字幕/火羽字幕-2026-10-22_0906.txt」
     * @throws IOException 写入失败（含权限不足）
     */
    public static String save(Context ctx, String name, String mime, String text) throws IOException {
        byte[] data = text.getBytes("UTF-8");
        String safeName = sanitize(name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String where = saveQ(ctx, safeName, mime, data);
            return "下载/" + DIR_NAME + "/" + where;
        }
        String fileName = saveLegacy(safeName, data);
        return "下载/" + DIR_NAME + "/" + fileName;
    }

    /** API 29+：插入媒体库（MediaStore 会自动处理重名） */
    private static String saveQ(Context ctx, String name, String mime, byte[] data) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("无法在下载目录创建文件（媒体库拒绝）");

            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("无法写入下载目录");
            try {
                os.write(data);
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);

            String shown = queryName(cr, uri);
            return (shown == null || shown.isEmpty()) ? name : shown;
        } catch (IOException e) {
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            throw e;
        } catch (Throwable t) {
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            throw new IOException("写入失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (" " + t.getMessage())));
        }
    }

    private static String queryName(ContentResolver cr, Uri uri) {
        android.database.Cursor c = null;
        try {
            c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** API 24-28：直接写公共下载目录（需要 WRITE_EXTERNAL_STORAGE） */
    @SuppressWarnings("deprecation")
    private static String saveLegacy(String name, byte[] data) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录：" + dir.getAbsolutePath());
        if (!dir.canWrite()) throw new IOException("下载目录不可写（请授予存储权限）");

        File f = unique(dir, name);
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(f);
            fo.write(data);
            fo.flush();
        } finally {
            if (fo != null) {
                try {
                    fo.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return f.getName();
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 999; i++) {
            f = new File(dir, stem + "-" + i + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + ext);
    }

    /** 文件名去掉路径分隔符等非法字符 */
    private static String sanitize(String name) {
        String n = name == null ? "火羽字幕.txt" : name;
        n = n.replace('/', '_').replace('\\', '_').replace(':', '_')
                .replace('*', '_').replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_').trim();
        if (n.isEmpty()) n = "火羽字幕.txt";
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n;
    }
}
