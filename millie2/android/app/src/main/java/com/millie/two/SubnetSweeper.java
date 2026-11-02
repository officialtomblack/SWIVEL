package com.millie.two;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** ACTIVE local-subnet sweep (manual, user-triggered): enumerate the live IPv4 hosts on the Wi-Fi the phone is
 *  currently joined to, and best-effort map each to its MAC via the kernel neighbour table. Passive scanning (the
 *  rest of the app) sees layer-2 MACs/SSIDs; IP is layer-3, so the only way to learn a neighbour's IP is to actively
 *  touch the subnet. This runs ONLY when the operator taps SWEEP, against the network they are on.
 *
 *  Host enumeration: a multithreaded TCP connect-scan across the /24. A successful accept OR a 'connection refused'
 *  (RST) both prove the host is up; only an all-ports timeout is treated as dead. This needs no root and works on
 *  every API 26-35 (InetAddress.isReachable is NOT used: on non-root Android it is a TCP-port-7 probe, redundant here).
 *
 *  IP->MAC: an in-process NETLINK_ROUTE socket dumping RTM_GETNEIGH (the modern replacement for /proc/net/arp, which
 *  SELinux blocks on Android 10+). No root, no CAP_NET_RAW. The kernel grants the dump to the base app domain on
 *  Android 8-12 (this phone); on Android 13-15 the untrusted_app domain is denied unless targetSdk<=31 — so any denial
 *  is caught and MACs simply stay "" . The connect-scan populates the neighbour cache first, so entries are fresh. */
final class SubnetSweeper {
    static final int[] PORTS = {80, 443, 22, 139, 445, 8080, 53, 62078};
    static final int THREADS = 64, TIMEOUT_MS = 300, MAX_PREFIX_HOSTS = 1024;  // cap the sweep at a /22 worth of hosts

    /** The phone's own network context (#1) plus the live hosts found (#3). */
    static final class Result {
        String ssid = "", bssid = "", selfIp = "", gateway = "", dns = "";
        int prefix = 0;
        final List<Host> hosts = new ArrayList<>();
    }
    static final class Host {
        final String ip; String mac = "";
        Host(String ip) { this.ip = ip; }
    }

    /** The phone's own network: SSID/BSSID from WifiManager, IPv4/gateway/DNS/prefix from LinkProperties
     *  (DhcpInfo.netmask is 0/unreliable on modern Android, so LinkProperties is authoritative). No sweep. */
    static Result ownNetwork(Context ctx) {
        Result r = new Result();
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    String s = wi.getSSID();
                    if (s != null && !s.equals("<unknown ssid>")) r.ssid = s.replace("\"", "");
                    if (wi.getBSSID() != null) r.bssid = wi.getBSSID().toUpperCase(Locale.US);
                }
            }
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) { r.selfIp = a.getHostAddress(); r.prefix = la.getPrefixLength(); }
                }
                for (InetAddress d : lp.getDnsServers()) if (d instanceof Inet4Address) { r.dns = d.getHostAddress(); break; }
                for (RouteInfo ri : lp.getRoutes())
                    if (ri.isDefaultRoute() && ri.getGateway() instanceof Inet4Address) { r.gateway = ri.getGateway().getHostAddress(); break; }
            }
        } catch (Exception ignored) {}
        return r;
    }

    /** Full sweep: own network + a connect-scan of the joined /24 (clamped), with best-effort IP->MAC. */
    static Result sweep(Context ctx) {
        Result res = ownNetwork(ctx);
        if (res.selfIp.isEmpty()) return res;              // not on IPv4 Wi-Fi -> nothing to sweep
        int ip, mask, netw, bcast;
        try {
            ip = ByteBuffer.wrap(((Inet4Address) InetAddress.getByName(res.selfIp)).getAddress()).getInt();
        } catch (Exception e) { return res; }
        int p = Math.max(res.prefix <= 0 ? 24 : res.prefix, 24);   // never sweep wider than the phone's own /24
        mask = p >= 32 ? 0xFFFFFFFF : (0xFFFFFFFF << (32 - p));
        netw = ip & mask; bcast = netw | ~mask;
        long span = (bcast & 0xFFFFFFFFL) - (netw & 0xFFFFFFFFL) - 1;
        if (span <= 0 || span > MAX_PREFIX_HOSTS) return res;

        Set<String> live = Collections.synchronizedSet(new TreeSet<>());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> jobs = new ArrayList<>();
        for (int h = netw + 1; (h & 0xFFFFFFFFL) < (bcast & 0xFFFFFFFFL); h++) {
            final int host = h;
            jobs.add(pool.submit(() -> probe(host, live)));
        }
        for (Future<?> f : jobs) { try { f.get(); } catch (Exception ignored) {} }
        pool.shutdownNow();

        Map<String, String> arp = neighbours();            // IP->MAC; empty if the kernel/SELinux denies the dump
        for (String addr : live) { Host h = new Host(addr); String m = arp.get(addr); if (m != null) h.mac = m; res.hosts.add(h); }
        return res;
    }

    /** One host: accept OR RST (ConnectException) => alive; all-ports timeout => skip. */
    static void probe(int hostInt, Set<String> live) {
        String ip;
        try { ip = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(hostInt).array()).getHostAddress(); }
        catch (Exception e) { return; }
        for (int port : PORTS) {
            try (Socket s = new Socket()) { s.connect(new InetSocketAddress(ip, port), TIMEOUT_MS); live.add(ip); return; }
            catch (java.net.ConnectException ce) { live.add(ip); return; }     // RST: host up, port closed
            catch (java.net.SocketTimeoutException te) { /* filtered: try the next port */ }
            catch (Exception ignored) { /* unreachable/other: try the next port */ }
        }
    }

    // ---- netlink RTM_GETNEIGH: read the kernel neighbour table in-process (no root). Any failure -> empty map. ----
    static final int NETLINK_ROUTE = 0, RTM_GETNEIGH = 30, RTM_NEWNEIGH = 28, NLMSG_DONE = 3;
    static final int NLM_F_REQUEST = 0x01, NLM_F_DUMP = 0x300;                 // NLM_F_ROOT(0x100)|NLM_F_MATCH(0x200)
    static final int NDA_DST = 1, NDA_LLADDR = 2;
    static final int NUD_USABLE = 0x02 | 0x04 | 0x08 | 0x10 | 0x80;            // REACHABLE|STALE|DELAY|PROBE|PERMANENT

    static Map<String, String> neighbours() {
        Map<String, String> out = new HashMap<>();
        FileDescriptor fd = null;
        try {
            fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_DGRAM, NETLINK_ROUTE);
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(2000));
            ByteBuffer req = ByteBuffer.allocate(28).order(ByteOrder.nativeOrder());   // nlmsghdr(16) + ndmsg(12)
            req.putInt(28);                                   // nlmsg_len
            req.putShort((short) RTM_GETNEIGH);               // nlmsg_type
            req.putShort((short) (NLM_F_REQUEST | NLM_F_DUMP));// nlmsg_flags
            req.putInt(1);                                    // nlmsg_seq
            req.putInt(0);                                    // nlmsg_pid (kernel auto-assigns)
            req.put((byte) OsConstants.AF_INET);              // ndm_family
            req.put((byte) 0); req.putShort((short) 0);       // pad
            req.putInt(0);                                    // ndm_ifindex (0 = all)
            req.putShort((short) 0); req.put((byte) 0); req.put((byte) 0);  // state/flags/type
            req.flip();
            Os.write(fd, req);
            for (int guard = 0; guard < 128; guard++) {
                ByteBuffer buf = ByteBuffer.allocate(16384).order(ByteOrder.nativeOrder());
                int n;
                try { n = Os.read(fd, buf); } catch (ErrnoException e) { break; }     // EAGAIN (timeout) or error
                if (n <= 0) break;
                if (parse(buf, n, out)) break;                // NLMSG_DONE
            }
        } catch (Throwable t) {
            // socket denied (SELinux on Android 13-15 base domain), unsupported, or parse error -> no MACs
        } finally {
            if (fd != null) try { Os.close(fd); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Parse all netlink messages in buf[0,limit); fill ip->mac. Returns true if NLMSG_DONE was seen. */
    static boolean parse(ByteBuffer buf, int limit, Map<String, String> out) {
        int pos = 0;
        while (pos + 16 <= limit) {
            int len = buf.getInt(pos);
            int type = buf.getShort(pos + 4) & 0xFFFF;
            if (len < 16 || pos + len > limit) break;
            if (type == NLMSG_DONE) return true;
            if (type == RTM_NEWNEIGH) {
                int state = buf.getShort(pos + 24) & 0xFFFF;              // ndm_state: nlmsghdr(16)+ndmsg offset 8
                String ip = null, mac = null;
                int ap = pos + 28;                                        // attrs: after nlmsghdr(16)+ndmsg(12)
                while (ap + 4 <= pos + len) {
                    int rlen = buf.getShort(ap) & 0xFFFF;
                    int rtype = buf.getShort(ap + 2) & 0xFFFF;
                    if (rlen < 4 || ap + rlen > pos + len) break;
                    int off = ap + 4, dlen = rlen - 4;
                    if (rtype == NDA_DST && dlen == 4)
                        ip = (buf.get(off) & 255) + "." + (buf.get(off + 1) & 255) + "." + (buf.get(off + 2) & 255) + "." + (buf.get(off + 3) & 255);
                    else if (rtype == NDA_LLADDR && dlen == 6)
                        mac = String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                                buf.get(off) & 255, buf.get(off + 1) & 255, buf.get(off + 2) & 255,
                                buf.get(off + 3) & 255, buf.get(off + 4) & 255, buf.get(off + 5) & 255);
                    ap += (rlen + 3) & ~3;                                // RTA_ALIGN
                }
                if (ip != null && mac != null && (state & NUD_USABLE) != 0) out.put(ip, mac);
            }
            pos += (len + 3) & ~3;                                        // NLMSG_ALIGN
        }
        return false;
    }
}
