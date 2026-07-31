package com.k16.camera;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class HexCodec {
    private HexCodec() {
    }

    static byte[] parse(String input) {
        String normalized = input
                .replace("0x", "")
                .replace("0X", "")
                .replace(",", " ")
                .replace(";", " ")
                .trim();

        String compact = normalized.replaceAll("\\s+", "");
        if (compact.length() == 0) {
            return new byte[0];
        }
        if ((compact.length() & 1) != 0) {
            throw new IllegalArgumentException("HEX长度必须是偶数");
        }

        byte[] data = new byte[compact.length() / 2];
        for (int i = 0; i < compact.length(); i += 2) {
            int high = Character.digit(compact.charAt(i), 16);
            int low = Character.digit(compact.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("HEX内容包含非法字符");
            }
            data[i / 2] = (byte) ((high << 4) | low);
        }
        return data;
    }

    static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            int value = data[i] & 0xFF;
            if (value < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(value).toUpperCase(Locale.US));
        }
        return builder.toString();
    }

    static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    static String unescape(String input) {
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current != '\\' || i == input.length() - 1) {
                builder.append(current);
                continue;
            }

            char next = input.charAt(++i);
            if (next == 'r') {
                builder.append('\r');
            } else if (next == 'n') {
                builder.append('\n');
            } else if (next == 't') {
                builder.append('\t');
            } else if (next == '\\') {
                builder.append('\\');
            } else if (next == '0') {
                builder.append('\0');
            } else if (next == 'x' || next == 'X') {
                if (i + 2 >= input.length()) {
                    throw new IllegalArgumentException("\\x 后面必须跟两个HEX字符");
                }
                int high = Character.digit(input.charAt(i + 1), 16);
                int low = Character.digit(input.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("\\x 后面包含非法HEX字符");
                }
                builder.append((char) ((high << 4) | low));
                i += 2;
            } else {
                builder.append(next);
            }
        }
        return builder.toString();
    }

    static String now() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(new Date());
    }
}
