package com.kugelaudio.sdk.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kugelaudio.sdk.AuthenticationException;
import com.kugelaudio.sdk.ConnectionException;
import com.kugelaudio.sdk.InsufficientCreditsException;
import com.kugelaudio.sdk.KugelAudioException;
import com.kugelaudio.sdk.NotFoundException;
import com.kugelaudio.sdk.RateLimitException;
import com.kugelaudio.sdk.ValidationException;

import java.net.http.HttpHeaders;
import java.net.http.WebSocketHandshakeException;
import java.util.Optional;

/**
 * Centralized classification of server errors into the right
 * {@link KugelAudioException} subclass.
 *
 * <p>Mirrors the server's {@code ErrorCode} enum (see
 * {@code models/tts/src/serving/deployments/errors.py}) so callers can switch on
 * {@link KugelAudioException#getErrorCode()} without matching on message
 * text.
 */
public final class Errors {

    public static final String CODE_UNAUTHORIZED = "UNAUTHORIZED";
    public static final String CODE_RATE_LIMITED = "RATE_LIMITED";
    public static final String CODE_INSUFFICIENT_CREDITS = "INSUFFICIENT_CREDITS";
    public static final String CODE_MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    public static final String CODE_EMPTY_AUDIO = "EMPTY_AUDIO";
    public static final String CODE_VALIDATION = "VALIDATION_ERROR";
    public static final String CODE_INTERNAL = "INTERNAL_ERROR";
    public static final String CODE_NOT_FOUND = "NOT_FOUND";
    public static final String CODE_MISSING_VOICE_ID = "MISSING_VOICE_ID";
    public static final String CODE_TOO_MANY_CONTEXTS = "TOO_MANY_CONTEXTS";

    public static final int WS_CLOSE_UNAUTHORIZED = 4001;
    public static final int WS_CLOSE_INSUFFICIENT_CREDITS = 4003;
    public static final int WS_CLOSE_RATE_LIMITED = 4029;
    public static final int WS_CLOSE_MODEL_UNAVAILABLE = 4500;

    private static final String API_KEYS_URL =
            "https://app.kugelaudio.com/settings/api-keys";

    private Errors() {}

    /**
     * True when a WebSocket close code represents a server-initiated error
     * that callers should surface. All other codes — including normal
     * closure (1000), going-away (1001), abnormal closure (1006), and any
     * other WS-spec code — are treated as end-of-stream and must NOT raise.
     *
     * <p>Mirrors the set used by the Python and JS SDKs so all three
     * behave identically when a load balancer or rolling deploy closes
     * a session with 1001/1006.
     */
    public static boolean isErrorCloseCode(int code) {
        return code == WS_CLOSE_UNAUTHORIZED
                || code == WS_CLOSE_INSUFFICIENT_CREDITS
                || code == WS_CLOSE_RATE_LIMITED
                || code == WS_CLOSE_MODEL_UNAVAILABLE;
    }

    /**
     * Build a {@link KugelAudioException} from a parsed HTTP error response.
     */
    public static KugelAudioException classifyHttp(
            int status,
            String body,
            HttpHeaders headers,
            ObjectMapper mapper) {
        String errorCode = null;
        String message = null;
        Integer retryAfter = null;

        if (body != null && !body.isBlank()) {
            try {
                JsonNode json = mapper.readTree(body);
                if (json.has("error_code") && json.get("error_code").isTextual()) {
                    errorCode = json.get("error_code").asText();
                }
                if (json.has("error") && json.get("error").isTextual()) {
                    message = json.get("error").asText();
                } else if (json.has("detail")) {
                    JsonNode detail = json.get("detail");
                    message = detail.isTextual() ? detail.asText() : detail.toString();
                }
                if (json.has("retry_after") && json.get("retry_after").canConvertToInt()) {
                    retryAfter = json.get("retry_after").asInt();
                }
            } catch (Exception ignored) {
                // Not JSON; fall back to raw body text.
            }
            if (message == null) {
                message = body;
            }
        }

        if (retryAfter == null && headers != null) {
            retryAfter = headers.firstValue("Retry-After")
                    .flatMap(Errors::parseIntOpt)
                    .orElse(null);
        }

        return build(status, errorCode, message, requestIdOf(headers), retryAfter, null);
    }

    /**
     * Build a {@link KugelAudioException} from a server-sent WebSocket
     * error frame (a JSON object with {@code error}, {@code error_code},
     * {@code code}, {@code request_id}).
     *
     * <p>WebSocket routes never run the HTTP middleware, so the correlation id
     * arrives in the frame body as {@code request_id} rather than as an
     * {@code x-request-id} header. It lands on the same
     * {@link KugelAudioException#getRequestId()} either way.
     */
    public static KugelAudioException classifyWsFrame(JsonNode data) {
        String errorCode = data.has("error_code") && data.get("error_code").isTextual()
                ? data.get("error_code").asText()
                : null;
        int status = data.has("code") && data.get("code").canConvertToInt()
                ? data.get("code").asInt()
                : 0;
        String message = data.has("error") && data.get("error").isTextual()
                ? data.get("error").asText()
                : "Server reported an error.";
        Integer retryAfter = data.has("retry_after") && data.get("retry_after").canConvertToInt()
                ? data.get("retry_after").asInt()
                : null;
        return build(status, errorCode, message, requestIdOf(data), retryAfter, null);
    }

    /**
     * Reads {@code x-request-id} from response headers. {@link HttpHeaders}
     * lookups are case-insensitive, so one lookup covers every casing.
     */
    public static String requestIdOf(HttpHeaders headers) {
        if (headers == null) return null;
        String value = headers.firstValue("x-request-id").orElse(null);
        return value == null || value.isBlank() ? null : value;
    }

    /** Reads the connection-scoped correlation id from a server frame. */
    public static String requestIdOf(JsonNode data) {
        if (data == null) return null;
        JsonNode node = data.get("request_id");
        if (node == null || !node.isTextual()) return null;
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * Build a {@link KugelAudioException} from a WebSocket close code
     * + reason string.
     */
    public static KugelAudioException classifyWsClose(int code, String reason) {
        String reasonTxt = reason == null ? "" : reason.strip();
        return switch (code) {
            case WS_CLOSE_UNAUTHORIZED -> {
                String msg = "KugelAudio rejected the API key. Check it is current at "
                        + API_KEYS_URL + ".";
                if (!reasonTxt.isEmpty()) msg = msg + " (" + reasonTxt + ")";
                yield new AuthenticationException(msg);
            }
            case WS_CLOSE_INSUFFICIENT_CREDITS -> new InsufficientCreditsException();
            case WS_CLOSE_RATE_LIMITED -> new RateLimitException();
            case WS_CLOSE_MODEL_UNAVAILABLE -> {
                String suffix = reasonTxt.isEmpty() ? "" : " (" + reasonTxt + ")";
                yield new ConnectionException(
                        "KugelAudio model is temporarily unavailable. Retry shortly." + suffix);
            }
            default -> {
                String detail = reasonTxt.isEmpty() ? "no reason given" : reasonTxt;
                yield new ConnectionException(
                        "KugelAudio WebSocket closed by server: " + detail
                                + " (code " + code + ").");
            }
        };
    }

    /**
     * Walk the cause chain of a thrown exception (typically an
     * {@link java.util.concurrent.ExecutionException} wrapping a
     * {@link WebSocketHandshakeException}) and return a typed
     * {@link KugelAudioException} if the failure carries an HTTP status.
     * Returns {@code null} if no handshake-rejection exception is found.
     *
     * <p>Ingress puts {@code x-request-id} on a rejected handshake (401, 429,
     * connection cap); it becomes the typed error's request id.
     *
     * <p>The TTS server rejects WS upgrades with a bare API key using
     * HTTP 403 (not 401), so 403 in the handshake path is classified as
     * {@link AuthenticationException}. The HTTP API path keeps the
     * generic 403 semantics via {@link #classifyHttp}.
     */
    public static KugelAudioException classifyWsHandshake(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof WebSocketHandshakeException wsh) {
                int status = wsh.getResponse().statusCode();
                String requestId = requestIdOf(wsh.getResponse().headers());
                if (status == 403) {
                    return new AuthenticationException(
                            AuthenticationException.DEFAULT_MESSAGE, 401, requestId, t);
                }
                return build(status, null, wsh.getMessage(), requestId, null, t);
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static KugelAudioException build(
            int status,
            String errorCode,
            String message,
            String requestId,
            Integer retryAfter,
            Throwable cause) {
        if (CODE_UNAUTHORIZED.equals(errorCode) || status == 401) {
            String msg = (message == null || message.isBlank())
                    ? AuthenticationException.DEFAULT_MESSAGE
                    : message;
            return new AuthenticationException(msg, status == 0 ? 401 : status, requestId, retryAfter, cause);
        }
        if (CODE_INSUFFICIENT_CREDITS.equals(errorCode) || status == 402) {
            String msg = (message == null || message.isBlank())
                    ? InsufficientCreditsException.DEFAULT_MESSAGE
                    : message;
            return new InsufficientCreditsException(msg, status == 0 ? 402 : status, requestId, retryAfter, cause);
        }
        if (CODE_RATE_LIMITED.equals(errorCode)
                || CODE_TOO_MANY_CONTEXTS.equals(errorCode)
                || status == 429) {
            String msg = message;
            if (msg == null || msg.isBlank()) {
                msg = retryAfter != null
                        ? "KugelAudio rate limit hit; retry after " + retryAfter + "s."
                        : "KugelAudio rate limit hit; retry shortly.";
            }
            return new RateLimitException(
                    msg,
                    status == 0 ? 429 : status,
                    errorCode == null ? CODE_RATE_LIMITED : errorCode,
                    requestId,
                    retryAfter,
                    cause);
        }
        if (CODE_VALIDATION.equals(errorCode)
                || CODE_MISSING_VOICE_ID.equals(errorCode)
                || status == 400) {
            String msg = (message == null || message.isBlank())
                    ? "Request validation failed."
                    : message;
            return new ValidationException(
                    msg,
                    status == 0 ? 400 : status,
                    errorCode == null ? CODE_VALIDATION : errorCode,
                    requestId,
                    retryAfter,
                    cause);
        }
        if (CODE_MODEL_UNAVAILABLE.equals(errorCode) || status == 503) {
            String detail = (message == null || message.isBlank())
                    ? "service temporarily unavailable"
                    : message;
            return new ConnectionException(
                    "KugelAudio is temporarily unavailable: " + detail + ". Retry shortly.",
                    status == 0 ? 503 : status,
                    errorCode,
                    requestId,
                    retryAfter,
                    cause);
        }
        if (CODE_NOT_FOUND.equals(errorCode) || status == 404) {
            String msg = (message == null || message.isBlank())
                    ? NotFoundException.DEFAULT_MESSAGE
                    : message;
            return new NotFoundException(msg, status == 0 ? 404 : status, requestId, retryAfter, cause);
        }
        String msg = (message == null || message.isBlank())
                ? "HTTP " + status
                : message;
        return new KugelAudioException(msg, status, errorCode, requestId, retryAfter, cause);
    }

    private static Optional<Integer> parseIntOpt(String s) {
        try {
            return Optional.of(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
