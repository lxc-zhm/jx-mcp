package com.jxmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * jx.* 自定义配置：安全开关与工具行为参数。
 */
@ConfigurationProperties(prefix = "jx")
public record JxProperties(

        /** query 工具的强制 LIMIT 上限 */
        @DefaultValue("1000") int forcedLimit,

        /** 放开无 WHERE 的 UPDATE/DELETE（默认拒绝） */
        @DefaultValue("false") boolean allowWhereLessMutation,

        /** http profile 的 Bearer Token；空 = 不鉴权 */
        String httpToken,

        /** 向量写入分块大小（字符） */
        @DefaultValue("800") int chunkSize,

        /** 分块重叠（字符） */
        @DefaultValue("120") int chunkOverlap,

        /** http 模式请求/响应详细日志开关（见 HttpLoggingFilter） */
        @DefaultValue("true") boolean httpLogEnabled,

        /** 详细日志中请求/响应体的最大字符数（超出截断） */
        @DefaultValue("2048") int httpLogMaxBodyChars
) {
}
