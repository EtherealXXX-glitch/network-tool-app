package com.k16.camera;

final class AppendSettings {
    static final String[] CHECKSUM_NAMES = {
            "无",
            "累加和8",
            "累加和16",
            "异或校验8",
            "CRC16(Modbus)",
            "CRC16(自定义)"
    };

    static final String[] TAIL_NAMES = {
            "无",
            "回车",
            "换行",
            "回车换行",
            "自定义HEX"
    };

    int checksumMode;
    int tailMode = 1;
    int startOffset;
    int crcPoly = 0xA001;
    int crcInit = 0xFFFF;
    int crcXorOut;
    boolean highByteFirst = true;
    boolean inputReflect = true;
    boolean outputReflect = true;
    String customTailHex = "0D";

    byte[] apply(byte[] payload) {
        byte[] checksum = checksumBytes(payload);
        byte[] tail = tailBytes();
        return HexCodec.concat(HexCodec.concat(payload, checksum), tail);
    }

    String summary() {
        return "附加位: " + CHECKSUM_NAMES[checksumMode] + " + " + TAIL_NAMES[tailMode]
                + ", 起始 " + startOffset + " 字节"
                + (checksumMode >= 2 ? (highByteFirst ? ", 高字节在前" : ", 低字节在前") : "");
    }

    private byte[] checksumBytes(byte[] payload) {
        int start = Math.max(0, Math.min(startOffset, payload.length));
        int length = payload.length - start;
        if (checksumMode == 0 || length <= 0) {
            return new byte[0];
        }

        if (checksumMode == 1) {
            int sum = 0;
            for (int i = start; i < payload.length; i++) {
                sum = (sum + (payload[i] & 0xFF)) & 0xFF;
            }
            return new byte[]{(byte) sum};
        }

        if (checksumMode == 2) {
            int sum = 0;
            for (int i = start; i < payload.length; i++) {
                sum = (sum + (payload[i] & 0xFF)) & 0xFFFF;
            }
            return wordBytes(sum);
        }

        if (checksumMode == 3) {
            int xor = 0;
            for (int i = start; i < payload.length; i++) {
                xor ^= payload[i] & 0xFF;
            }
            return new byte[]{(byte) xor};
        }

        return wordBytes(crc16(payload, start));
    }

    private int crc16(byte[] payload, int start) {
        boolean custom = checksumMode == 5;
        int poly = custom ? (crcPoly & 0xFFFF) : 0xA001;
        int crc = custom ? (crcInit & 0xFFFF) : 0xFFFF;
        int xorOut = custom ? (crcXorOut & 0xFFFF) : 0x0000;
        boolean refin = custom ? inputReflect : true;
        boolean refout = custom ? outputReflect : true;

        if (refin) {
            for (int i = start; i < payload.length; i++) {
                crc ^= payload[i] & 0xFF;
                for (int bit = 0; bit < 8; bit++) {
                    if ((crc & 0x0001) != 0) {
                        crc = (crc >>> 1) ^ poly;
                    } else {
                        crc >>>= 1;
                    }
                }
            }
        } else {
            for (int i = start; i < payload.length; i++) {
                crc ^= (payload[i] & 0xFF) << 8;
                for (int bit = 0; bit < 8; bit++) {
                    if ((crc & 0x8000) != 0) {
                        crc = (crc << 1) ^ poly;
                    } else {
                        crc <<= 1;
                    }
                    crc &= 0xFFFF;
                }
            }
        }

        crc &= 0xFFFF;
        if (refin != refout) {
            crc = reflect16(crc);
        }
        return (crc ^ xorOut) & 0xFFFF;
    }

    private byte[] tailBytes() {
        if (tailMode == 1) {
            return new byte[]{0x0D};
        }
        if (tailMode == 2) {
            return new byte[]{0x0A};
        }
        if (tailMode == 3) {
            return new byte[]{0x0D, 0x0A};
        }
        if (tailMode == 4) {
            return HexCodec.parse(customTailHex);
        }
        return new byte[0];
    }

    private byte[] wordBytes(int value) {
        if (highByteFirst) {
            return new byte[]{(byte) ((value >>> 8) & 0xFF), (byte) (value & 0xFF)};
        }
        return new byte[]{(byte) (value & 0xFF), (byte) ((value >>> 8) & 0xFF)};
    }

    private int reflect16(int value) {
        int reflected = 0;
        for (int i = 0; i < 16; i++) {
            if ((value & (1 << i)) != 0) {
                reflected |= 1 << (15 - i);
            }
        }
        return reflected & 0xFFFF;
    }
}
