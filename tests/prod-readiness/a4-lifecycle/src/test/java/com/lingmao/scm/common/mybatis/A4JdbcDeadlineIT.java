package com.lingmao.scm.common.mybatis;

import com.lingmao.scm.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class A4JdbcDeadlineIT {
    @Autowired DataSource dataSource;

    @Test void actualPooledConnectionsRestoreNetworkTimeoutAfterScopedBackgroundWork() throws Exception {
        var borrowed = new ArrayList<Connection>();
        int maximum = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        try {
            try (var ignored = JdbcNetworkDeadline.open(1500)) {
                for (int i = 0; i < maximum; i++) {
                    var connection = dataSource.getConnection(); borrowed.add(connection);
                    assertThat(connection.getNetworkTimeout()).isEqualTo(1500);
                }
            }
            for (var connection : borrowed) connection.close(); borrowed.clear();
            for (int i = 0; i < maximum; i++) {
                var connection = dataSource.getConnection(); borrowed.add(connection);
                assertThat(connection.getNetworkTimeout()).isZero();
            }
        } finally { for (var connection : borrowed) connection.close(); }
    }

    @Test void realPostgresRoundTripCannotOutliveTheClientNetworkDeadline() throws Exception {
        try (var ignored = JdbcNetworkDeadline.open(200); var connection = dataSource.getConnection();
             var query = connection.prepareStatement("SELECT pg_sleep(3)")) {
            long began = System.nanoTime();
            assertThatThrownBy(query::execute).isInstanceOf(SQLException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(2));
        }
    }
}
