package com.lingmao.scm;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SqlStatementCaptureIT {
    @Autowired DataSource dataSource;
    @Autowired SqlSessionFactory applicationFactory;

    @Test
    void capturesCountAndPageWhenAnotherConfigurationAlreadyWarmedPagination() {
        assertThat(applicationFactory.getConfiguration().getInterceptors())
                .filteredOn(SqlStatementCapture.class::isInstance).hasSize(1);
        String statementId = "capture." + UUID.randomUUID() + ".selectPage";
        SqlSessionFactory first = factory(statementId);
        SqlSessionFactory second = factory(statementId);
        assertThat(query(first, statementId).total()).isEqualTo(10);

        // Each factory has its own interceptor, while the cached COUNT statement may
        // still use the first configuration. Every query opens a fresh SqlSession.
        var captured = SqlStatementCapture.capture(() -> query(second, statementId));
        assertThat(captured.result().total()).isEqualTo(10);
        assertThat(captured.result().ids()).containsExactlyElementsOf(LongStream.rangeClosed(1, 3).boxed().toList());
        assertThat(captured.statements()).hasSize(2);
        assertThat(captured.statements().getFirst().getSql()).containsIgnoringCase("count(");
        assertThat(captured.statements().getLast().getSql()).containsIgnoringCase("limit");
    }

    private SqlSessionFactory factory(String statementId) {
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("sql-capture-test", new JdbcTransactionFactory(), dataSource));
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(plugins);
        configuration.addInterceptor(new SqlStatementCapture());
        var resultMap = new ResultMap.Builder(configuration, statementId + "Result", Long.class, List.of()).build();
        configuration.addMappedStatement(new MappedStatement.Builder(configuration, statementId,
                new StaticSqlSource(configuration, "SELECT n::bigint AS id FROM generate_series(1, 10) AS n ORDER BY n"),
                SqlCommandType.SELECT).resultMaps(List.of(resultMap)).build());
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private Result query(SqlSessionFactory factory, String statementId) {
        Page<Long> page = new Page<>(1, 3);
        try (var session = factory.openSession()) {
            List<Long> rows = session.selectList(statementId, Map.of("page", page));
            return new Result(page.getTotal(), rows);
        }
    }

    private record Result(long total, List<Long> ids) { }
}
