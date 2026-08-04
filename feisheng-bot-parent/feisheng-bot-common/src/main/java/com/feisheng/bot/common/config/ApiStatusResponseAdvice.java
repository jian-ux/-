package com.feisheng.bot.common.config;

import com.feisheng.bot.common.vo.R;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Keeps the HTTP status aligned with the application's response envelope. */
@RestControllerAdvice
public class ApiStatusResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof R<?> result && result.getCode() >= 400) {
            HttpStatus status = HttpStatus.resolve(result.getCode());
            response.setStatusCode(status == null ? HttpStatus.BAD_REQUEST : status);
        }
        return body;
    }
}
