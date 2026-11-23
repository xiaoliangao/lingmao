package com.lingmao.scm.module.dev.core;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 无数据库阶段先解析真正 Mapper XML；执行、返回元数据和大快照验证仍必须由 S1SampleOwnerIT 完成。 */
class S1SampleMapperSqlTest {
    @Test
    void actualMapperResourcesBindLightOwnerProjectionAndSeekWithoutSnapshotColumns() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/dev/SampleMapper.xml", "mapper/dev/SampleOrderMapper.xml",
                "mapper/dev/SampleSelectionMapper.xml", "mapper/dev/SampleNodeMapper.xml", "mapper/dev/SampleReviewMapper.xml",
                "mapper/techpack/TechPackMapper.xml")) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        String prefix = TechPackMapper.class.getName() + ".";
        Map<String, Object> parameters = new HashMap<>(); parameters.put("tenant", "synthetic-deployment");
        parameters.put("companyId", 1L); parameters.put("sampleId", 2L); parameters.put("limit", 101);
        parameters.put("beforeVersion", 9);
        BoundSql lock = configuration.getMappedStatement(prefix + "lockSamplePacks").getBoundSql(parameters);
        String sql = lock.getSql().replaceAll("\\s+", " ").trim();
        assertThat(sql).doesNotContain("owner_snapshot", "p.*");
        String projected = sql.substring("SELECT ".length(), sql.indexOf(" FROM "));
        assertThat(projected.split(",")).containsExactly("p.id", "p.style_id", "p.sample_id", "s.company_id",
                "p.stage", "p.status", "p.version", "p.version_lock");
        assertThat(configuration.getMappedStatement(prefix + "lockSamplePacks").getResultMaps().getFirst().getType())
                .isEqualTo(TechPackMapper.SamplePackIdentity.class);
        BoundSql seek = configuration.getMappedStatement(prefix + "selectSamplePacks").getBoundSql(parameters);
        assertThat(seek.getSql()).doesNotContain("owner_snapshot", "p.*").contains("p.version < ?", "LIMIT ?");
        assertThat(seek.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("tenant", "companyId", "sampleId", "beforeVersion", "limit");
        parameters.put("beforeVersion", null);
        BoundSql first = configuration.getMappedStatement(prefix + "selectSamplePacks").getBoundSql(parameters);
        assertThat(first.getSql()).doesNotContain("p.version < ?");
        Path directory = Path.of("test-artifacts/full-audit/s1/sample"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("pack-bound-sql-static.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("verification", "BoundSql only; no PostgreSQL connection", "lockSql", lock.getSql(),
                        "listSeekSql", seek.getSql(), "listFirstSql", first.getSql(), "lockColumns", 8)));
    }
}
