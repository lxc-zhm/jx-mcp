package com.jxmcp.http;

import com.jxmcp.config.HttpLoggingFilterConfig;
import com.jxmcp.config.JxProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * http profile 的请求/响应详细日志：每个请求两条 INFO
 * <pre>
 * ==&gt; req-1a2b3c4d POST /mcp headers={content-type=application/json, authorization=***} body={...}
 * &lt;== req-1a2b3c4d 200 application/json (123ms) headers={...} body={...}
 * </pre>
 *
 * 实现要点：
 * <ul>
 *   <li>请求侧用 ContentCachingRequestWrapper 缓存 body（在链内被读取后可用，故日志统一在 finally 打，
 *       请求行与响应行共用同一 req-id 关联）</li>
 *   <li>响应侧不能使用 ContentCachingResponseWrapper——它是缓冲式的，会破坏 MCP 的 SSE 流式响应
 *       （text/event-stream 需要边写边下发）。这里用直通 + 有界捕获的 Tee 流：内容实时写往客户端，
 *       同时捕获一份前缀字节供日志使用</li>
 *   <li>Authorization/Cookie 等敏感头脱敏为 ***；body 超出 jx.http-log-max-body-chars 截断</li>
 *   <li>异常时响应行带 error= 类型，异常继续向上抛（不吞）</li>
 * </ul>
 *
 * 由 {@link HttpLoggingFilterConfig} 注册为最外层 filter（order 最高优先级，鉴权失败的 401 也留日志）；
 * jx.http-log-enabled=false 时整体跳过。
 */
@Slf4j
public class HttpLoggingFilter extends OncePerRequestFilter {

    /** 值不落盘的敏感头（请求与响应都适用） */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key");

    private static final String MASK = "***";
    private static final String EMPTY_BODY = "<empty>";

    private final JxProperties props;

    public HttpLoggingFilter(JxProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !props.httpLogEnabled();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        String id = "req-" + UUID.randomUUID().toString().substring(0, 8);
        long start = System.nanoTime();
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, requestCacheLimit());
        CapturingResponseWrapper res = new CapturingResponseWrapper(response, captureByteLimit());
        Exception failure = null;
        try {
            chain.doFilter(req, res);
        } catch (Exception ex) {
            failure = ex;
            throw ex;
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            drainUnreadBody(req);
            logRequest(id, req);
            logResponse(id, res, costMs, failure);
        }
    }

    /**
     * 链内未读的请求体补充缓存（如鉴权失败 401 短路时 transport 不读 body），
     * 避免请求行丢失 body。仅在 Content-Length 明确且缓存为空时 drain。
     */
    private static void drainUnreadBody(ContentCachingRequestWrapper req) {
        try {
            if (req.getContentAsByteArray().length == 0 && req.getContentLengthLong() > 0) {
                req.getInputStream().readAllBytes();
            }
        } catch (IOException ignored) {
            // 连接已断：body 记不上，保留其余字段
        }
    }

    /** 请求行：method、URI+query、headers、body（进入时 body 尚未读取，故在 finally 中打） */
    private void logRequest(String id, ContentCachingRequestWrapper req) {
        StringBuilder sb = new StringBuilder("==> ").append(id).append(' ')
                .append(req.getMethod()).append(' ').append(req.getRequestURI());
        if (req.getQueryString() != null) {
            sb.append('?').append(req.getQueryString());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        var names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, maskIfSensitive(name, String.join(", ", Collections.list(req.getHeaders(name)))));
        }
        String body = new String(req.getContentAsByteArray(), charsetOf(req.getCharacterEncoding()));
        sb.append(" headers=").append(headers)
                .append(" body=").append(truncate(body));
        log.info(sb.toString());
    }

    /** 响应行：status、content-type、耗时、headers、body（异常时追加 error= 类型） */
    private void logResponse(String id, CapturingResponseWrapper res, long costMs, Exception failure) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : res.getHeaderNames()) {
            headers.put(name, maskIfSensitive(name, String.join(", ", res.getHeaders(name))));
        }
        StringBuilder sb = new StringBuilder("<== ").append(id).append(' ')
                .append(res.getStatus()).append(' ')
                .append(res.getContentType() != null ? res.getContentType() : "-")
                .append(" (").append(costMs).append("ms)")
                .append(" headers=").append(headers)
                .append(" body=").append(truncate(res.capturedAsString()));
        if (failure != null) {
            sb.append(" error=").append(failure.getClass().getName());
        }
        log.info(sb.toString());
    }

    private static String maskIfSensitive(String name, String value) {
        return SENSITIVE_HEADERS.contains(name.toLowerCase()) ? MASK : value;
    }

    private String truncate(String body) {
        if (body.isEmpty()) {
            return EMPTY_BODY;
        }
        int max = props.httpLogMaxBodyChars();
        if (max <= 0 || body.length() <= max) {  // <=0 表示不截断
            return body;
        }
        return body.substring(0, max) + "...(truncated, total " + body.length() + " chars)";
    }

    /** 捕获字节数上限：UTF-8 下每字符最多 4 字节；maxBodyChars<=0 表示不截断 */
    private int requestCacheLimit() {
        return props.httpLogMaxBodyChars() <= 0 ? Integer.MAX_VALUE : props.httpLogMaxBodyChars() * 4;
    }

    private int captureByteLimit() {
        return requestCacheLimit();
    }

    private static Charset charsetOf(String name) {
        if (name == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (Exception ex) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 响应包装：直通写出（SSE 安全）+ 有界捕获 */
    private static final class CapturingResponseWrapper extends HttpServletResponseWrapper {

        private final ByteArrayOutputStream captured;
        private final int byteLimit;
        private TeeServletOutputStream tee;
        private PrintWriter writer;

        CapturingResponseWrapper(HttpServletResponse response, int byteLimit) {
            super(response);
            this.byteLimit = byteLimit;
            this.captured = new ByteArrayOutputStream();
        }

        String capturedAsString() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (tee == null) {
                tee = new TeeServletOutputStream(super.getOutputStream(), captured, byteLimit);
            }
            return tee;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getCharacterEncoding()), false);
            }
            return writer;
        }
    }

    /** 直通 + 有界捕获的输出流：flush 全部委托，不缓冲，SSE 事件即时下发 */
    private static final class TeeServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final ByteArrayOutputStream captured;
        private final int byteLimit;

        TeeServletOutputStream(ServletOutputStream delegate, ByteArrayOutputStream captured, int byteLimit) {
            this.delegate = delegate;
            this.captured = captured;
            this.byteLimit = byteLimit;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            if (captured.size() < byteLimit) {
                captured.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            int n = Math.min(len, byteLimit - captured.size());
            if (n > 0) {
                captured.write(b, off, n);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }
    }
}
