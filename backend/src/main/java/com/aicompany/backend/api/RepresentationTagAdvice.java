package com.aicompany.backend.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 32 -- TD-32: the entity-tag of a versioned resource is strong, so it
 * must change whenever the representation's bytes do, not only when its row
 * does. A controller sets the state tag ({@code "<version>"}, {@link ETags});
 * just before the body is written this turns it into
 * {@code "<version>.<digest of the body>"}. A response whose shape changed, or
 * that shows data from another row, now has a tag of its own.
 *
 * <p>Preconditions are unchanged: {@link Precondition} reads the version in front
 * of the dot and compares states, as ADR-009 decided, and still accepts the bare
 * {@code "<version>"} a client builds from a listing (TD-33).
 */
@RestControllerAdvice
public class RepresentationTagAdvice implements ResponseBodyAdvice<Object> {

    private static final Pattern STATE_TAG = Pattern.compile("\"(\\d+)\"");

    private final JsonMapper mapper;

    public RepresentationTagAdvice(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String tag = response.getHeaders().getETag();
        if (body == null || tag == null) {
            return body;
        }
        Matcher state = STATE_TAG.matcher(tag);
        if (state.matches()) {
            response.getHeaders().setETag(ETags.of(Long.parseLong(state.group(1)), digest(mapper.writeValueAsBytes(body))));
        }
        return body;
    }

    private static String digest(byte[] representation) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(representation);
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
