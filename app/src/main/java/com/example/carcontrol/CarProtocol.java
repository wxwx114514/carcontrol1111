package com.example.carcontrol;

/**
 * 和单片机约定的 10 字节帧：
 *
 *   [0]=0xA5  [1]=0x5A  [2]=类型  [3]=seq
 *   [4][5]=a int16 小端     控制帧：a = v（前进量）；参数帧：a = 死区下限%
 *   [6][7]=b int16 小端     控制帧：b = w（转向量）；参数帧：b = 直行微调‰
 *   [8]=flags  bit0=刹车  bit2=急停
 *   [9]=前 9 字节异或
 *
 *   类型 0x01 = 控制帧（约 33Hz 连续发）
 *   类型 0x02 = 参数帧（调参时发一次，单片机立即生效，不产生运动）
 */
public final class CarProtocol {

    public static final int TYPE_CONTROL = 0x01;
    public static final int TYPE_CONFIG = 0x02;

    public static final int FLAG_BRAKE = 0x01;
    public static final int FLAG_ESTOP = 0x04;

    private static int sSeq = 0;

    private CarProtocol() { }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static byte[] build(int type, int a, int b, int flags) {
        byte[] f = new byte[10];

        f[0] = (byte) 0xA5;
        f[1] = (byte) 0x5A;
        f[2] = (byte) type;
        f[3] = (byte) (sSeq++ & 0xFF);

        a = clamp(a, -32000, 32000);
        b = clamp(b, -32000, 32000);

        f[4] = (byte) (a & 0xFF);
        f[5] = (byte) ((a >> 8) & 0xFF);
        f[6] = (byte) (b & 0xFF);
        f[7] = (byte) ((b >> 8) & 0xFF);
        f[8] = (byte) (flags & 0xFF);

        int chk = 0;
        for (int i = 0; i < 9; i++) {
            chk ^= (f[i] & 0xFF);
        }
        f[9] = (byte) (chk & 0xFF);

        return f;
    }

    /** 控制帧：v、w 都在 -1000~+1000 */
    public static byte[] frame(int v, int w, int flags) {
        return build(TYPE_CONTROL, clamp(v, -1000, 1000), clamp(w, -1000, 1000), flags);
    }

    /** 参数帧：dutyMin = 死区下限 20~90（%），trim = 直行微调 -300~300（‰） */
    public static byte[] config(int dutyMin, int trim) {
        return build(TYPE_CONFIG, clamp(dutyMin, 0, 100), clamp(trim, -500, 500), 0);
    }
}
