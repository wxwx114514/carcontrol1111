package com.example.carcontrol;

/**
 * 和单片机约定的 10 字节帧：
 *   [0]=0xA5  [1]=0x5A  [2]=0x01(控制)  [3]=seq
 *   [4][5]=v int16 小端（-1000~1000，正=前进）
 *   [6][7]=w int16 小端（-1000~1000，正=右转）
 *   [8]=flags  bit0=刹车  bit2=急停
 *   [9]=前 9 字节异或
 */
public final class CarProtocol {

    public static final int FLAG_BRAKE = 0x01;
    public static final int FLAG_ESTOP = 0x04;

    private static int sSeq = 0;

    private CarProtocol() { }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static byte[] frame(int v, int w, int flags) {
        byte[] f = new byte[10];

        f[0] = (byte) 0xA5;
        f[1] = (byte) 0x5A;
        f[2] = (byte) 0x01;
        f[3] = (byte) (sSeq++ & 0xFF);

        v = clamp(v, -1000, 1000);
        w = clamp(w, -1000, 1000);

        f[4] = (byte) (v & 0xFF);
        f[5] = (byte) ((v >> 8) & 0xFF);
        f[6] = (byte) (w & 0xFF);
        f[7] = (byte) ((w >> 8) & 0xFF);
        f[8] = (byte) (flags & 0xFF);

        int chk = 0;
        for (int i = 0; i < 9; i++) {
            chk ^= (f[i] & 0xFF);
        }
        f[9] = (byte) (chk & 0xFF);

        return f;
    }
}
