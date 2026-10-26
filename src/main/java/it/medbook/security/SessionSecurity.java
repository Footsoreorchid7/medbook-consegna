package it.medbook.security;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import it.medbook.config.AuthConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Meccanismo cookie/Origin/CSRF comune a login, logout e API cliniche. Nessuno stato per richiesta. */
public final class SessionSecurity {
    private final SessionStore sessions;
    private final AuthConfig config;
    public SessionSecurity(SessionStore sessions, AuthConfig config) {
        this.sessions=Objects.requireNonNull(sessions); this.config=Objects.requireNonNull(config);
    }
    public Optional<SessionStore.Session> find(HttpExchange exchange) {
        List<String> cookies=exchange.getRequestHeaders().get("Cookie");
        String id=null; int length=0;
        if(cookies!=null) {
            for(String header:cookies) {
                length+=header.length();
                if(length>4096) { throw new IllegalArgumentException("Cookie non valido."); }
                for(String item:header.split(";",-1)) {
                    String[] pair=item.trim().split("=",2);
                    if(pair[0].equals(config.cookieName())) {
                        if(id!=null || pair.length!=2) { throw new IllegalArgumentException("Cookie non valido."); }
                        id=pair[1];
                    }
                }
            }
        }
        return id==null?Optional.empty():sessions.find(id);
    }
    public boolean validOrigin(HttpExchange exchange) {
        return config.origin().equals(single(exchange.getRequestHeaders(),"Origin"));
    }
    public boolean validCsrf(HttpExchange exchange,SessionStore.Session session) {
        String supplied=single(exchange.getRequestHeaders(),"X-CSRF-Token");
        return supplied!=null && supplied.length()==43 && MessageDigest.isEqual(
                session.csrfToken().getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII));
    }
    private String single(Headers headers,String name) {
        List<String> values=headers.get(name);
        return values!=null && values.size()==1?values.getFirst():null;
    }
}
