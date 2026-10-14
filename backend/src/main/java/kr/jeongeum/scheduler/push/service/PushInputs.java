package kr.jeongeum.scheduler.push.service;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.push.api.PushRequests;

public final class PushInputs {
    private static final Set<String> HOSTS=Set.of("fcm.googleapis.com","updates.push.services.mozilla.com","web.push.apple.com");
    private static final BigInteger P=new BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",16);
    private static final BigInteger B=new BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",16);
    private PushInputs() {}
    public static Validated validate(PushRequests.Register input) {
        if (input==null || input.keys()==null) { throw ApiException.invalidInput(); }
        URI uri=endpoint(input.endpoint());
        publicKey(input.keys().p256dh());
        byte[] secret=decode(input.keys().auth(),16,40);
        if (secret.length!=16) { throw ApiException.invalidInput(); }
        String name=input.deviceName()==null?"이 기기":input.deviceName().strip();
        if (name.isEmpty() || name.codePointCount(0,name.length())>80 || name.codePoints().anyMatch(Character::isISOControl)) { throw ApiException.invalidInput(); }
        return new Validated(uri.toASCIIString(),Base64.getUrlEncoder().withoutPadding().encodeToString(decode(input.keys().p256dh(),65,120)),
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret),name);
    }
    public static URI endpoint(String value) {
        try {
            if (value==null || value.length()>2048 || !value.equals(value.strip()) || value.chars().anyMatch(c -> c<33 || c>126)) { throw ApiException.invalidInput(); }
            URI uri=URI.create(value);
            if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || (uri.getPort()!=-1 && uri.getPort()!=443)
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || uri.getRawPath()==null || uri.getRawPath().length()<2 || uri.getRawPath().contains("\\")) { throw ApiException.invalidInput(); }
            return uri;
        } catch (IllegalArgumentException exception) { throw ApiException.invalidInput(); }
    }
    public static void publicKey(String value) {
        byte[] point=decode(value,65,120);
        if (point.length!=65 || point[0]!=4) { throw ApiException.invalidInput(); }
        BigInteger x=new BigInteger(1,java.util.Arrays.copyOfRange(point,1,33)),y=new BigInteger(1,java.util.Arrays.copyOfRange(point,33,65));
        if (x.compareTo(P)>=0 || y.compareTo(P)>=0 || !y.multiply(y).mod(P).equals(x.multiply(x).multiply(x).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P))) {
            throw ApiException.invalidInput();
        }
    }
    static byte[] decode(String value,int expected,int max) {
        try {
            if (value==null || value.length()>max || !value.matches("[A-Za-z0-9_-]+={0,2}")) { throw ApiException.invalidInput(); }
            byte[] bytes=Base64.getUrlDecoder().decode(value);
            if (bytes.length!=expected) { throw ApiException.invalidInput(); } return bytes;
        } catch (IllegalArgumentException exception) { throw ApiException.invalidInput(); }
    }
    public static String sessionDigest(String sessionId) {
        if (sessionId==null || sessionId.isBlank()) { throw ApiException.invalidInput(); }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("jeongeum-push-session:"+sessionId).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) { return false; }
        byte[] a=address.getAddress();
        if (a.length==16) {
            // Global unicast only; documentation and transition ranges are unsuitable send targets.
            return (a[0]&0xe0)==0x20 && !((a[0]&255)==0x20 && (a[1]&255)==0x01 && (a[2]&255)==0x0d && (a[3]&255)==0xb8)
                    && !((a[0]&255)==0x20 && (a[1]&255)==0x02);
        }
        int first=a[0]&255,second=a[1]&255;
        return first!=0 && first!=127 && first<224 && first!=10 && !(first==100 && second>=64 && second<=127)
                && !(first==169 && second==254) && !(first==172 && second>=16 && second<=31) && !(first==192 && second==168)
                && !(first==192 && second==0) && !(first==198 && (second==18 || second==19))
                && !(first==192 && second==2) && !(first==198 && second==51) && !(first==203 && second==0);
    }
    public record Validated(String endpoint,String p256dh,String auth,String deviceName) {
        @Override public String toString() { return "Validated Push subscription [redacted]"; }
    }
}
