package com.lingmao.scm;

import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.module.system.service.FileImportService;
import org.springframework.web.multipart.MultipartFile;
import java.util.function.Function;
import java.util.function.Supplier;

/** Unit-only adapter. Real transaction/hash/permission behavior is covered by A2 PostgreSQL HTTP tests. */
public final class DirectFileImports implements FileImportService {
    public static final DirectFileImports INSTANCE = new DirectFileImports();
    private DirectFileImports() { }
    @Override public <P> ImportResult execute(String operation, String scope, String permission, MultipartFile file,
                                              Object options, Supplier<P> parse, Function<P, ImportResult> write) {
        return write.apply(parse.get());
    }
}
