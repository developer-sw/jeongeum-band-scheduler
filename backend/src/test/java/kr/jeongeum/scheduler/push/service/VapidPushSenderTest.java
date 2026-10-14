package kr.jeongeum.scheduler.push.service;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.push.api.PushRequests;
import kr.jeongeum.scheduler.push.domain.PushDelivery;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jose4j.jws.JsonWebSignature;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Validates real VAPID signing and RFC 8291 decryption offline. No external HTTP/DNS calls. */
class VapidPushSenderTest {
    private static final Instant NOW=Instant.parse("2026-10-30T08:00:00Z");
    private static final byte[] AUTH=new byte[16];
    private final JsonMapper json=new JsonMapper();
    private KeyPair pair() throws Exception {
        Security.addProvider(new BouncyCastleProvider()); var generator=KeyPairGenerator.getInstance("ECDH","BC"); generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1")); return generator.generateKeyPair();
    }
    private String publicKey(KeyPair pair) { return encode(Utils.encode((ECPublicKey)pair.getPublic())); }
    private String privateKey(KeyPair pair) {
        byte[] number=((ECPrivateKey)pair.getPrivate()).getD().toByteArray(),value=new byte[32];
        System.arraycopy(number,Math.max(0,number.length-32),value,Math.max(0,32-number.length),Math.min(32,number.length)); return encode(value);
    }
    private String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private PushSettings settings(KeyPair pair) { return new PushSettings(true,publicKey(pair),privateKey(pair),"mailto:synthetic@example.invalid"); }
    private PushDelivery delivery(KeyPair browser,String type,Instant start,String body) {
        return new PushDelivery(1,UUID.randomUUID(),1,22,33,4,"https://fcm.googleapis.com/wpush/v2/synthetic",publicKey(browser),encode(AUTH),
                "synthetic-event","합성 알림",body,"/notifications",start,type);
    }
    @Test void actualAes128GcmPayloadDecryptsAndVapidSignatureVerifiesWithoutExternalSend() throws Exception {
        KeyPair server=pair(),browser=pair(); var sender=new VapidPushSender(settings(server),json,Clock.fixed(NOW,ZoneOffset.UTC));
        var request=sender.encryptedRequest(delivery(browser,"START_REMINDER",NOW.plusSeconds(1800),"합성 본문"));
        assertEquals("aes128gcm",request.getHeaders().get("Content-Encoding")); assertEquals("1800",request.getHeaders().get("TTL"));
        assertTrue(request.getBody().length<4096); assertFalse(new String(request.getBody(),StandardCharsets.UTF_8).contains("합성 본문"));
        String authorization=request.getHeaders().get("Authorization"); assertTrue(authorization.startsWith("vapid t="));
        String token=authorization.substring(8,authorization.indexOf(", k=")); var signature=new JsonWebSignature(); signature.setCompactSerialization(token); signature.setKey(server.getPublic()); assertTrue(signature.verifySignature());
        var claims=json.readTree(signature.getPayload()); assertEquals("https://fcm.googleapis.com",claims.get("aud").asString()); assertEquals("mailto:synthetic@example.invalid",claims.get("sub").asString());
        byte[] plaintext=decrypt(request.getBody(),browser); var payload=json.readTree(plaintext);
        assertEquals("33",payload.get("subscriptionId").asString()); assertEquals("4",payload.get("bindingVersion").asString()); assertEquals("synthetic-event",payload.get("eventKey").asString());
        assertEquals("합성 본문",payload.get("body").asString()); assertEquals("jeongeum-notification:22",payload.get("tag").asString()); assertEquals("/notifications",payload.get("targetPath").asString());
        assertFalse(payload.has("endpoint")); assertFalse(payload.has("auth")); assertFalse(payload.has("userId"));
    }
    @Test void payloadBudgetTruncatesUnicodeWithoutBrokenSurrogatesAndTtlUsesInjectedClock() throws Exception {
        KeyPair browser=pair(); var sender=new VapidPushSender(settings(pair()),json,Clock.fixed(NOW,ZoneOffset.UTC));
        var request=sender.encryptedRequest(delivery(browser,"START_REMINDER",NOW.plusSeconds(10),"😀".repeat(2000)));
        assertEquals("10",request.getHeaders().get("TTL")); assertTrue(request.getBody().length<4096);
        String body=json.readTree(decrypt(request.getBody(),browser)).get("body").asString(); assertEquals(240,body.codePointCount(0,body.length())); assertTrue(body.endsWith("…"));
        assertEquals("3600",sender.encryptedRequest(delivery(browser,"IMPORTANT_NOTICE",null,"본문")).getHeaders().get("TTL"));
    }
    @Test void configurationIsDisabledUnlessRequestedWithMatchingPairAndValidSubject() throws Exception {
        KeyPair key=pair(),different=pair(); assertTrue(settings(key).enabled());
        assertFalse(new PushSettings(false,publicKey(key),privateKey(key),"mailto:synthetic@example.invalid").enabled());
        assertFalse(new PushSettings(true,publicKey(key),privateKey(different),"mailto:synthetic@example.invalid").enabled());
        assertFalse(new PushSettings(true,publicKey(key),"","mailto:synthetic@example.invalid").enabled());
        assertFalse(new PushSettings(true,publicKey(key),privateKey(key),"file:///local").enabled());
        PushSettings disabled=new PushSettings(true,"invalid","invalid","mailto:synthetic@example.invalid"); assertNull(disabled.publicKey());
        assertEquals("PUSH_DISABLED",new VapidPushSender(disabled,json,Clock.fixed(NOW,ZoneOffset.UTC)).send(delivery(key,"IMPORTANT_NOTICE",null,"본문")).errorCode());
    }
    @Test void retryAfterSupportsSecondsDateAndBoundsInvalidOrExtremeInput() {
        assertEquals(Duration.ofSeconds(120),VapidPushSender.retryAfter("120",NOW));
        assertEquals(Duration.ofSeconds(120),VapidPushSender.retryAfter("Fri, 30 Oct 2026 08:02:00 GMT",NOW));
        assertEquals(Duration.ofHours(1),VapidPushSender.retryAfter("99999999",NOW)); assertEquals(Duration.ofSeconds(1),VapidPushSender.retryAfter("0",NOW));
        assertNull(VapidPushSender.retryAfter("not-a-date",NOW)); assertNull(VapidPushSender.retryAfter(null,NOW));
    }
    @Test void exactProviderAllowlistRejectsSsrfBypassesBeforeAnyNetworkRequest() {
        for(String value:java.util.List.of("http://fcm.googleapis.com/x","https://fcm.googleapis.com.evil.invalid/x","https://fcm.googleapis.com@127.0.0.1/x",
                "https://[::1]/x","https://2130706433/x","https://fcm.googleapis.com:8080/x","https://fcm.googleapis.com/x?secret=y","https://fcm.googleapis.com/x#secret")) {
            assertThrows(ApiException.class,()->PushInputs.endpoint(value));
        }
        for(String host:java.util.List.of("fcm.googleapis.com","updates.push.services.mozilla.com","web.push.apple.com")) {
            assertEquals(host,PushInputs.endpoint("https://"+host+"/synthetic").getHost());
        }
    }
    @Test void dnsTargetsExcludePrivateLoopbackCarrierAndDocumentationRanges() throws Exception {
        for(String address:java.util.List.of("127.0.0.1","0.0.0.0","10.0.0.1","172.16.0.1","192.168.1.1","169.254.1.1","100.64.0.1","198.18.1.1","192.0.2.1","::1","fc00::1","fe80::1","2001:db8::1")) {
            assertFalse(PushInputs.publicAddress(InetAddress.getByName(address)),address);
        }
        assertTrue(PushInputs.publicAddress(InetAddress.getByName("142.250.1.1"))); assertTrue(PushInputs.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }
    @Test void keysAreValidatedOnCurveAndCanonicalizedAndSessionDigestIsOpaque() throws Exception {
        KeyPair browser=pair(); String key=publicKey(browser); PushInputs.publicKey(key);
        byte[] invalid=Utils.encode((ECPublicKey)browser.getPublic()); invalid[64]^=1; assertThrows(ApiException.class,()->PushInputs.publicKey(encode(invalid)));
        var parsed=PushInputs.validate(new PushRequests.Register("https://web.push.apple.com/synthetic",new PushRequests.Keys(key+"=",encode(AUTH)+"=="),"합성 기기"));
        assertEquals(key,parsed.p256dh()); assertEquals(encode(AUTH),parsed.auth());
        assertEquals(64,PushInputs.sessionDigest("synthetic-session").length()); assertNotEquals(PushInputs.sessionDigest("synthetic-session"),PushInputs.sessionDigest("second-session"));
        assertFalse(parsed.toString().contains(parsed.auth()));
    }
    private byte[] decrypt(byte[] encrypted,KeyPair browser) throws Exception {
        byte[] salt=Arrays.copyOfRange(encrypted,0,16); int keyLength=encrypted[20]&255;
        byte[] serverPublic=Arrays.copyOfRange(encrypted,21,21+keyLength),browserPublic=Utils.encode((ECPublicKey)browser.getPublic());
        var agreement=KeyAgreement.getInstance("ECDH","BC"); agreement.init(browser.getPrivate()); agreement.doPhase(Utils.loadPublicKey(serverPublic),true);
        byte[] ikm=expand(hmac(AUTH,agreement.generateSecret()),concat("WebPush: info\0".getBytes(StandardCharsets.UTF_8),browserPublic,serverPublic),32);
        byte[] prk=hmac(salt,ikm),cek=expand(prk,"Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.UTF_8),16),nonce=expand(prk,"Content-Encoding: nonce\0".getBytes(StandardCharsets.UTF_8),12);
        var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(cek,"AES"),new GCMParameterSpec(128,nonce));
        byte[] result=cipher.doFinal(Arrays.copyOfRange(encrypted,21+keyLength,encrypted.length)); assertEquals(2,result[result.length-1]); return Arrays.copyOf(result,result.length-1);
    }
    private byte[] expand(byte[] prk,byte[] info,int length) throws Exception { return Arrays.copyOf(hmac(prk,concat(info,new byte[]{1})),length); }
    private byte[] hmac(byte[] key,byte[] input) throws Exception { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(input); }
    private byte[] concat(byte[]... arrays) { int length=Arrays.stream(arrays).mapToInt(a -> a.length).sum(); byte[] result=new byte[length]; int offset=0; for(byte[] value:arrays) { System.arraycopy(value,0,result,offset,value.length); offset+=value.length; } return result; }
}
