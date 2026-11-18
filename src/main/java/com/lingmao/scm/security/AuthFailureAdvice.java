package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(-100)
public class AuthFailureAdvice {
    @ExceptionHandler(SecurityStateUnavailable.class)
    public ResponseEntity<R<Void>> unavailable(SecurityStateUnavailable failure) {
        return ResponseEntity.status(503).header("Retry-After", "3").body(R.fail(503, failure.getMessage()));
    }

    @ExceptionHandler(AuthRejected.class)
    public ResponseEntity<R<Void>> rejected(AuthRejected failure) {
        return ResponseEntity.status(401).body(R.fail(401, failure.getMessage()));
    }

    @ExceptionHandler(FieldPermissionDenied.class)
    public ResponseEntity<R<Void>> forbidden(FieldPermissionDenied failure) {
        return ResponseEntity.status(403).body(R.fail(403, failure.getMessage()));
    }

    @ExceptionHandler(com.lingmao.scm.common.scope.BoundaryViolation.class)
    public ResponseEntity<R<Void>> boundary(com.lingmao.scm.common.scope.BoundaryViolation failure) {
        return ResponseEntity.status(failure.getCode()).body(R.fail(failure.getCode(), failure.getMessage()));
    }
}
