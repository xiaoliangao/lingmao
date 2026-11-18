package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

@RestControllerAdvice
@Order(-150)
public class InputBoundaryAdvice {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> malformed(HttpMessageNotReadableException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof RequestBodyTooLarge) return tooLarge();
        if (failure.getCause() instanceof UnrecognizedPropertyException)
            return ResponseEntity.badRequest().body(R.fail(400, "请求包含不支持的字段，请刷新后重试"));
        String field = failure.getCause() instanceof InvalidFormatException format ? safeFieldPath(format) : "";
        return ResponseEntity.badRequest().body(R.fail(400,
                field.isEmpty() ? "请求体格式不对" : "请求体格式不对：字段 " + field));
    }

    /** 留下有用的 DTO 字段位置，但不回显输入值、任意对象键和异常细节。 */
    private static String safeFieldPath(JacksonException failure) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : failure.getPath()) {
            String property = ref.getPropertyName();
            if (property != null) {
                if (!property.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) return "";
                if (!path.isEmpty()) path.append('.');
                path.append(property);
            } else if (ref.getIndex() >= 0 && ref.getIndex() <= 1_000_000) {
                path.append('[').append(ref.getIndex()).append(']');
            } else return "";
            if (path.length() > 256) return "";
        }
        return path.toString();
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> multipartTooLarge(MaxUploadSizeExceededException failure) { return tooLarge(); }
    private static ResponseEntity<R<Void>> tooLarge() {
        return ResponseEntity.status(413).body(R.fail(413, "请求内容超过大小限制，请减小文件或分批提交"));
    }
}
