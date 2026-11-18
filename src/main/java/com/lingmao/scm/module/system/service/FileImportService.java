package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.excel.ImportResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.function.Function;
import java.util.function.Supplier;

/** 调用前已经查过业务权限和导入目标；解析从不在写事务里做。 */
public interface FileImportService {
    <P> ImportResult execute(String operation, String scope, String permission, MultipartFile file, Object options,
                            Supplier<P> parse, Function<P, ImportResult> write);
}
