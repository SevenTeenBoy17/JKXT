package com.jkxt.security.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 解析请求的真实客户端 IP（登录审计 last_login_ip，以及后续按 IP 维度的限流）。
 *
 * <p>原实现无条件采信 {@code X-Forwarded-For} 首段，而该头由客户端完全可控：任何人都能把审计 IP 写成任意
 * 值，任何按 IP 的门槛也能靠轮换 XFF 绕过。这里改为 secure-by-default —— 只有当直连对端
 * （{@code getRemoteAddr()}）本身落在 {@code jkxt.auth.trusted-proxies} 里时才读 XFF；该配置<b>默认为空</b>，
 * 即默认完全不信任 XFF，退回直连对端地址。
 *
 * <p>采信时取 XFF 中<b>最右侧的非可信代理</b>而非首段：XFF 由各跳自左向右追加，最右侧那段是紧邻我们的
 * 可信代理亲眼看到的对端地址；往左的部分都可能是客户端预先塞进来的伪造前缀。
 */
@Component
public class ClientIpResolver {

  private static final String FORWARDED_FOR = "X-Forwarded-For";

  private final List<CidrRange> trustedProxies;

  public ClientIpResolver(@Value("${jkxt.auth.trusted-proxies:}") String trustedProxies) {
    this.trustedProxies = parseRanges(trustedProxies);
  }

  public String resolve(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (trustedProxies.isEmpty()) {
      return remoteAddr;
    }
    InetAddress peer = parseLiteral(remoteAddr);
    if (peer == null || !isTrustedProxy(peer)) {
      // 直连对端不是可信代理 → 它自称的 XFF 无任何可信度，只能采信我们自己看到的地址。
      return remoteAddr;
    }
    String forwarded = request.getHeader(FORWARDED_FOR);
    if (forwarded == null || forwarded.isBlank()) {
      return remoteAddr;
    }
    String[] hops = forwarded.split(",");
    for (int i = hops.length - 1; i >= 0; i--) {
      InetAddress hop = parseLiteral(hops[i].trim());
      if (hop == null) {
        // 可信代理只会追加合法字面量；出现无法解析的段说明这条链已不可信，退回直连对端而不是继续猜。
        return remoteAddr;
      }
      if (!isTrustedProxy(hop)) {
        return hop.getHostAddress();
      }
    }
    return remoteAddr;
  }

  private boolean isTrustedProxy(InetAddress address) {
    for (CidrRange range : trustedProxies) {
      if (range.contains(address)) {
        return true;
      }
    }
    return false;
  }

  private static List<CidrRange> parseRanges(String config) {
    List<CidrRange> ranges = new ArrayList<>();
    if (config == null || config.isBlank()) {
      return List.copyOf(ranges);
    }
    for (String entry : config.split(",")) {
      String text = entry.trim();
      if (!text.isEmpty()) {
        ranges.add(CidrRange.parse(text));
      }
    }
    return List.copyOf(ranges);
  }

  /**
   * 只接受 IP 字面量，且绝不触发 DNS 解析：XFF 由外部完全可控，若把它交给会回落到域名解析的 API，等于给
   * 未认证的登录接口开了一条"按攻击者指定的名字发起 DNS 查询"的通道。IPv4 自行逐字节解析；IPv6 交给
   * {@link InetAddress} 但强制加方括号 —— 方括号让 JDK 进入"必须是 IPv6 字面量"分支，解析失败直接抛
   * {@link UnknownHostException}，不会回落查 DNS。
   */
  private static InetAddress parseLiteral(String text) {
    // 带 scope id(如 fe80::1%eth0)的链路本地地址跨代理没有意义，且会把 last_login_ip(varchar(45)) 撑长。
    if (text == null || text.isEmpty() || text.indexOf('%') >= 0) {
      return null;
    }
    byte[] ipv4 = parseIpv4(text);
    if (ipv4 != null) {
      try {
        return InetAddress.getByAddress(ipv4);
      } catch (UnknownHostException impossible) {
        return null;
      }
    }
    if (text.indexOf(':') < 0) {
      return null;
    }
    try {
      return InetAddress.getByName("[" + text + "]");
    } catch (UnknownHostException invalid) {
      return null;
    }
  }

  private static byte[] parseIpv4(String text) {
    String[] parts = text.split("\\.", -1);
    if (parts.length != 4) {
      return null;
    }
    byte[] bytes = new byte[4];
    for (int i = 0; i < 4; i++) {
      String part = parts[i];
      if (part.isEmpty() || part.length() > 3) {
        return null;
      }
      int value = 0;
      for (int j = 0; j < part.length(); j++) {
        char digit = part.charAt(j);
        if (digit < '0' || digit > '9') {
          return null;
        }
        value = value * 10 + (digit - '0');
      }
      if (value > 255) {
        return null;
      }
      bytes[i] = (byte) value;
    }
    return bytes;
  }

  /**
   * 一条可信代理网段。配置里的非法条目在构造期(即启动期)直接抛错拒绝启动 —— 安全控制被 typo 静默削弱，
   * 比响亮的启动失败危险得多（与 {@code ProductionSecurityGate} 的取向一致）。
   */
  private record CidrRange(byte[] prefix, int prefixLength) {

    static CidrRange parse(String text) {
      int slash = text.indexOf('/');
      String addressPart = slash < 0 ? text : text.substring(0, slash).trim();
      InetAddress address = parseLiteral(addressPart);
      if (address == null) {
        throw new IllegalArgumentException("jkxt.auth.trusted-proxies 含非法 IP/CIDR 条目: " + text);
      }
      byte[] prefix = address.getAddress();
      int maxBits = prefix.length * 8;
      if (slash < 0) {
        // 裸地址 = 单机代理，等价 /32(v4) 或 /128(v6)。
        return new CidrRange(prefix, maxBits);
      }
      int prefixLength;
      try {
        prefixLength = Integer.parseInt(text.substring(slash + 1).trim());
      } catch (NumberFormatException notANumber) {
        throw new IllegalArgumentException("jkxt.auth.trusted-proxies 含非法 CIDR 前缀长度: " + text, notANumber);
      }
      if (prefixLength < 0 || prefixLength > maxBits) {
        throw new IllegalArgumentException("jkxt.auth.trusted-proxies 的 CIDR 前缀长度超出范围: " + text);
      }
      return new CidrRange(prefix, prefixLength);
    }

    boolean contains(InetAddress address) {
      byte[] candidate = address.getAddress();
      // v4(4 字节)与 v6(16 字节)不跨族比较。
      if (candidate.length != prefix.length) {
        return false;
      }
      int wholeBytes = prefixLength / 8;
      for (int i = 0; i < wholeBytes; i++) {
        if (candidate[i] != prefix[i]) {
          return false;
        }
      }
      int remainingBits = prefixLength % 8;
      if (remainingBits == 0) {
        return true;
      }
      int mask = (0xFF << (8 - remainingBits)) & 0xFF;
      return (candidate[wholeBytes] & mask) == (prefix[wholeBytes] & mask);
    }
  }
}
