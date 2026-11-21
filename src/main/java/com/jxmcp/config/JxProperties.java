package com.jxmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * jx.* 自定义配置：安全开关与工具行为参数。
 */
@ConfigurationProperties(prefix = "jx")
public class JxProperties {

    /** query 工具的强制 LIMIT 上限 */
    private int forcedLimit = 1000;

    /** 放开无 WHERE 的 UPDATE/DELETE（默认拒绝） */
    private boolean allowWhereLessMutation = false;

    /** http profile 的 Bearer Token；空 = 不鉴权 */
    private String httpToken;

    /** 向量写入分块大小（字符） */
    private int chunkSize = 800;

    /** 分块重叠（字符） */
    private int chunkOverlap = 120;

    public int getForcedLimit() {
        return forcedLimit;
    }

    public void setForcedLimit(int forcedLimit) {
        this.forcedLimit = forcedLimit;
    }

    public boolean isAllowWhereLessMutation() {
        return allowWhereLessMutation;
    }

    public void setAllowWhereLessMutation(boolean allowWhereLessMutation) {
        this.allowWhereLessMutation = allowWhereLessMutation;
    }

    public String getHttpToken() {
        return httpToken;
    }

    public void setHttpToken(String httpToken) {
        this.httpToken = httpToken;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }
}
