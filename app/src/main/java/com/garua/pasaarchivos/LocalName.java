package com.garua.pasaarchivos;

import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Anuncia en la red local el nombre "micel.local" (mDNS) para que la PC encuentre
 * al celular aunque su dirección numérica cambie. Solo responde preguntas por ese nombre.
 */
final class LocalName {
    private static final int PORT = 5353;
    private final String host;      // por ejemplo "cel-k7p2.local", distinto en cada celular
    private final byte[] NAME;

    LocalName(String label) {
        host = label + ".local";
        byte[] l = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        NAME = new byte[l.length + 8];
        NAME[0] = (byte) l.length;
        System.arraycopy(l, 0, NAME, 1, l.length);
        byte[] tail = { 5, 'l', 'o', 'c', 'a', 'l', 0 };
        System.arraycopy(tail, 0, NAME, l.length + 1, tail.length);
    }

    String host() { return host; }

    private MulticastSocket sock;
    private volatile boolean running;
    private InetAddress group;

    boolean start() {
        try {
            group = InetAddress.getByName("224.0.0.251");
            sock = new MulticastSocket(PORT);
            sock.setTimeToLive(255);
            boolean joined = false;
            for (NetworkInterface ni : lanInterfaces()) {
                try { sock.joinGroup(new InetSocketAddress(group, PORT), ni); joined = true; } catch (Exception ignored) {}
            }
            if (!joined) { sock.close(); return false; }
        } catch (Exception e) {
            return false;
        }
        running = true;
        Thread t = new Thread(new Runnable() { @Override public void run() { loop(); } }, "pa-mdns");
        t.setDaemon(true);
        t.start();
        return true;
    }

    void stop() {
        running = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
    }

    private void loop() {
        byte[] buf = new byte[1500];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                handle(p);
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void handle(DatagramPacket p) throws Exception {
        byte[] b = p.getData();
        int len = p.getLength();
        if (len < 12) return;
        int flags = u16(b, 2);
        if ((flags & 0x8000) != 0) return;               // es una respuesta, no una pregunta
        int qd = u16(b, 4);
        int off = 12, qtype = 0;
        boolean match = false;
        for (int i = 0; i < qd && off < len; i++) {
            StringBuilder name = new StringBuilder();
            off = readName(b, len, off, name);
            if (off < 0 || off + 4 > len) return;
            int t = u16(b, off);
            off += 4;
            if (name.toString().equalsIgnoreCase(host) && (t == 1 || t == 28 || t == 255)) { match = true; qtype = t; }
        }
        if (!match) return;
        InetAddress from = p.getAddress();
        byte[] ip = pickAddress(from);
        if (ip == null) return;

        // Respuesta directa al que preguntó.
        boolean legacy = p.getPort() != PORT;
        byte[] uni = answer(legacy ? u16(b, 0) : 0, ip, legacy, qtype);
        try { sock.send(new DatagramPacket(uni, uni.length, from, p.getPort())); } catch (Exception ignored) {}
        // Respuesta general a la red (lo normal en mDNS).
        byte[] multi = answer(0, ip, false, qtype);
        for (NetworkInterface ni : lanInterfaces()) {
            try {
                sock.setNetworkInterface(ni);
                sock.send(new DatagramPacket(multi, multi.length, group, PORT));
            } catch (Exception ignored) {}
        }
    }

    private byte[] answer(int id, byte[] ip, boolean legacy, int qtype) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        w16(o, id); w16(o, 0x8400);
        w16(o, legacy ? 1 : 0); w16(o, 2); w16(o, 0); w16(o, 0);
        if (legacy) { o.write(NAME, 0, NAME.length); w16(o, qtype); w16(o, 1); }
        int cls = legacy ? 0x0001 : 0x8001;
        int ttl = legacy ? 10 : 120;
        // Registro A: nombre -> dirección
        o.write(NAME, 0, NAME.length); w16(o, 1); w16(o, cls); w16(o, 0); w16(o, ttl); w16(o, 4); o.write(ip, 0, 4);
        // Registro NSEC: "solo existe dirección IPv4", para que la PC no espere una IPv6
        o.write(NAME, 0, NAME.length); w16(o, 47); w16(o, cls); w16(o, 0); w16(o, ttl); w16(o, NAME.length + 3);
        o.write(NAME, 0, NAME.length); o.write(0); o.write(1); o.write(0x40);
        return o.toByteArray();
    }

    private static int readName(byte[] b, int len, int off, StringBuilder out) {
        int jumps = 0, ret = -1, pos = off;
        while (pos < len) {
            int l = b[pos] & 0xff;
            if (l == 0) { pos++; return ret >= 0 ? ret : pos; }
            if ((l & 0xC0) == 0xC0) {
                if (pos + 1 >= len || ++jumps > 5) return -1;
                if (ret < 0) ret = pos + 2;
                pos = ((l & 0x3f) << 8) | (b[pos + 1] & 0xff);
                continue;
            }
            if (pos + 1 + l > len) return -1;
            if (out.length() > 0) out.append('.');
            out.append(new String(b, pos + 1, l, java.nio.charset.StandardCharsets.US_ASCII));
            pos += 1 + l;
        }
        return -1;
    }

    /** Solo interfaces de red local (wifi, zona wifi, cable); se excluyen los datos móviles. */
    private static boolean isLan(String n) {
        n = n.toLowerCase();
        return n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap")
            || n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("bt-pan");
    }

    static List<NetworkInterface> lanInterfaces() {
        List<NetworkInterface> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !isLan(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) { out.add(ni); break; }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Elige la dirección del celular que está en la misma red que quien pregunta. */
    private static byte[] pickAddress(InetAddress from) {
        byte[] first = null;
        byte[] f = from.getAddress();
        for (NetworkInterface ni : lanInterfaces()) {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                InetAddress a = ia.getAddress();
                if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                byte[] mine = a.getAddress();
                if (first == null) first = mine;
                int prefix = ia.getNetworkPrefixLength();
                if (f.length == 4 && prefix > 0 && prefix <= 32 && sameNet(mine, f, prefix)) return mine;
            }
        }
        return first;
    }

    private static boolean sameNet(byte[] a, byte[] b, int prefix) {
        int ia = ((a[0] & 0xff) << 24) | ((a[1] & 0xff) << 16) | ((a[2] & 0xff) << 8) | (a[3] & 0xff);
        int ib = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        int mask = prefix == 32 ? -1 : ~(-1 >>> prefix);
        return (ia & mask) == (ib & mask);
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static void w16(java.io.ByteArrayOutputStream o, int v) { o.write((v >> 8) & 0xff); o.write(v & 0xff); }
}
