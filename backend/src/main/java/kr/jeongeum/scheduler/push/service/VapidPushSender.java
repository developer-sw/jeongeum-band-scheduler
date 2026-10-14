package kr.jeongeum.scheduler.push.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import kr.jeongeum.scheduler.push.domain.PushDelivery;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class VapidPushSender implements PushSender {
    private static final ThreadPoolExecutor DNS=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),
            task -> { Thread t=new Thread(task,"push-dns"); t.setDaemon(true); return t; });
    private final PushSettings settings;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public VapidPushSender(PushSettings settings,ObjectMapper json,Clock clock) { this.settings=settings; this.json=json; this.clock=clock; }
    @Override public boolean enabled() { return settings.enabled(); }
    @Override public Result send(PushDelivery delivery) {
        if (!enabled()) { return Result.transientFailure("PUSH_DISABLED"); }
        URI endpoint;
        try { endpoint=PushInputs.endpoint(delivery.endpoint()); }
        catch (RuntimeException invalid) { return new Result(400,"ENDPOINT_INVALID",null); }
        try {
            var resolved=DNS.submit(() -> InetAddress.getAllByName(endpoint.getHost()));
            InetAddress[] addresses;
            try { addresses=resolved.get(3,TimeUnit.SECONDS); }
            finally { resolved.cancel(true); }
            if (addresses.length==0 || java.util.Arrays.stream(addresses).anyMatch(address -> !PushInputs.publicAddress(address))) {
                return new Result(400,"ENDPOINT_ADDRESS_BLOCKED",null);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Result.transientFailure("PUSH_INTERRUPTED"); }
        catch (Exception unavailable) { return Result.transientFailure("PUSH_DNS_UNAVAILABLE"); }
        try {
            nl.martijndwars.webpush.HttpRequest prepared=encryptedRequest(delivery);
            // Never accept a library rewrite that escapes the exact browser provider allowlist.
            URI sendUri=PushInputs.endpoint(prepared.getUrl());
            if (!endpoint.getHost().equals(sendUri.getHost())) { return new Result(400,"ENDPOINT_INVALID",null); }
            var request=HttpRequest.newBuilder(sendUri).timeout(Duration.ofSeconds(10));
            prepared.getHeaders().forEach(request::header);
            HttpResponse<Void> result=http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(prepared.getBody())).build(),HttpResponse.BodyHandlers.discarding());
            return Result.response(result.statusCode(),retryAfter(result.headers().firstValue("Retry-After").orElse(null),clock.instant()));
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Result.transientFailure("PUSH_INTERRUPTED"); }
        catch (java.io.IOException unavailable) { return Result.transientFailure("PUSH_NETWORK_UNAVAILABLE"); }
        catch (Exception invalid) { return new Result(400,"PUSH_ENCRYPTION_FAILED",null); }
    }
    /** Crypto-only seam: verifies the actual VAPID/AES128GCM output without contacting an external provider. */
    nl.martijndwars.webpush.HttpRequest encryptedRequest(PushDelivery delivery) throws Exception {
        if (!enabled()) { throw new IllegalStateException("Push delivery is disabled"); }
        int ttl=3600;
        if ("START_REMINDER".equals(delivery.eventType()) && delivery.actualStartAt()!=null) {
            ttl=(int)Math.max(1,Math.min(3600,Duration.between(clock.instant(),delivery.actualStartAt()).toSeconds()));
        }
        byte[] payload=json.writeValueAsBytes(Map.of("subscriptionId",Long.toString(delivery.subscriptionId()),
                "bindingVersion",Long.toString(delivery.bindingVersion()),"eventKey",delivery.eventKey(),
                "title",truncate(delivery.title(),80),"body",truncate(delivery.body(),240),"targetPath",delivery.targetPath(),
                "tag","jeongeum-notification:"+delivery.notificationId()));
        Notification notification=new Notification(delivery.endpoint(),delivery.p256dh(),delivery.auth(),payload,ttl);
        return new PreparedPush(settings.publicKey(),settings.privateKey(),settings.subject()).prepare(notification);
    }
    private static String truncate(String input,int count) {
        return input.codePointCount(0,input.length())<=count?input:input.substring(0,input.offsetByCodePoints(0,count-1))+"…";
    }
    public static Duration retryAfter(String input,Instant now) {
        if (input==null) { return null; }
        try {
            long seconds=input.matches("[0-9]{1,8}")?Long.parseLong(input):Duration.between(now,ZonedDateTime.parse(input,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();
            return Duration.ofSeconds(Math.max(1,Math.min(3600,seconds)));
        } catch (RuntimeException invalid) { return null; }
    }
    private static final class PreparedPush extends AbstractPushService<PreparedPush> {
        PreparedPush(String publicKey,String privateKey,String subject) throws java.security.GeneralSecurityException { super(publicKey,privateKey,subject); }
        nl.martijndwars.webpush.HttpRequest prepare(Notification notification) throws Exception { return prepareRequest(notification,Encoding.AES128GCM); }
    }
}
