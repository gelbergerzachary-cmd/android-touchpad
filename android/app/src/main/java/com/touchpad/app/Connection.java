package com.touchpad.app;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** UDP link to the Mac server. All socket work happens off the UI thread. */
public class Connection {
    public interface Listener {
        void onStatus(boolean connected, String message);
        void onDiscovered(String host, String name);
    }

    static final int PORT = 50505;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread sendThread;
    private Handler sender;
    private volatile DatagramSocket socket;
    private volatile InetAddress target;
    private volatile String pin = "";
    private volatile long lastOk;
    private volatile String serverName = "";
    private volatile String error;
    private boolean lastReportedConnected;
    private String lastReportedMessage;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (target != null) sendRaw("HELLO " + pin, target);
            reportStatus();
            sender.postDelayed(this, 1000);
        }
    };

    public Connection(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        sendThread = new HandlerThread("touchpad-net");
        sendThread.start();
        sender = new Handler(sendThread.getLooper());
        sender.post(() -> {
            try {
                DatagramSocket s = new DatagramSocket();
                s.setBroadcast(true);
                socket = s;
                Thread rx = new Thread(() -> receiveLoop(s), "touchpad-rx");
                rx.setDaemon(true);
                rx.start();
            } catch (Exception e) {
                error = "Network error: " + e.getMessage();
            }
            heartbeat.run();
        });
    }

    public void stop() {
        if (sender == null) return;
        sender.removeCallbacksAndMessages(null);
        DatagramSocket s = socket;
        InetAddress t = target;
        sender.post(() -> {
            if (t != null && isConnected()) sendRaw("BYE", t);
            if (s != null) s.close();
        });
        sendThread.quitSafely();
        socket = null;
        sender = null;
        lastOk = 0;
    }

    public void setTarget(String host, String newPin) {
        pin = newPin == null ? "" : newPin.trim();
        lastOk = 0;
        error = null;
        if (sender == null) return;
        sender.post(() -> {
            try {
                target = (host == null || host.trim().isEmpty()) ? null : InetAddress.getByName(host.trim());
            } catch (Exception e) {
                target = null;
                error = "Can't find " + host;
            }
            heartbeat.run();
        });
    }

    public boolean isConnected() {
        return SystemClock.uptimeMillis() - lastOk < 3500;
    }

    public void send(String msg) {
        InetAddress t = target;
        Handler h = sender;
        if (t == null || h == null || !isConnected()) return;
        h.post(() -> sendRaw(msg, t));
    }

    public void discover() {
        if (sender == null) return;
        sender.post(() -> {
            sendRaw("DISCOVER", broadcast("255.255.255.255"));
            try {
                WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                DhcpInfo dhcp = wifi == null ? null : wifi.getDhcpInfo();
                if (dhcp != null && dhcp.ipAddress != 0) {
                    int b = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                    byte[] q = new byte[4];
                    for (int k = 0; k < 4; k++) q[k] = (byte) ((b >> (k * 8)) & 0xFF);
                    sendRaw("DISCOVER", InetAddress.getByAddress(q));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private InetAddress broadcast(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendRaw(String msg, InetAddress to) {
        DatagramSocket s = socket;
        if (s == null || to == null) return;
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(b, b.length, to, PORT));
        } catch (Exception e) {
            error = "Network error: " + e.getMessage();
        }
    }

    private void receiveLoop(DatagramSocket s) {
        byte[] buf = new byte[1024];
        while (!s.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                if (msg.startsWith("OK")) {
                    serverName = msg.length() > 3 ? msg.substring(3) : "Mac";
                    lastOk = SystemClock.uptimeMillis();
                    error = null;
                    reportStatus();
                } else if (msg.equals("BAD")) {
                    lastOk = 0;
                    error = "Wrong PIN";
                    reportStatus();
                } else if (msg.startsWith("TOUCHPAD ")) {
                    String host = p.getAddress().getHostAddress();
                    String name = msg.substring(9);
                    main.post(() -> listener.onDiscovered(host, name));
                }
            } catch (Exception e) {
                if (s.isClosed()) return;
            }
        }
    }

    private synchronized void reportStatus() {
        boolean c = isConnected();
        String m;
        if (c) m = "Connected to " + serverName;
        else if (error != null) m = error;
        else if (target == null) m = "Not set up — tap here";
        else m = "Connecting…";
        if (c == lastReportedConnected && m.equals(lastReportedMessage)) return;
        lastReportedConnected = c;
        lastReportedMessage = m;
        main.post(() -> listener.onStatus(c, m));
    }
}
