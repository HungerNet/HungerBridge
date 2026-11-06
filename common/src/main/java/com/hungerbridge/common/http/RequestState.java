package com.hungerbridge.common.http;

import com.google.gson.JsonObject;
import com.hungerbridge.common.security.TokenManager;
import com.sun.net.httpserver.HttpExchange;

/**
 * Per-request state (authenticated token, parsed body, "response sent" flag).
 *
 * <p><b>Why not {@code HttpExchange#setAttribute}:</b> in the JDK implementation
 * ({@code sun.net.httpserver.ExchangeImpl}, verified on JDK 21 and 25) exchange
 * attributes are backed by the <i>HttpContext's</i> attribute map, i.e. shared by every
 * concurrent request to the same endpoint. Storing the authenticated token there let one
 * request's ACL check see another request's token.
 *
 * <p>A request is handled start-to-finish (filters + handler) on one worker thread, so the
 * state is thread-confined; {@code BridgeServer}'s outermost filter binds and clears it.
 */
public final class RequestState {

    private static final ThreadLocal<RequestState> CURRENT = new ThreadLocal<>();

    private final HttpExchange exchange;
    boolean responded;
    JsonObject json;
    String tokenId;
    TokenManager.Token token;

    private RequestState(HttpExchange exchange) {
        this.exchange = exchange;
    }

    /** State for {@code ex}; created (and bound to this thread) on first use. */
    public static RequestState of(HttpExchange ex) {
        RequestState s = CURRENT.get();
        if (s == null || s.exchange != ex) {
            s = new RequestState(ex);
            CURRENT.set(s);
        }
        return s;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public JsonObject json() {
        return json;
    }

    public String tokenId() {
        return tokenId;
    }

    public TokenManager.Token token() {
        return token;
    }
}
