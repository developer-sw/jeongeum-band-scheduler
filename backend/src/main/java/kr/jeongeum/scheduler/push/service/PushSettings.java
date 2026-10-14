package kr.jeongeum.scheduler.push.service;

import java.net.URI;
import java.security.Security;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PushSettings {
    private final String publicKey,privateKey,subject;
    private final boolean enabled;
    public PushSettings(@Value("${jeongeum.push.enabled:false}") boolean requested,
            @Value("${APP_VAPID_PUBLIC_KEY:}") String publicKey,@Value("${APP_VAPID_PRIVATE_KEY:}") String privateKey,
            @Value("${APP_VAPID_SUBJECT:}") String subject) {
        this.publicKey=publicKey; this.privateKey=privateKey; this.subject=subject;
        Security.addProvider(new BouncyCastleProvider());
        boolean valid=false;
        // Upstream's opt-in crypto diagnostic prints plaintext/keys; never enable it in this application.
        if (requested && !"1".equals(System.getenv("ECE_KEYLOG")) && !publicKey.isBlank() && !privateKey.isBlank() && !subject.isBlank()) {
            try {
                PushInputs.publicKey(publicKey); PushInputs.decode(privateKey,32,48);
                URI contact=URI.create(subject);
                valid=("mailto".equals(contact.getScheme()) || ("https".equals(contact.getScheme()) && contact.getHost()!=null))
                        && Utils.verifyKeyPair(Utils.loadPrivateKey(privateKey),Utils.loadPublicKey(publicKey));
            } catch (Exception invalid) { valid=false; }
        }
        this.enabled=valid;
    }
    public boolean enabled() { return enabled; }
    public String publicKey() { return enabled?publicKey:null; }
    String privateKey() { return privateKey; }
    String subject() { return subject; }
    @Override public String toString() { return "PushSettings [enabled="+enabled+"]"; }
}
