package com.xauusd.mobileengine;

import android.util.Base64;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

public final class ExnessClient {
    public static final String DEFAULT_HOST = "https://api.exness.com";

    public static final class Response {
        public final int code;
        public final String body;
        public Response(int code, String body) { this.code = code; this.body = body; }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    public static final class Credentials {
        public String accountId = "";
        public String apiKey = "";
        public String secret = "";
        public String host = DEFAULT_HOST;
    }

    private final SecurityStore store;
    private final SecureRandom random = new SecureRandom();

    public ExnessClient(SecurityStore store) {
        this.store = store;
    }

    public Credentials loadCredentials() {
        Credentials c = new Credentials();
        c.accountId = store.get("exn_account", "");
        c.apiKey = store.get("exn_api_key", "");
        c.secret = store.get("exn_secret", "");
        c.host = store.get("exn_host", DEFAULT_HOST);
        if (c.host.trim().isEmpty()) c.host = DEFAULT_HOST;
        return c;
    }

    public void saveCredentials(String accountId, String apiKey, String secret, String host) {
        store.put("exn_account", accountId == null ? "" : accountId.trim());
        store.put("exn_api_key", apiKey == null ? "" : apiKey.trim());
        store.put("exn_secret", secret == null ? "" : secret.trim());
        store.put("exn_host", (host == null || host.trim().isEmpty()) ? DEFAULT_HOST : normalizeHost(host));
    }

    public Response connectAndResolve() throws Exception {
        Credentials c = loadCredentials();
        requireCredentials(c);
        String path = "/v1/trading/access-point?account_id=" + c.accountId;
        Response r = request(c.host, "GET", path, "", "");
        if (r.ok()) {
            try {
                JSONObject j = new JSONObject(r.body);
                String access = j.optString("access_point", "");
                if (!access.isEmpty()) {
                    String host = normalizeHost(access);
                    store.put("exn_host", host);
                    c.host = host;
                }
            } catch (Exception ignored) {}
        }
        return accountInfo();
    }

    public Response accountInfo() throws Exception {
        Credentials c = loadCredentials();
        requireCredentials(c);
        String path = "/v1/configuration/accounts/" + c.accountId + "/account";
        return request(c.host, "GET", path, "", "");
    }

    public Response snapshot() throws Exception {
        Credentials c = loadCredentials();
        requireCredentials(c);
        String path = "/v1/trading/accounts/" + c.accountId + "/snapshot";
        return request(c.host, "GET", path, "", "");
    }

    public Response instrumentConditions(String instrument) throws Exception {
        Credentials c = loadCredentials();
        requireCredentials(c);
        String path = "/v1/configuration/accounts/" + c.accountId + "/instruments/" + instrument + "/conditions";
        return request(c.host, "GET", path, "", "");
    }

    public Response placeLimit(String instrument, String side, String volume,
                                String price, String sl, String tp, String comment) throws Exception {
        Credentials c = loadCredentials();
        requireCredentials(c);
        if (!"buy".equals(side) && !"sell".equals(side)) throw new IllegalArgumentException("side must be buy/sell");
        JSONObject j = new JSONObject();
        j.put("instrument", instrument);
        j.put("side", side);
        j.put("type", "limit");
        j.put("volume", volume);
        j.put("price", price);
        if (sl != null && !sl.trim().isEmpty()) j.put("stop_loss_price", sl);
        if (tp != null && !tp.trim().isEmpty()) j.put("take_profit_price", tp);
        if (comment != null && !comment.trim().isEmpty()) j.put("comment", comment);

        String body = j.toString();
        String path = "/v1/trading/accounts/" + c.accountId + "/orders";
        String idem = "xauusd-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return request(c.host, "POST", path, body, idem);
    }

    public Response cancelOrder(String orderId) throws Exception {
        Credentials c = loadCredentials();
        requireCredentials(c);
        String path = "/v1/trading/accounts/" + c.accountId + "/orders/" + orderId;
        String idem = "cancel-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return request(c.host, "DELETE", path, "", idem);
    }

    private void requireCredentials(Credentials c) {
        if (c.accountId.isEmpty() || c.apiKey.isEmpty() || c.secret.isEmpty()) {
            throw new IllegalStateException("Exness API credentials belum lengkap");
        }
    }

    private Response request(String host, String method, String path, String body, String idem) throws Exception {
        host = normalizeHost(host);
        if (body == null) body = "";
        long ts = System.currentTimeMillis();

        JSONObject data = new JSONObject();
        data.put("api_key", loadCredentials().apiKey);
        data.put("idempotency_key", idem == null ? "" : idem);
        data.put("timestamp", ts);
        data.put("sign_version", 1);
        data.put("method", method.toUpperCase(Locale.US));
        data.put("path", path);
        data.put("body_hash", sha256B64Url(body.getBytes(StandardCharsets.UTF_8)));

        byte[] signedBytes = data.toString().getBytes(StandardCharsets.UTF_8);
        String exnData = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signedBytes);
        String exnSign = sign(signedBytes, loadCredentials().secret);

        URL url = URI.create(host + path).toURL();
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(9000);
        con.setReadTimeout(9000);
        con.setRequestMethod(method);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("EXN-API-KEY", loadCredentials().apiKey);
        con.setRequestProperty("EXN-IDEMPOTENCY-KEY", idem == null ? "" : idem);
        con.setRequestProperty("EXN-TIMESTAMP", Long.toString(ts));
        con.setRequestProperty("EXN-SIGN-VERSION", "1");
        con.setRequestProperty("EXN-DATA", exnData);
        con.setRequestProperty("EXN-SIGN", exnSign);

        if ("POST".equals(method) || "PATCH".equals(method)) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            con.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        InputStream stream = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String response = read(stream);
        con.disconnect();
        return new Response(code, response);
    }

    private String sign(byte[] data, String secret) throws Exception {
        byte[] raw = decodeSecret(secret);
        if (raw.length < 32) throw new IllegalArgumentException("Secret key Ed25519 tidak valid");
        if (raw.length > 32) {
            byte[] tail = new byte[32];
            System.arraycopy(raw, raw.length - 32, tail, 0, 32);
            raw = tail;
        }
        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(raw, 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(data, 0, data.length);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature());
    }

    private byte[] decodeSecret(String secret) {
        String s = secret.trim();
        if (s.startsWith("-----BEGIN")) {
            s = s.replaceAll("-----BEGIN [^-]+-----", "")
                 .replaceAll("-----END [^-]+-----", "")
                 .replaceAll("\\s+", "");
        }
        try {
            if (s.matches("(?i)[0-9a-f]{64}")) {
                byte[] out = new byte[32];
                for (int i = 0; i < 32; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
                return out;
            }
            try { return java.util.Base64.getUrlDecoder().decode(s); }
            catch (IllegalArgumentException ignored) {}
            return Base64.decode(s, Base64.DEFAULT);
        } catch (Exception e) {
            throw new IllegalArgumentException("Format secret key tidak dikenali");
        }
    }

    private static String sha256B64Url(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) out.append(line);
        r.close();
        return out.toString();
    }

    public static String normalizeHost(String host) {
        String h = host.trim();
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "https://" + h;
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }
}
