package com.yijin.xiangqi.light;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Calls run on the Activity's single worker; stop is safe from the main thread. */
final class StrongEngine {
    private static final String NETWORK_HASH = "7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e";
    private volatile long handle;
    private final Context context;
    StrongEngine(Context context) { this.context = context.getApplicationContext(); }
    private static native long nativeCreate(String directory);
    private static native String nativeSearch(long handle, String fen, int depth, int millis);
    private static native void nativeStop(long handle);
    private static native void nativeDestroy(long handle);

    Chess.Move bestMove(int[] board, int turn, int level) throws Exception {
        if (Chess.moves(board, turn).isEmpty()) return null;
        ensureReady();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String move = nativeSearch(handle, Chess.fen(board, turn), level == 2 ? 6 : 0, level == 2 ? 700 : 2400);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Chess.Move parsed = Chess.fromUci(move);
        if (parsed == null || !Chess.legal(board, parsed.from, parsed.to, turn)) throw new Exception("引擎返回了无效走法");
        return parsed;
    }
    private void ensureReady() throws Exception {
        if (handle != 0) return;
        File folder = new File(context.getNoBackupFilesDir(), "pikafish");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("无法准备引擎目录");
        File network = new File(folder, "pikafish.nnue");
        if (!network.isFile() || !NETWORK_HASH.equals(hash(network))) {
            File temp = new File(folder, "network.part");
            try (InputStream input = context.getAssets().open("pikafish.nnue"); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] bytes = new byte[65536]; int n;
                while ((n = input.read(bytes)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(bytes, 0, n);
                }
            }
            if (!NETWORK_HASH.equals(hash(temp))) throw new Exception("引擎文件校验失败");
            if (network.exists() && !network.delete()) throw new Exception("无法更新引擎文件");
            if (!temp.renameTo(network)) throw new Exception("无法保存引擎文件");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        System.loadLibrary("strongengine");
        handle = nativeCreate(folder.getAbsolutePath());
        if (handle == 0) throw new Exception("引擎未能初始化");
    }
    private String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int n;
            while ((n = in.read(bytes)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                digest.update(bytes, 0, n);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    void stop() { long current = handle; if (current != 0) nativeStop(current); }
    void close() { long current = handle; handle = 0; if (current != 0) nativeDestroy(current); }
}
