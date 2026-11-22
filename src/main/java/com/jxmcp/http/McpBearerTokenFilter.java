package com.jxmcp.http;

import com.jxmcp.config.JxProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * http profile 的可选 Bearer Token 鉴权：jx.http-token 为空 = 不鉴权（本地/内网）；
 * 非空 = /mcp 请求必须携带 Authorization: Bearer <token>，否则 401。
 */
@Component
public class McpBearerTokenFilter extends OncePerRequestFilter {

    private final JxProperties props;

    public McpBearerTokenFilter(JxProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return props.httpToken() == null || props.httpToken().isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = props.httpToken();
        String provided = request.getHeader("Authorization");
        if (provided != null && provided.startsWith("Bearer ")
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        provided.substring(7).getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid bearer token");
    }
}
