package com.lingmao.scm.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

/** Forwarded values are accepted only from configured network peers; no DNS lookups. */
@Component
public class TrustedProxyResolver {
    private final List<Network> proxies;

    public TrustedProxyResolver(@Value("${lingmao.proxy.trusted-cidrs:}") String cidrs) {
        proxies = Arrays.stream(cidrs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Network::parse).toList();
    }

    public String clientIp(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!trusted(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 2048) return peer;
        String[] chain = forwarded.split(",");
        if (chain.length > 16) return peer;
        String current = peer;
        for (int i = chain.length - 1; i >= 0 && trusted(current); i--) {
            String next = chain[i].trim();
            if (address(next) == null) return peer;
            current = next;
        }
        return current;
    }

    public boolean secure(HttpServletRequest request) {
        if (request.isSecure()) return true;
        return trusted(request.getRemoteAddr()) && "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }

    private boolean trusted(String ip) {
        byte[] value = address(ip);
        return value != null && proxies.stream().anyMatch(n -> n.matches(value));
    }

    private static byte[] address(String input) {
        if (input == null || input.length() > 45 ||
                !(input.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || input.contains(":") && input.matches("[0-9a-fA-F:]+"))) return null;
        try { return InetAddress.getByName(input).getAddress(); }
        catch (java.net.UnknownHostException invalid) { return null; }
    }

    private record Network(byte[] bytes, int prefix) {
        static Network parse(String cidr) {
            String[] parts = cidr.split("/", -1);
            byte[] bytes = address(parts[0]);
            if (bytes == null || parts.length > 2) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            int prefix = parts.length == 1 ? bytes.length * 8 : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > bytes.length * 8) throw new IllegalArgumentException("Invalid trusted proxy prefix");
            return new Network(bytes, prefix);
        }
        boolean matches(byte[] value) {
            if (value.length != bytes.length) return false;
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((value[bit / 8] & mask) != (bytes[bit / 8] & mask)) return false;
            }
            return true;
        }
    }
}
