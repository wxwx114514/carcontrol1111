package com.example.carcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 主界面（全程序化创建 UI，不需要 XML 布局，也不需要第三方库）
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ 状态文字          [连接] [急停] [回正]        │
 *   ├───────────────────────┬──────────────────────┤
 *   │      方向盘            │      前后滑块         │
 *   │  （不自动回正 + 0° 吸附）│ （保持位置 + 刻度）    │
 *   └───────────────────────┴──────────────────────┘
 */
public class MainActivity extends Activity implements BtSppClient.Listener {

    private static final int REQ_BT_PERM = 1001;

    private final BtSppClient bt = new BtSppClient();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SteeringWheelView wheel;
    private TickSliderView slider;
    private TextView status;
    private Button btnConnect;
    private Button btnEstop;

    private boolean estop = false;
    private boolean txRunning = false;
    private boolean pendingConnect = false;

    /** 33ms 一次，往发送线程塞最新值 */
    private final Runnable txTask = new Runnable() {
        @Override
        public void run() {
            if (!bt.isConnected()) {
                txRunning = false;
                return;
            }
            int v = estop ? 0 : slider.getValue();
            int w = estop ? 0 : wheel.getW();
            int flags = estop ? CarProtocol.FLAG_ESTOP : 0;
            bt.send(v, w, flags);
            updateStatus();
            ui.postDelayed(this, 33);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(buildUi());

        requestBtPermissionIfNeeded();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF102027);
        root.setPadding(dp(8), dp(6), dp(8), dp(6));

        // ---------- 顶栏 ----------
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14f);
        status.setText("未连接");
        bar.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnConnect = makeButton("连接", 0xFF1565C0);
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onConnectClicked();
            }
        });
        bar.addView(btnConnect);

        btnEstop = makeButton("急停", 0xFFC62828);
        btnEstop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                estop = !estop;
                btnEstop.setText(estop ? "解除急停" : "急停");
                if (estop) {
                    slider.setValue(0);
                    wheel.recenter();
                }
                updateStatus();
            }
        });
        bar.addView(btnEstop);

        Button btnCenter = makeButton("回正", 0xFF455A64);
        btnCenter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wheel.recenter();
                updateStatus();
            }
        });
        bar.addView(btnCenter);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 主体：方向盘 + 滑块 ----------
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, dp(6), 0, 0);

        wheel = new SteeringWheelView(this);
        body.addView(wheel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        slider = new TickSliderView(this);
        body.addView(slider, new LinearLayout.LayoutParams(dp(170),
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ===================== 蓝牙 =====================

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBtPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && !hasBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_BT_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERM) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingConnect) {
                    pendingConnect = false;
                    showDeviceDialog();
                }
            } else {
                toast("没有蓝牙权限，无法连接");
            }
        }
    }

    private void onConnectClicked() {
        if (bt.isConnected()) {
            bt.stop();
            stopTxLoop();
            estop = false;
            btnEstop.setText("急停");
            status.setText("已断开");
            return;
        }
        if (!hasBtPermission()) {
            pendingConnect = true;
            requestBtPermissionIfNeeded();
            return;
        }
        showDeviceDialog();
    }

    private void showDeviceDialog() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            toast("这台设备不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            toast("请先在系统设置里打开蓝牙");
            return;
        }

        final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        final List<String> names = new ArrayList<String>();

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    devices.add(d);
                    names.add(d.getName() + "\n" + d.getAddress());
                }
            }
        } catch (SecurityException se) {
            toast("缺少蓝牙权限");
            return;
        }

        if (devices.isEmpty()) {
            toast("还没有配对设备：请先在系统设置里配对 HC-05（PIN 1234 或 0000）");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择已配对的 HC-05")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        status.setText("正在连接…");
                        bt.connectAsync(devices.get(which), MainActivity.this);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onState(final boolean connected, final String message) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                status.setText(message);
                if (connected) {
                    estop = false;
                    btnEstop.setText("急停");
                    btnConnect.setText("断开");
                    startTxLoop();
                } else {
                    btnConnect.setText("连接");
                    stopTxLoop();
                    // 兜底：连不上/断线时确保车是停的
                    bt.send(0, 0, CarProtocol.FLAG_BRAKE);
                }
            }
        });
    }

    private void startTxLoop() {
        if (txRunning) return;
        txRunning = true;
        ui.post(txTask);
    }

    private void stopTxLoop() {
        txRunning = false;
        ui.removeCallbacks(txTask);
    }

    // ===================== 生命周期 =====================

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台立刻停车：先塞一帧"零速+刹车"，再停止推送；
        // 真出问题还有单片机那边的 300ms 掉线保护兜底
        if (bt.isConnected()) {
            bt.send(0, 0, CarProtocol.FLAG_BRAKE);
        }
        stopTxLoop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bt.isConnected()) startTxLoop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTxLoop();
        bt.stop();
    }

    private void updateStatus() {
        if (!bt.isConnected()) return;
        String s = "已连接  V=" + (estop ? 0 : slider.getValue())
                + "  W=" + (estop ? 0 : wheel.getW())
                + (estop ? "  [急停]" : "");
        status.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
