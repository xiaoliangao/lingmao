package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.excel.ImportResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.function.Function;
import java.util.function.Supplier;

/** Domain authorization and target checks precede this call; parsing never runs inside a write transaction. */
public interface FileImportService {
    <P> ImportResult execute(String operation, String scope, String permission, MultipartFile file, Object options,
                            Supplier<P> parse, Function<P, ImportResult> write);
}
