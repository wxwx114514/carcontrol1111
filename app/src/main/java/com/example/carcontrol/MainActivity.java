package com.example.carcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.SharedPreferences;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 主界面（全程序化创建 UI，无第三方库）
 *
 *   ┌────────────────────────────────────────────────┐
 *   │ 状态          [连接] [急停] [回正] [调参]        │
 *   ├───────────────────────┬────────────────────────┤
 *   │      方向盘            │       前后滑块          │
 *   └───────────────────────┴────────────────────────┘
 *
 * 调参（保存到 SharedPreferences，下次连上自动下发）：
 *   · 死区下限：20%~90%（低于它电机推不动的占空比）
 *   · 直行微调：-300~+300‰（抵消两轮转速差）
 *
 * 急停：点一次即锁定，再点无效；解除只能断开重连（或重启 App）。
 */
public class MainActivity extends Activity implements BtSppClient.Listener {

    private static final int REQ_BT_PERM = 1001;

    private static final String PREFS = "car_cfg";
    private static final String K_DUTY = "duty_min";
    private static final String K_TRIM = "trim";

    private final BtSppClient bt = new BtSppClient();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SteeringWheelView wheel;
    private TickSliderView slider;
    private TextView status;
    private Button btnConnect;
    private Button btnEstop;
    private Button btnCfg;

    private boolean estopPulse = false;         /* 急停脉冲：点一次 → 刹停 → 自动解除 */
    private long    estopUntil = 0L;
    private static final long ESTOP_MS = 150L;  /* 急停持续时间（33Hz 下约 5 帧） */

    private boolean txRunning = false;
    private boolean pendingConnect = false;

    private int dutyMin = 40;        /* 死区下限 % */
    private int trim = 0;            /* 直行微调 ‰ */

    /** 33ms 一次，把最新状态交给发送线程 */
    private final Runnable txTask = new Runnable() {
        @Override
        public void run() {
            if (!bt.isConnected()) {
                txRunning = false;
                return;
            }
            boolean estopNow = estopPulse;
            if (estopNow && System.currentTimeMillis() >= estopUntil) {
                estopPulse = false;                 /* 到时间自动解除 */
                estopNow = false;
            }

            int v = estopNow ? 0 : slider.getValue();
            int w = estopNow ? 0 : wheel.getW();
            int flags = estopNow
                    ? (CarProtocol.FLAG_ESTOP | CarProtocol.FLAG_BRAKE)
                    : 0;
            bt.offerMotion(CarProtocol.frame(v, w, flags));
            updateStatus();
            ui.postDelayed(this, 33);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        loadConfig();
        setContentView(buildUi());
        requestBtPermissionIfNeeded();
    }

    // ===================== 界面 =====================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF102027);
        root.setPadding(dp(8), dp(6), dp(8), dp(6));

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
                onEstopClicked();
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

        btnCfg = makeButton("调参", 0xFF00695C);
        btnCfg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfigDialog();
            }
        });
        bar.addView(btnCfg);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

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
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ===================== 调参 =====================

    private void loadConfig() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        dutyMin = sp.getInt(K_DUTY, 40);
        trim = sp.getInt(K_TRIM, 0);
        /* 防脏数据：超出范围就夹回来，避免 SeekBar 进度为负 */
        if (dutyMin < 20) dutyMin = 20;
        if (dutyMin > 90) dutyMin = 90;
        if (trim < -300) trim = -300;
        if (trim > 300) trim = 300;
    }

    private void saveConfig() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit().putInt(K_DUTY, dutyMin).putInt(K_TRIM, trim).apply();
    }

    /** 把当前参数发给单片机（0x02 参数帧） */
    private void sendConfig() {
        bt.offerConfig(CarProtocol.config(dutyMin, trim));
    }

    private void showConfigDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(14), dp(24), dp(6));

        final TextView tvDuty = new TextView(this);
        tvDuty.setTextColor(Color.WHITE);
        tvDuty.setTextSize(15f);
        final TextView tvTrim = new TextView(this);
        tvTrim.setTextColor(Color.WHITE);
        tvTrim.setTextSize(15f);

        final SeekBar sbDuty = new SeekBar(this);
        sbDuty.setMax(14);                              /* 20 + 5*14 = 90 */
        sbDuty.setProgress((dutyMin - 20) / 5);

        final SeekBar sbTrim = new SeekBar(this);
        sbTrim.setMax(60);                              /* -300 + 10*60 = 300 */
        sbTrim.setProgress((trim + 300) / 10);

        final int[] picked = new int[]{dutyMin, trim};

        tvDuty.setText("死区下限（起转占空比）：" + picked[0] + " %");
        tvTrim.setText("直行微调： " + picked[1] + " ‰");

        sbDuty.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                picked[0] = 20 + p * 5;
                tvDuty.setText("死区下限（起转占空比）：" + picked[0] + " %");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        sbTrim.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                picked[1] = -300 + p * 10;
                tvTrim.setText("直行微调： " + picked[1] + " ‰");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        box.addView(tvDuty);
        box.addView(sbDuty);
        box.addView(tvTrim);
        box.addView(sbTrim);

        TextView tip = new TextView(this);
        tip.setTextColor(0xFFB0BEC5);
        tip.setTextSize(12f);
        tip.setText("死区越小越灵敏，但太小会推不动；\n"
                + "车往左偏就把微调往右拉，往右偏就往左拉。");
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("调参（保存后下次自动下发）")
                .setView(box)
                .setPositiveButton("保存并下发", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        dutyMin = picked[0];
                        trim = picked[1];
                        saveConfig();
                        if (bt.isConnected()) sendConfig();
                        toast("已保存：" + dutyMin + "% / " + trim + "‰");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===================== 急停（点一次刹停，自动解除） =====================

    private void onEstopClicked() {
        estopPulse = true;
        estopUntil = System.currentTimeMillis() + ESTOP_MS;

        /* 顺手把两个控制都清零，避免急停结束后车按旧速度继续冲 */
        slider.setValue(0);
        wheel.recenter();

        updateStatus();
        toast("急停！已自动解除");
    }

    private void clearEstop() {
        estopPulse = false;
        estopUntil = 0L;
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
            clearEstop();
            status.setText("已断开");
            btnConnect.setText("连接");
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
            toast("还没有配对设备：请先在系统设置里配对 HC-05（PIN 1234）");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择已配对的 HC-05")
                .setItems(names.toArray(new String[0]),
                        new DialogInterface.OnClickListener() {
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
                    clearEstop();               /* 新的一次连接 = 解除急停 */
                    btnConnect.setText("断开");
                    btnCfg.setEnabled(true);
                    startTxLoop();
                    sendConfig();               /* 连上先下发保存好的参数 */
                } else {
                    btnConnect.setText("连接");
                    stopTxLoop();
                    clearEstop();
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
        if (bt.isConnected()) {
            bt.offerMotion(CarProtocol.frame(0, 0,
                    CarProtocol.FLAG_BRAKE | CarProtocol.FLAG_ESTOP));
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
        String s = "已连接  V=" + (estopPulse ? 0 : slider.getValue())
                + "  W=" + (estopPulse ? 0 : wheel.getW())
                + "  [死区 " + dutyMin + "% 微调 " + trim + "‰]"
                + (estopPulse ? "  急停中" : "");
        status.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
