package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.service.ExportFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class A2ExportStorageIT {
    @Autowired ExportFileStore files;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DeploymentNamespace namespace;
    @TempDir Path separate;

    @Test void instancesMustUseTheSameDirectoryAndTheSecondInstanceReadsThePublishedFile() throws Exception {
        Path root = files.path(999999, UUID.randomUUID()).getParent();
        var second = new LocalExportFileStore(root.toString(), namespace, jdbc, transactions);
        var slot = files.allocate(999999, UUID.randomUUID().toString());
        try {
            try (var output = files.output(slot)) { output.write(new byte[]{1, 2, 3, 4}); }
            assertThat(files.publish(slot)).isEqualTo(4);
            assertThat(Files.readAllBytes(second.path(slot.jobId(), slot.token()))).containsExactly(1, 2, 3, 4);
            assertThatThrownBy(() -> new LocalExportFileStore(separate.toString(), namespace, jdbc, transactions))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("same private export directory");
        } finally { files.discard(slot); second.closeScan(); }
    }

    @Test void actualFileOutputStopsAt250MbAndTheFailureLeavesNoPartialArtifact() throws Exception {
        var slot = files.allocate(999998, UUID.randomUUID().toString());
        byte[] block = new byte[1024 * 1024];
        try {
            try (var output = files.output(slot)) {
                for (int i = 0; i < 250; i++) output.write(block);
                assertThat(Files.size(slot.partial())).isEqualTo(ExportFileStore.MAX_BYTES);
                assertThatThrownBy(() -> output.write(1)).isInstanceOf(JobFailure.class).hasMessage("SIZE_LIMIT");
            }
        } finally { files.discard(slot); }
        assertThat(Files.exists(slot.partial())).isFalse(); assertThat(Files.exists(slot.complete())).isFalse();
    }
}
