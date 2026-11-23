package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.mapper.SampleSearch;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 捕获分页插件最终执行的 SQL 和实际参数，再对同一语句 EXPLAIN；不关闭 seqscan，不改查询凑索引。 */
@IntegrationTest
class S1SamplePlansIT extends SampleTestSupport {
    @Autowired SqlSessionFactory sessions;

    @Test
    void fiveThousandCompleteDraftsUseIndexedExactBatchLookupAndSqlScopeBeforePagination() throws Exception {
        Fixture f = fixture(); login(f.creator(), true);
        var all = new ArrayList<Created>();
        for (int batch = 0; batch < 50; batch++) transactions.executeWithoutResult(status -> {
            for (int i = 0; i < 100; i++) all.add(createDraftInTransaction(f));
        });
        Created a = all.get(123); Created b = all.get(4567);
        String keyword = a.sampleCode() + ", " + b.orderNo();
        Created nameOnly = draft(f);
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), nameOnly.sampleId(), DataScope.current(false), 0, null);
            lock.sample().setName(keyword); samples.updateSample(lock, lock.sample());
        });
        jdbc.execute("ANALYZE dev_sample"); jdbc.execute("ANALYZE dev_sample_order"); jdbc.execute("ANALYZE dev_sample_node");
        Capture capture = capture(); capture.start();
        var page = samples.page(f.company(), DataScope.current(false), search(keyword, null, null), 1, 20);
        List<Executed> calls = capture.finish();
        assertThat(page.getRecords()).extracting(row -> row.getId()).containsExactlyInAnyOrder(a.sampleId(), b.sampleId());
        assertThat(page.getTotal()).isEqualTo(2);
        // The COUNT wrapper also contains current_order_id and inner LATERAL LIMITs.
        // Select the top-level page statement explicitly, retaining every actual bound parameter.
        List<Executed> pageCalls = calls.stream().filter(S1SamplePlansIT::isTopLevelPage).toList();
        List<Executed> countCalls = calls.stream().filter(call -> !isTopLevelPage(call)).toList();
        assertThat(pageCalls).as("one actual top-level page SELECT").hasSize(1);
        assertThat(countCalls).as("one separate actual COUNT query").hasSize(1);
        Executed actual = pageCalls.getFirst();
        Executed actualCount = countCalls.getFirst();
        assertThat(actual.sql().stripLeading().toUpperCase(Locale.ROOT)).contains("UNION")
                .matches("(?s)^(SELECT|WITH)\\b.*\\bLIMIT\\s+\\?\\s*$");
        assertThat(actualCount.sql().toUpperCase(Locale.ROOT)).containsPattern("SELECT\\s+COUNT\\s*\\(\\*\\)");
        assertThat(actual.parameters().getLast()).isEqualTo(20L);
        assertThat(actual.sql()).doesNotContain("ILIKE");
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + actual.sql(), String.class, actual.parameters().toArray());
        String explain = String.join("\n", plan);
        assertThat(explain).containsPattern("(?i)(Index|Bitmap)");
        assertThat(explain).containsPattern("idx_dev_sample_code|uk_dev_sample_code");
        assertThat(explain).containsPattern("idx_dev_sample_order_no_company|uk_dev_sample_order_no");
        List<String> countPlan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + actualCount.sql(),
                String.class, actualCount.parameters().toArray());
        String pageJson = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + actual.sql(),
                String.class, actual.parameters().toArray());
        Map<String, Double> work = planWork(json.readTree(pageJson).get(0).path("Plan"));
        Path dir = Path.of("test-artifacts/full-audit/s1/sample"); Files.createDirectories(dir);
        // Persist the executed SQL and both plans before the work bound assertion, including real red controls.
        Files.writeString(dir.resolve("batch-codes-explain.txt"), explain + "\n");
        Files.writeString(dir.resolve("batch-codes-page-explain.json"), pageJson + "\n");
        Files.writeString(dir.resolve("batch-codes-count-explain.txt"), String.join("\n", countPlan) + "\n");
        evidence("batch-codes-query.json", Map.of("rowsSeeded", 5001, "sql", actual.sql(), "parameters", actual.parameters(),
                "matchedIds", page.getRecords().stream().map(row -> row.getId()).toList(), "excludedNameOnlyId", nameOnly.sampleId()));
        evidence("batch-codes-count-query.json", Map.of("sql", actualCount.sql(), "parameters", actualCount.parameters(),
                "total", page.getTotal()));
        evidence("batch-codes-page-work.json", Map.of("matchingHeads", 2, "work", work));
        assertThat(work).containsKeys("currentOrderLoops", "currentOrderRowsExamined", "currentNodeLoops", "sampleHeadRowsExamined");
        assertThat(work.get("currentOrderLoops")).as("latest-order LATERAL may only visit the two exact candidate heads").isBetween(1D, 2D);
        assertThat(work.get("currentOrderRowsExamined")).as("latest-order rows remain bounded by candidate heads").isLessThanOrEqualTo(2D);
        assertThat(work.get("currentNodeLoops")).as("node LATERAL may only visit the two exact candidate heads").isBetween(0D, 2D);
        assertThat(work.get("sampleHeadRowsExamined")).as("exact code lookup plus candidate heads must not scan all 5001 heads").isBetween(2D, 4D);

        String duplicateCodes = a.sampleCode() + " " + a.orderNo() + "," + b.orderNo() + "," + a.sampleCode();
        var deduplicated = samples.page(f.company(), DataScope.current(false), search(duplicateCodes, null, null), 1, 20);
        assertThat(deduplicated.getRecords()).extracting(row -> row.getId()).containsExactlyInAnyOrder(a.sampleId(), b.sampleId());
        assertThat(deduplicated.getTotal()).isEqualTo(2);
        assertThat(samples.counts(f.company(), DataScope.current(false), search(duplicateCodes, null, null))
                .stream().mapToLong(row -> row.total()).sum()).isEqualTo(2);
        String hundredCodes = String.join(",", all.subList(0, 100).stream().map(Created::sampleCode).toList());
        var hundred = samples.page(f.company(), DataScope.current(false), search(hundredCodes, null, null), 1, 100);
        assertThat(hundred.getRecords()).hasSize(100); assertThat(hundred.getTotal()).isEqualTo(100);

        dispatch(f, b.sampleId(), MakeWay.INTERNAL);
        LocalDate today = LocalDate.now(clock);
        assertThat(samples.page(f.company(), DataScope.current(false), search(duplicateCodes, "MAKING", today, today), 1, 20)
                .getRecords()).extracting(row -> row.getId()).containsExactly(b.sampleId());
        assertThat(samples.page(f.company(), DataScope.current(false), search(duplicateCodes, "WAIT_ASSIGN", today, today), 1, 20)
                .getRecords()).extracting(row -> row.getId()).containsExactly(a.sampleId());
        assertThat(samples.page(f.company(), DataScope.current(false), search(duplicateCodes, null, today.plusDays(1), null), 1, 20)
                .getRecords()).isEmpty();
        // Status badges intentionally ignore the selected status but keep batch/company/date filters.
        assertThat(samples.counts(f.company(), DataScope.current(false), search(duplicateCodes, "MAKING", today, today))
                .stream().mapToLong(row -> row.total()).sum()).isEqualTo(2);
        login(f.maker(), false);
        var own = samples.page(f.company(), DataScope.current(false), search(duplicateCodes, null, null), 1, 20);
        assertThat(own.getRecords()).extracting(row -> row.getId()).containsExactly(b.sampleId());
        assertThat(own.getTotal()).isEqualTo(1);
        assertThat(samples.counts(f.company(), DataScope.current(false), search(duplicateCodes, null, null)))
                .singleElement().satisfies(row -> { assertThat(row.status()).isEqualTo("MAKING"); assertThat(row.total()).isEqualTo(1); });
        login(f.otherUser(), false);
        assertThat(samples.page(f.company(), DataScope.current(false), search(keyword, null, null), 1, 20).getRecords()).isEmpty();
        assertThat(samples.counts(f.company(), DataScope.current(false), search(keyword, null, null))).isEmpty();
        login(f.creator(), true);
        assertThat(samples.page(f.otherCompany(), DataScope.current(false), search(keyword, null, null), 1, 20).getRecords()).isEmpty();
        evidence("batch-codes-semantics.json", Map.of("sameObjectSampleAndOrderCodeDeduplicated", true,
                "hundredInputCodesReturned", hundred.getTotal(), "selfMakerIds", own.getRecords().stream().map(row -> row.getId()).toList(),
                "statusAndDateFiltersPreserved", true, "otherCompanyRows", 0));
    }

    @Test
    void halfOpenBusinessDayIncludes2359AndLongSingleKeywordIsEmptyInsteadOfUnfiltered() throws Exception {
        Fixture f = fixture(); login(f.creator(), true);
        long last = datedDraft(f, OffsetDateTime.parse("2026-01-15T23:59:00+08:00"));
        long next = datedDraft(f, OffsetDateTime.parse("2026-01-16T00:00:00+08:00"));
        var day = samples.page(f.company(), DataScope.current(false), search(null, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15)), 1, 20);
        assertThat(day.getRecords()).extracting(row -> row.getId()).containsExactly(last).doesNotContain(next);
        assertThat(samples.page(f.company(), DataScope.current(false), search("x".repeat(101), null, null), 1, 20).getRecords()).isEmpty();
        assertThat(samples.counts(f.company(), DataScope.current(false), search("x".repeat(101), null, null))).isEmpty();
        String batch = String.join(",", java.util.stream.IntStream.range(0, 101).mapToObj(i -> "YY" + i).toList());
        assertThatThrownBy(() -> samples.page(f.company(), DataScope.current(false), search(batch, null, null), 1, 20))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> samples.page(f.company(), DataScope.current(false), search(null, null, null), 1, 101))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        evidence("sample-calendar-day.json", Map.of("date", "2026-01-15", "included2359", last, "excludedNextDay", next));
    }

    private long datedDraft(Fixture f, OffsetDateTime created) {
        return transactions.execute(status -> {
            var sample = sample(f); sample.setCode(unique("YY")); sample.setCreateTime(created); sample.setUpdateTime(created);
            samples.insertSample(sample); samples.replaceColors(sample, List.of(f.colors().getFirst().id()));
            var order = order(f, sample.getId(), 1); samples.insertOrder(order);
            samples.replaceSelection(order, List.of(f.colors().getFirst()), List.of("M")); return sample.getId();
        });
    }

    private static SampleSearch search(String keyword, LocalDate from, LocalDate to) {
        return search(keyword, null, from, to);
    }

    private static SampleSearch search(String keyword, String status, LocalDate from, LocalDate to) {
        return new SampleSearch(keyword, status, null, null, null, null, null, null, null,
                null, false, null, null, from, to, SampleSearch.Sort.CREATED_DESC);
    }

    private static boolean isTopLevelPage(Executed call) {
        // A valid statement ending in LIMIT ? has a top-level limit; inner LATERAL limits end with ')'.
        return call.sql().stripLeading().toUpperCase(Locale.ROOT).matches("(?s)^(SELECT|WITH)\\b.*\\bLIMIT\\s+\\?\\s*$");
    }

    private static Map<String, Double> planWork(JsonNode node) {
        Map<String, Double> result = new LinkedHashMap<>(); collectWork(node, result); return result;
    }

    private static void collectWork(JsonNode node, Map<String, Double> result) {
        if (node.has("Relation Name")) {
            String relation = node.path("Relation Name").stringValue(), alias = node.path("Alias").stringValue();
            double loops = number(node.path("Actual Loops"));
            double rows = (number(node.path("Actual Rows")) + number(node.path("Rows Removed by Filter"))
                    + number(node.path("Rows Removed by Index Recheck"))) * loops;
            if (relation.equals("dev_sample")) result.merge("sampleHeadRowsExamined", rows, Double::sum);
            if (relation.equals("dev_sample_order") && alias.equals("x")) {
                result.merge("currentOrderLoops", loops, Double::sum);
                result.merge("currentOrderRowsExamined", rows, Double::sum);
            }
            if (relation.equals("dev_sample_node") && alias.equals("n")) {
                result.merge("currentNodeLoops", loops, Double::sum);
                result.merge("currentNodeRowsExamined", rows, Double::sum);
            }
        }
        if (node.has("Plans")) for (JsonNode child : node.path("Plans")) collectWork(child, result);
    }

    private static double number(JsonNode node) {
        return node.isNumber() ? node.doubleValue() : 0D;
    }

    private Capture capture() {
        Configuration configuration = sessions.getConfiguration();
        synchronized (configuration) {
            for (Interceptor interceptor : configuration.getInterceptors()) if (interceptor instanceof Capture capture) return capture;
            Capture capture = new Capture(configuration); configuration.addInterceptor(capture); return capture;
        }
    }

    record Executed(String sql, List<Object> parameters) { }

    /** 只观察本线程真正 prepare 的最终语句，始终继续原调用；其他线程/其他测试不采集。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static final class Capture implements Interceptor {
        private final Configuration configuration;
        private final ThreadLocal<List<Executed>> current = new ThreadLocal<>();
        Capture(Configuration configuration) { this.configuration = configuration; }
        void start() { current.set(new ArrayList<>()); }
        List<Executed> finish() { var found = current.get(); current.remove(); return List.copyOf(found); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var collector = current.get();
            if (collector != null) {
                var bound = ((StatementHandler) invocation.getTarget()).getBoundSql();
                var meta = configuration.newMetaObject(bound.getParameterObject());
                var values = new ArrayList<Object>();
                for (var mapping : bound.getParameterMappings()) {
                    String property = mapping.getProperty();
                    values.add(bound.hasAdditionalParameter(property) ? bound.getAdditionalParameter(property) : meta.getValue(property));
                }
                collector.add(new Executed(bound.getSql(), java.util.Collections.unmodifiableList(values)));
            }
            return invocation.proceed();
        }
    }
}
