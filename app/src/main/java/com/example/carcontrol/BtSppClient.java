package com.example.carcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * HC-05 经典蓝牙 SPP 客户端。
 *
 * 发送策略：
 *   · 控制帧：永远只保留"最新一帧"，旧的直接丢（不排队，避免延迟累积）
 *   · 参数帧：优先发送，插在控制帧前面，保证调参一定能挤进去
 *   · 独立线程 30ms 一轮，绝不阻塞 UI
 */
public class BtSppClient {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface Listener {
        void onState(boolean connected, String message);
    }

    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean running;

    private volatile byte[] pendingMotion;
    private volatile boolean motionDirty;

    private volatile byte[] pendingConfig;
    private volatile boolean configDirty;

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

    /** 控制帧：只保留最新一帧 */
    public void offerMotion(byte[] frame) {
        pendingMotion = frame;
        motionDirty = true;
    }

    /** 参数帧：优先发一次 */
    public void offerConfig(byte[] frame) {
        pendingConfig = frame;
        configDirty = true;
    }

    private void startTxLoop() {
        txThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        byte[] f = null;
                        if (configDirty) {
                            f = pendingConfig;
                            configDirty = false;
                        } else if (motionDirty) {
                            f = pendingMotion;
                            motionDirty = false;
                        }
                        if (f != null) {
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
        motionDirty = false;
        configDirty = false;

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
