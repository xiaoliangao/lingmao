package com.lingmao.scm.common.idempotency;

import com.lingmao.scm.common.result.R;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(-100)
public final class IdempotencyExceptionAdvice {
    @ExceptionHandler(IdempotencyInProgress.class)
    public ResponseEntity<R<Void>> inProgress(IdempotencyInProgress failure) {
        return ResponseEntity.status(409).header("Retry-After", "1").body(R.fail(409, failure.getMessage()));
    }
}
