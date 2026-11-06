package com.termux.cloud;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mesh-only bind for the mesh sshd (build.json::mesh_sshd.mesh_only). With the flag ON, which is the default, the sshd listens on
 * the WireGuard mesh's own addresses and on loopback, never on 0.0.0.0 or :: (every interface, Wi-Fi, LAN and cellular included).
 *
 * The mesh addresses are resolved when the sshd starts, from the interfaces the phone has NOW: an interface whose NAME matches the
 * declared pattern (the VPN tun, never wlan or rmnet, because a home LAN is often 10.0.0.x too) and whose address lies in a declared
 * mesh CIDR. The tun address belongs to the WG profile and changes with it, which is why nothing is written down. No mesh address
 * means no bind: {@link #binds} returns null and the caller must FAIL CLOSED (not start, and end a running sshd), never fall back to
 * the wildcard. Loopback stays in the list: it is not reachable from any network, and the local clients (the jump helpers, the
 * watchdog, MyTerminal) dial 127.0.0.1.
 *
 * Pure Java, no Android API: test/test-mesh-only.sh compiles this file on a JVM.
 */
public final class MeshAddrs {
    private MeshAddrs() {}

    /** The bind list, or null when mesh-only is ON and the mesh has no address (fail closed). */
    public static String[] binds(boolean meshOnly, List<String> meshAddrs, String[] loopback, String[] open) {
        if (!meshOnly) return open;
        if (meshAddrs == null || meshAddrs.isEmpty()) return null;
        List<String> out = new ArrayList<>(meshAddrs);
        Collections.addAll(out, loopback);
        return out.toArray(new String[0]);
    }

    /** Addresses of interfaces named like [ifaceRegex] that lie in one of [cidrs]; ifaceAddrs is interface name to literal addresses. */
    public static List<String> pick(Map<String, List<String>> ifaceAddrs, String ifaceRegex, String[] cidrs) {
        Pattern name = Pattern.compile(ifaceRegex);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : ifaceAddrs.entrySet()) {
            if (!name.matcher(e.getKey()).matches()) continue;
            for (String a : e.getValue()) {
                String lit = a.contains("%") ? a.substring(0, a.indexOf('%')) : a;
                for (String c : cidrs) {
                    if (inCidr(lit, c)) { if (!out.contains(lit)) out.add(lit); break; }
                }
            }
        }
        return out;
    }

    /** The phone's interfaces right now, name to literal addresses (no DNS: only literals are ever parsed). */
    public static Map<String, List<String>> interfaces() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface i = ifs.nextElement();
                List<String> addrs = new ArrayList<>();
                Enumeration<InetAddress> as = i.getInetAddresses();
                while (as.hasMoreElements()) addrs.add(as.nextElement().getHostAddress());
                m.put(i.getName(), addrs);
            }
        } catch (Exception e) {
            // no interface list: no mesh address: the caller fails closed
        }
        return m;
    }

    public static List<String> resolve(String ifaceRegex, String[] cidrs) {
        return pick(interfaces(), ifaceRegex, cidrs);
    }

    static boolean inCidr(String literal, String cidr) {
        try {
            int slash = cidr.indexOf('/');
            byte[] net = literalBytes(cidr.substring(0, slash));
            byte[] ip = literalBytes(literal);
            int bits = Integer.parseInt(cidr.substring(slash + 1));
            if (net == null || ip == null || net.length != ip.length || bits < 0 || bits > net.length * 8) return false;
            for (int i = 0; i < bits; i++) {
                int mask = 0x80 >> (i % 8);
                if ((net[i / 8] & mask) != (ip[i / 8] & mask)) return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] literalBytes(String s) {
        if (!s.matches("[0-9a-fA-F:.]+")) return null;   // a literal only, so getByName never queries DNS
        try {
            return InetAddress.getByName(s).getAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
