package com.lingmao.scm.module.techpack.service.impl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.function.Supplier;
/** PDF 的短数据库快照；文件读取、图片转换和渲染全部由调用方在返回后完成。 */
@Component @RequiredArgsConstructor
class PackReadTransactions {
    private final PlatformTransactionManager transactions;
    <T> T read(Supplier<T> loader){
        var tx=new TransactionTemplate(transactions);tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(30);
        return Objects.requireNonNull(tx.execute(status->loader.get()));
    }
}
