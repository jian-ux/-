package com.feisheng.bot.common.exception;

import com.feisheng.bot.common.vo.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception e) {
        log.error("Error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(R.fail(500, "系统内部错误"));
    }
}
