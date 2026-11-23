package com.example.carcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * HC-05 经典蓝牙 SPP 客户端。
 *
 * 设计要点：
 *   1. connect() 会阻塞，必须在后台线程调用；
 *   2. 发送用独立线程，30Hz，永远只发"最新的"一组值（丢旧帧，不排队），
 *      这样即使某次发送变慢，操作也不会越积越延迟；
 *   3. 断线/异常统一走 stop()，主界面负责在断开时补发停车帧。
 */
public class BtSppClient {

    /** 经典蓝牙串口（SPP）标准 UUID，HC-05/06 都用这个 */
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface Listener {
        void onState(boolean connected, String message);
    }

    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean running;

    private volatile int pendingV;
    private volatile int pendingW;
    private volatile int pendingFlags;
    private volatile boolean dirty;

    private Thread txThread;

    public boolean isConnected() {
        return running && out != null;
    }

    public void connectAsync(final BluetoothDevice device, final Listener listener) {
        stop();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothSocket s =
                            device.createRfcommSocketToServiceRecord(SPP_UUID);
                    s.connect();
                    socket = s;
                    out = s.getOutputStream();
                    running = true;
                    startTxLoop();
                    listener.onState(true, "已连接：" + device.getName());
                } catch (Exception e) {
                    stop();
                    listener.onState(false, "连接失败：" + e.getMessage());
                }
            }
        }, "bt-connect").start();
    }

    /** 只记录"最新要发的东西"，真正发送在发送线程里做 */
    public void send(int v, int w, int flags) {
        pendingV = v;
        pendingW = w;
        pendingFlags = flags;
        dirty = true;
    }

    private void startTxLoop() {
        txThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        if (dirty) {
                            dirty = false;
                            byte[] f = CarProtocol.frame(pendingV, pendingW, pendingFlags);
                            OutputStream o = out;
                            if (o != null) {
                                o.write(f);
                                o.flush();
                            }
                        }
                        Thread.sleep(30);           // 约 33Hz
                    } catch (InterruptedException ie) {
                        break;
                    } catch (IOException ioe) {
                        running = false;            // 写失败 = 链路断了
                        break;
                    }
                }
            }
        }, "bt-tx");
        txThread.start();
    }

    public void stop() {
        running = false;
        dirty = false;

        Thread t = txThread;
        txThread = null;
        if (t != null) t.interrupt();

        OutputStream o = out;
        out = null;
        if (o != null) {
            try { o.close(); } catch (Exception ignored) { }
        }

        BluetoothSocket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }
}
