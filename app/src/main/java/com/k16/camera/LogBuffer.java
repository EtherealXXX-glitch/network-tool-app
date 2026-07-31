package com.k16.camera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

final class LogBuffer {
    private static final long MAX_BYTES = 100L * 1024L * 1024L;
    private static final int DISPLAY_BYTES = 512 * 1024;

    private final File file;

    LogBuffer(File cacheDir) {
        file = new File(cacheDir, "netassist_session.log");
        clear();
    }

    synchronized void append(String text) {
        try {
            FileOutputStream stream = new FileOutputStream(file, true);
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            stream.close();
            trimIfNeeded();
        } catch (IOException ignored) {
        }
    }

    synchronized String displayText() {
        if (!file.exists()) {
            return "";
        }
        try {
            RandomAccessFile reader = new RandomAccessFile(file, "r");
            long length = reader.length();
            long start = Math.max(0L, length - DISPLAY_BYTES);
            reader.seek(start);
            byte[] bytes = new byte[(int) (length - start)];
            reader.readFully(bytes);
            reader.close();
            String prefix = start > 0 ? "... 已隐藏更早日志，缓存仍保留，当前显示最后512KB ...\n" : "";
            return prefix + new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    synchronized String allTextForCopy() {
        if (!file.exists()) {
            return "";
        }
        try {
            FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            input.close();
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    synchronized void clear() {
        if (file.exists()) {
            file.delete();
        }
        try {
            file.createNewFile();
        } catch (IOException ignored) {
        }
    }

    synchronized void deleteOnExit() {
        clear();
        if (file.exists()) {
            file.delete();
        }
    }

    synchronized long size() {
        return file.exists() ? file.length() : 0L;
    }

    private void trimIfNeeded() throws IOException {
        long length = file.length();
        if (length <= MAX_BYTES) {
            return;
        }
        long keepStart = length - MAX_BYTES;
        File temp = new File(file.getParentFile(), "netassist_session.tmp");
        RandomAccessFile reader = new RandomAccessFile(file, "r");
        FileOutputStream writer = new FileOutputStream(temp, false);
        reader.seek(keepStart);
        byte[] buffer = new byte[8192];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            if (count > 0) {
                writer.write(buffer, 0, count);
            }
        }
        reader.close();
        writer.close();
        if (file.delete()) {
            temp.renameTo(file);
        } else {
            temp.delete();
        }
    }
}
