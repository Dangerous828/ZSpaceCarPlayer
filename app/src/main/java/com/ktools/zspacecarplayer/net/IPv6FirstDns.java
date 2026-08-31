package com.ktools.zspacecarplayer.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Dns;

/**
 * IPv6 优先 DNS 机制，规避 Android 4.3 libc 双栈 IPv4 查询超时导致的卡顿
 */
public class IPv6FirstDns implements Dns {

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (hostname == null) {
            throw new UnknownHostException("hostname == null");
        }
        List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
        if (addresses == null || addresses.isEmpty()) {
            return addresses;
        }

        List<InetAddress> result = new ArrayList<>();
        for (InetAddress addr : addresses) {
            if (addr instanceof Inet6Address) {
                result.add(0, addr); // IPv6 插到最前面
            } else {
                result.add(addr);
            }
        }
        return result.isEmpty() ? addresses : result;
    }
}
