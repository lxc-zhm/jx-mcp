package com.jxmcp.config;

import com.jxmcp.http.HttpLoggingFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/**
 * http profile 专用的请求/响应详细日志 filter 注册。
 *
 * order = HIGHEST_PRECEDENCE：挂在最外层，鉴权失败（401）的请求/响应同样留痕；
 * stdio / reembed profile 无 web 容器，不注册。
 */
@Configuration
@Profile("http")
public class HttpLoggingFilterConfig {

    @Bean
    public FilterRegistrationBean<HttpLoggingFilter> httpLoggingFilter(JxProperties props) {
        FilterRegistrationBean<HttpLoggingFilter> registration =
                new FilterRegistrationBean<>(new HttpLoggingFilter(props));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
