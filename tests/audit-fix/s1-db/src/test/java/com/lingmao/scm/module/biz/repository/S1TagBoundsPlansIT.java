package com.lingmao.scm.module.biz.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.core.TagName;
import com.lingmao.scm.module.biz.entity.Tag;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class S1TagBoundsPlansIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired TransactionTemplate transactions;
    @Autowired TagRepository tags;
    @Autowired DeploymentNamespace namespace;
    @Autowired JsonMapper json;

    @Test
    void boundedRealPrefixQueryUsesItsIndexWithoutPlannerHintsAndNeverReturnsMoreThan100() throws Exception {
        String bulk = unique("bulk"); String needle = unique("needle");
        jdbc.update("""
                INSERT INTO biz_tag(tenant_key,name,canonical)
                SELECT ?,?||lpad(n::text,6,'0'),?||lpad(n::text,6,'0') FROM generate_series(1,20000) n
                """, namespace.tenant(), bulk, bulk);
        jdbc.update("""
                INSERT INTO biz_tag(tenant_key,name,canonical)
                SELECT ?,?||lpad(n::text,6,'0'),?||lpad(n::text,6,'0') FROM generate_series(1,150) n
                """, namespace.tenant(), needle, needle);
        jdbc.execute("ANALYZE biz_tag");
        List<Tag> first = tags.searchActive(needle, 100);
        assertThat(first).hasSize(100);
        assertThat(first).extracting(Tag::canonical).isSorted();
        assertThat(first.getFirst().canonical()).isEqualTo(needle + "000001");
        assertThat(first.getLast().canonical()).isEqualTo(needle + "000100");
        var args = new MapSqlParameterSource("tenant", namespace.tenant())
                .addValue("prefix", TagRepository.literalPrefix(TagName.prefix(needle))).addValue("limit", 100);
        List<String> plan = named.query("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + TagRepository.SEARCH_SQL,
                args, (rs, row) -> rs.getString(1));
        assertThat(String.join("\n", plan)).contains("idx_biz_tag_active_prefix");
        assertThat(plan.getFirst()).contains("Limit").contains("rows=100");
        List<Long> include = new ArrayList<>(first.stream().map(Tag::id).toList());
        Collections.reverse(include);
        assertThat(tags.findByIds(include)).extracting(Tag::id).containsExactlyElementsOf(include);
        List<Long> excluded = include.subList(0, 19);
        List<Tag> remainder = tags.searchActive(needle, 81, excluded);
        assertThat(remainder).hasSize(81).noneMatch(tag -> excluded.contains(tag.id()));
        assertThat(tags.searchActive("", 100)).hasSize(100);
        assertThatThrownBy(() -> tags.searchActive(needle, 101)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tags.searchActive(needle, 0)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tags.findByIds(Collections.nCopies(101, first.getFirst().id()))).isInstanceOf(BizException.class);
        Path path = Path.of("target/a1-tags/prefix-explain.txt"); Files.createDirectories(path.getParent());
        Files.writeString(path, String.join("\n", plan));
        Files.writeString(path.resolveSibling("prefix-query-results.json"), json.writeValueAsString(Map.of(
                "seedRows", 20150, "prefix", needle, "limit100", first, "includeOrder", include,
                "remainingBudget", 81, "remaining", remainder)));
    }

    @Test
    void percentUnderscoreAndBackslashAreLiteralPrefixCharacters() {
        String prefix = unique("literal"); String literal = prefix + "%_\\tail";
        long literalId = insertTag(literal);
        insertTag(prefix + "anything-tail");
        insertTag(prefix + "%X\\tail");
        assertThat(tags.searchActive(prefix + "%_\\", 100)).extracting(Tag::id).containsExactly(literalId);
        assertThat(TagRepository.literalPrefix("%_\\")).isEqualTo("\\%\\_\\\\%");
    }

    @Test
    void getDoesNotWriteAndThirtyOneInputsOrResolvedLinksRollBackCompletely() {
        long owner = supplier(); var target = new TagRepository.Target(TagObjectType.SUPPLIER, owner, null);
        assertThat(tags.read(target)).isEqualTo(new TagRepository.Snapshot(0, List.of()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_object WHERE object_type='SUPPLIER' AND object_id=?",
                Integer.class, owner)).isZero();
        List<String> tooMany = IntStream.range(0, 31).mapToObj(index -> "limit-" + index).toList();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                tags.replaceBindings(target, 0, tooMany, Map.of(), List.of())))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(400));
        String prefix = unique("retain");
        List<String> names = IntStream.range(0, 30).mapToObj(index -> prefix + index).toList();
        var first = transactions.execute(status -> tags.replaceBindings(target, 0, names, Map.of(), List.of()).after());
        long archived = first.links().getFirst().tag().id(); long renamed = first.links().get(1).tag().id();
        transactions.executeWithoutResult(status -> { tags.archive(archived, 0); tags.rename(renamed, 0, names.getFirst()); });
        var before = tags.read(target);
        var requested = new ArrayList<>(before.links().stream().map(link -> link.tag().name()).distinct().toList());
        String extra = unique("overflow"); requested.add(extra);
        assertThat(requested).hasSize(30);
        var versions = new HashMap<Long, Integer>();
        before.links().forEach(link -> versions.put(link.tag().id(), link.tag().version()));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> tags.replaceBindings(target, before.version(),
                requested, versions, before.links().stream().map(link -> link.tag().id()).toList())))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(400));
        assertThat(tags.read(target)).isEqualTo(before);
        assertThat(tags.searchActive(extra, 1)).isEmpty();
    }

    private long insertTag(String name) {
        return jdbc.queryForObject("INSERT INTO biz_tag(tenant_key,name,canonical) VALUES (?,?,?) RETURNING id",
                Long.class, namespace.tenant(), name, name);
    }
    private long supplier() {
        String code = unique("TB");
        return jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name) VALUES (?,?,?) RETURNING id", Long.class, code, code, code);
    }
    private static String unique(String prefix) { return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
}
