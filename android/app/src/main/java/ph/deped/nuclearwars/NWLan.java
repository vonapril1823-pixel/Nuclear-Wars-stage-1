package ph.deped.nuclearwars;

import android.content.Context;
import android.net.wifi.WifiManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Same-hotspot discovery and pairing for Nuclear Wars Versus.
 * UDP: a host broadcasts a small "lobby" note every second; other phones listen and show a list.
 * TCP: a joiner opens a line channel to the host to swap the WebRTC offer and answer, so nobody scans or pastes a code.
 * The match itself still runs over WebRTC in the page.
 */
@CapacitorPlugin(name = "NWLan")
public class NWLan extends Plugin {
    private DatagramSocket udp;
    private Thread udpThread;
    private ServerSocket server;
    private final Map<Integer, Socket> socks = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private WifiManager.MulticastLock lock;

    private void emitUdp(String ip, String text) {
        JSObject o = new JSObject(); o.put("ip", ip); o.put("text", text); notifyListeners("udp", o);
    }

    private void emitTcp(int id, String kind, String line, String ip) {
        JSObject o = new JSObject(); o.put("id", id); o.put("kind", kind); if (line != null) o.put("line", line); if (ip != null) o.put("ip", ip); notifyListeners("tcp", o);
    }

    @PluginMethod
    public void udpListen(PluginCall call) {
        final int port = call.getInt("port", 47321);
        try {
            if (udp != null) { call.resolve(); return; }
            WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { lock = wm.createMulticastLock("nwlan"); lock.setReferenceCounted(false); lock.acquire(); }
            udp = new DatagramSocket(null); udp.setReuseAddress(true); udp.setBroadcast(true); udp.bind(new java.net.InetSocketAddress(port));
            final DatagramSocket s = udp;
            udpThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (!s.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length); s.receive(p);
                        emitUdp(p.getAddress().getHostAddress(), new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    } catch (IOException e) { break; }
                }
            }, "nwlan-udp");
            udpThread.setDaemon(true); udpThread.start(); call.resolve();
        } catch (Exception e) { udp = null; call.reject("udp: " + e.getMessage()); }
    }

    @PluginMethod
    public void udpStop(PluginCall call) {
        try { if (udp != null) udp.close(); } catch (Exception ignored) {}
        udp = null; try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void udpBroadcast(PluginCall call) {
        final int port = call.getInt("port", 47321); final String text = call.getString("text", "");
        new Thread(() -> {
            byte[] data = text.getBytes(StandardCharsets.UTF_8); int sent = 0;
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress b = ia.getBroadcast(); if (b == null) continue;
                        try { s.send(new DatagramPacket(data, data.length, b, port)); sent++; } catch (IOException ignored) {}
                    }
                }
                if (sent == 0) { try { s.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), port)); sent++; } catch (IOException ignored) {} }
            } catch (Exception ignored) {}
            JSObject r = new JSObject(); r.put("sent", sent); call.resolve(r);
        }, "nwlan-bcast").start();
    }

    @PluginMethod
    public void tcpListen(PluginCall call) {
        final int port = call.getInt("port", 47322);
        try {
            if (server != null) { call.resolve(); return; }
            server = new ServerSocket(); server.setReuseAddress(true); server.bind(new java.net.InetSocketAddress(port));
            final ServerSocket ss = server;
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try { Socket c = ss.accept(); int id = nextId.getAndIncrement(); socks.put(id, c); emitTcp(id, "open", null, c.getInetAddress().getHostAddress()); read(id, c); }
                    catch (IOException e) { break; }
                }
            }, "nwlan-accept");
            t.setDaemon(true); t.start(); call.resolve();
        } catch (Exception e) { server = null; call.reject("tcp: " + e.getMessage()); }
    }

    @PluginMethod
    public void tcpStopListen(PluginCall call) {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null; call.resolve();
    }

    private void read(final int id, final Socket c) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) emitTcp(id, "line", line, null);
            } catch (IOException ignored) {}
            socks.remove(id); try { c.close(); } catch (IOException ignored) {}
            emitTcp(id, "close", null, null);
        }, "nwlan-read-" + id);
        t.setDaemon(true); t.start();
    }

    @PluginMethod
    public void tcpConnect(PluginCall call) {
        final String ip = call.getString("ip", ""); final int port = call.getInt("port", 47322);
        new Thread(() -> {
            try {
                Socket c = new Socket(); c.connect(new java.net.InetSocketAddress(ip, port), 4000); c.setTcpNoDelay(true);
                int id = nextId.getAndIncrement(); socks.put(id, c); JSObject r = new JSObject(); r.put("id", id); call.resolve(r); read(id, c);
            } catch (Exception e) { call.reject("connect: " + e.getMessage()); }
        }, "nwlan-connect").start();
    }

    @PluginMethod
    public void tcpSend(PluginCall call) {
        final int id = call.getInt("id", -1); final String line = call.getString("line", "");
        new Thread(() -> {
            Socket c = socks.get(id);
            if (c == null) { call.reject("closed"); return; }
            try { OutputStream o = c.getOutputStream(); o.write((line.replace("\n", " ") + "\n").getBytes(StandardCharsets.UTF_8)); o.flush(); call.resolve(); }
            catch (IOException e) { call.reject("send: " + e.getMessage()); }
        }, "nwlan-send").start();
    }

    @PluginMethod
    public void tcpClose(PluginCall call) {
        Socket c = socks.remove(call.getInt("id", -1)); if (c != null) { try { c.close(); } catch (IOException ignored) {} }
        call.resolve();
    }

    @PluginMethod
    public void stopAll(PluginCall call) {
        for (Socket c : socks.values()) { try { c.close(); } catch (IOException ignored) {} } socks.clear();
        try { if (server != null) server.close(); } catch (Exception ignored) {} server = null;
        try { if (udp != null) udp.close(); } catch (Exception ignored) {} udp = null;
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        call.resolve();
    }
}
