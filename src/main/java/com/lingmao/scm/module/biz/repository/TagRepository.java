package com.lingmao.scm.module.biz.repository;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.core.TagName;
import com.lingmao.scm.module.biz.core.TagNameLocks;
import com.lingmao.scm.module.biz.entity.Tag;
import com.lingmao.scm.module.biz.entity.TagLink;
import com.lingmao.scm.module.biz.entity.TagObject;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.security.LoginUser;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 标签持久化事务核心。只写 biz_tag*；所有命名空间取自可信部署配置，公开方法不接收 tenant。
 * Service 必须先通过真实领域 Provider 做权限/DataScope 校验并锁 owner，再调用写方法。
 * 这些方法加入调用方事务：Idempotency-Key、业务日志及 commit 后缓存失效由同一 Service 编排。
 */
@Repository
public class TagRepository {
    private static final int MAX_OPTIONS = 100;
    private static final String TAG_COLUMNS = "id,tenant_key,name,canonical,version,deleted_token";
    private static final String LINK_COLUMNS = """
            l.id AS link_id,l.object_ref_id,l.seq,t.id,t.tenant_key,t.name,t.canonical,t.version,t.deleted_token
            """;
    // 供真实 EXPLAIN 测试复用相同 SQL；prefix 是规范化并转义后的字面前缀，不接受排序片段。
    static final String SEARCH_SQL = """
            SELECT id,tenant_key,name,canonical,version,deleted_token FROM biz_tag
            WHERE tenant_key=:tenant AND deleted_token=0 AND canonical LIKE :prefix ESCAPE '\\'
            ORDER BY canonical,id LIMIT :limit
            """;
    static final String SEARCH_EXCLUDING_SQL = """
            SELECT id,tenant_key,name,canonical,version,deleted_token FROM biz_tag
            WHERE tenant_key=:tenant AND deleted_token=0 AND canonical LIKE :prefix ESCAPE '\\'
              AND id NOT IN (:excluded)
            ORDER BY canonical,id LIMIT :limit
            """;
    private static final RowMapper<Tag> TAG_MAPPER = (rs, row) -> tag(rs);
    private static final RowMapper<TagObject> OBJECT_MAPPER = (rs, row) -> new TagObject(rs.getLong("id"),
            rs.getString("tenant_key"), TagObjectType.valueOf(rs.getString("object_type")),
            rs.getLong("object_id"), rs.getObject("company_id", Long.class), rs.getInt("version"));
    private static final RowMapper<TagLink> LINK_MAPPER = (rs, row) ->
            new TagLink(rs.getLong("link_id"), rs.getLong("object_ref_id"), rs.getInt("seq"), tag(rs));

    private final NamedParameterJdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final TagNameLocks nameLocks;

    public TagRepository(NamedParameterJdbcTemplate jdbc, DeploymentNamespace namespace, TagNameLocks nameLocks) {
        this.jdbc = jdbc;
        this.namespace = namespace;
        this.nameLocks = nameLocks;
    }

    /** companyId 取 Provider 查到的真实父头，不取请求头猜测；无主体的共享主数据为 null。 */
    public record Target(TagObjectType type, long id, Long companyId) {
        public Target {
            if (type == null || id <= 0 || companyId != null && companyId <= 0) {
                throw new BizException(400, "标签对象参数不合法");
            }
        }
    }

    public record Snapshot(int version, List<TagLink> links) {
        public Snapshot { links = List.copyOf(links); }
    }

    /** 新建/改名/归档标签才改变字典；无论是否新建，关联写都要在 commit 后失效对应对象缓存。 */
    public record Change(Snapshot before, Snapshot after, boolean libraryChanged) { }

    /** GET 不创建 registry；单条 JOIN 的 MVCC 快照保证 version 与标签来自同一次读取。 */
    public Snapshot read(Target target) {
        var parameters = targetParameters(target);
        return jdbc.query("""
                SELECT o.id AS registry_id,o.company_id,o.version AS object_version,
                       l.id AS link_id,l.object_ref_id,l.seq,
                       t.id,t.tenant_key,t.name,t.canonical,t.version,t.deleted_token
                FROM biz_tag_object o
                LEFT JOIN biz_tag_link l ON l.tenant_key=o.tenant_key AND l.object_ref_id=o.id
                LEFT JOIN biz_tag t ON t.tenant_key=l.tenant_key AND t.id=l.tag_id
                WHERE o.tenant_key=:tenant AND o.object_type=:type AND o.object_id=:owner
                ORDER BY l.seq,l.id LIMIT 31
                """, parameters, rs -> {
            if (!rs.next()) return new Snapshot(0, List.of());
            if (!Objects.equals(target.companyId(), rs.getObject("company_id", Long.class))) throw changed();
            int version = rs.getInt("object_version");
            var links = new ArrayList<TagLink>();
            do {
                if (rs.getObject("link_id") != null) links.add(LINK_MAPPER.mapRow(rs, links.size()));
            } while (rs.next());
            assertLinkBudget(links.size());
            return new Snapshot(version, links);
        });
    }

    public List<Tag> searchActive(String keyword, int size) {
        return searchActive(keyword, size, List.of());
    }

    /** includeIds 已占的预算由 Service 扣除；SQL 先排除已有 ID，再 LIMIT remaining，不先取全量。 */
    public List<Tag> searchActive(String keyword, int size, Collection<Long> excludedIds) {
        requireOptionSize(size);
        List<Long> excluded = optionIds(excludedIds);
        String prefix = literalPrefix(TagName.prefix(keyword));
        var args = parameters().addValue("prefix", prefix).addValue("limit", size);
        return jdbc.query(excluded.isEmpty() ? SEARCH_SQL : SEARCH_EXCLUDING_SQL,
                args.addValue("excluded", excluded), TAG_MAPPER);
    }

    /** 筛选可以选择历史归档标签；仅查共享字典，不返回关联对象或命中数量。 */
    public List<Tag> searchForFilter(String keyword, int size, Collection<Long> excludedIds) {
        requireOptionSize(size);
        List<Long> excluded = optionIds(excludedIds);
        var args = parameters().addValue("prefix", literalPrefix(TagName.prefix(keyword)))
                .addValue("limit", size).addValue("excluded", excluded);
        String sql = "SELECT " + TAG_COLUMNS + " FROM biz_tag WHERE tenant_key=:tenant AND canonical LIKE :prefix ESCAPE '\\'"
                + (excluded.isEmpty() ? "" : " AND id NOT IN (:excluded)") + " ORDER BY canonical,id LIMIT :limit";
        return jdbc.query(sql, args, TAG_MAPPER);
    }

    /** 仅库字典，不暴露目标信息。Service 仍需 biz:tag:list；允许回显归档标签。 */
    public List<Tag> findByIds(Collection<Long> ids) {
        List<Long> bounded = optionIds(ids);
        if (bounded.isEmpty()) return List.of();
        List<Tag> rows = jdbc.query("SELECT " + TAG_COLUMNS + " FROM biz_tag WHERE tenant_key=:tenant AND id IN (:ids) ORDER BY id LIMIT 100",
                parameters().addValue("ids", bounded), TAG_MAPPER);
        if (rows.size() != bounded.size()) throw missing();
        var byId = new HashMap<Long, Tag>();
        rows.forEach(tag -> byId.put(tag.id(), tag));
        return bounded.stream().map(byId::get).toList();
    }

    public Tag get(long id) {
        requireId(id);
        List<Tag> rows = jdbc.query("SELECT " + TAG_COLUMNS + " FROM biz_tag WHERE tenant_key=:tenant AND id=:id",
                parameters().addValue("id", id), TAG_MAPPER);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    /**
     * retainIds 精确选择保留的旧 link。某 canonical 有保留 ID 时只保留这些 ID；否则选/建活跃标签。
     * tagVersions 必须精确覆盖全部当前标签，即使本次将移除它，也不能绕过全局改名/归档的冲突。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Change replaceBindings(Target target, int expectedVersion, List<String> names,
                                  Map<Long, Integer> tagVersions, List<Long> retainIds) {
        requireTransaction();
        requireVersion(expectedVersion);
        List<TagName> normalized = TagName.distinct(names);
        Map<Long, Integer> expectedTags = checkedVersions(tagVersions);
        List<Long> retained = retainedIds(retainIds);
        return translating(() -> replace(target, expectedVersion, normalized, expectedTags, retained));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Tag rename(long tagId, int expectedVersion, String input) {
        requireTransaction(); requireId(tagId); requireVersion(expectedVersion);
        TagName name = TagName.of(input);
        return translating(() -> {
            Tag observed = get(tagId);
            if (observed.archived() || observed.version() != expectedVersion) throw changed();
            nameLocks.acquire(List.of(observed.canonical(), name.canonical()));
            Tag locked = lockTags(List.of(tagId)).get(tagId);
            if (locked == null || !locked.equals(observed)) throw changed();
            List<Tag> conflicts = activeNames(List.of(name.canonical()));
            if (conflicts.stream().anyMatch(tag -> tag.id() != tagId)) {
                throw new BizException(ErrorCodes.BIZ_ERROR, "已存在同名的活跃标签");
            }
            List<Tag> updated = jdbc.query("""
                    UPDATE biz_tag SET name=:name,canonical=:canonical,version=version+1,
                        update_by=:actor,update_time=clock_timestamp()
                    WHERE tenant_key=:tenant AND id=:id AND deleted_token=0
                      AND version=:version AND version<2147483647
                    RETURNING id,tenant_key,name,canonical,version,deleted_token
                    """, parameters().addValue("id", tagId).addValue("version", expectedVersion)
                    .addValue("name", name.display()).addValue("canonical", name.canonical()), TAG_MAPPER);
            if (updated.size() != 1) throw changed();
            return updated.getFirst();
        });
    }

    /** 归档只锁当前 tag，不反向锁 registry，不会改写历史 link。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Tag archive(long tagId, int expectedVersion) {
        requireTransaction(); requireId(tagId); requireVersion(expectedVersion);
        return translating(() -> {
            Tag locked = lockTags(List.of(tagId)).get(tagId);
            if (locked == null) throw missing();
            if (locked.archived() || locked.version() != expectedVersion) throw changed();
            List<Tag> updated = jdbc.query("""
                    UPDATE biz_tag SET deleted_token=id,version=version+1,
                        update_by=:actor,update_time=clock_timestamp()
                    WHERE tenant_key=:tenant AND id=:id AND deleted_token=0
                      AND version=:version AND version<2147483647
                    RETURNING id,tenant_key,name,canonical,version,deleted_token
                    """, parameters().addValue("id", tagId).addValue("version", expectedVersion), TAG_MAPPER);
            if (updated.size() != 1) throw changed();
            return updated.getFirst();
        });
    }

    private Change replace(Target target, int expectedVersion, List<TagName> names,
                           Map<Long, Integer> expectedTags, List<Long> retained) {
        // Provider 必须已锁定 owner；数据库重复核实主体，堵住遗漏 Provider 锁/主体比较的接线错误。
        Long actualCompany = jdbc.queryForObject("SELECT scm_tag_lock_owner(:type,:owner)", targetParameters(target), Long.class);
        if (!Objects.equals(actualCompany, target.companyId())) throw changed();
        jdbc.update("""
                INSERT INTO biz_tag_object(tenant_key,object_type,object_id,company_id,create_by,update_by)
                VALUES(:tenant,:type,:owner,:company,:actor,:actor)
                ON CONFLICT (tenant_key,object_type,object_id) DO NOTHING
                """, targetParameters(target));
        List<TagObject> registries = jdbc.query("""
                SELECT id,tenant_key,object_type,object_id,company_id,version FROM biz_tag_object
                WHERE tenant_key=:tenant AND object_type=:type AND object_id=:owner FOR UPDATE
                """, targetParameters(target), OBJECT_MAPPER);
        if (registries.size() != 1) throw changed();
        TagObject object = registries.getFirst();
        if (object.version() != expectedVersion || !Objects.equals(object.companyId(), target.companyId())) throw changed();
        List<TagLink> observed = links(object.id());
        checkExpectedTags(observed, expectedTags);
        var involvedNames = new LinkedHashSet<String>();
        observed.forEach(link -> involvedNames.add(link.tag().canonical()));
        names.forEach(name -> involvedNames.add(name.canonical()));
        nameLocks.acquire(involvedNames);
        List<TagLink> afterNamesLock = links(object.id());
        if (!observed.equals(afterNamesLock)) throw changed();
        List<Tag> active = activeNames(names.stream().map(TagName::canonical).toList());
        var ids = new TreeSet<Long>();
        observed.forEach(link -> ids.add(link.tag().id()));
        active.forEach(tag -> ids.add(tag.id()));
        Map<Long, Tag> locked = lockTags(ids);
        // Archive 不拿名称锁；所以取得标签行锁后还必须确认库状态与此前快照一致。
        for (TagLink link : observed) if (!link.tag().equals(locked.get(link.tag().id()))) throw changed();
        for (Tag tag : active) if (!tag.equals(locked.get(tag.id()))) throw changed();
        checkExpectedTags(observed, expectedTags);

        var currentByTag = new HashMap<Long, TagLink>();
        observed.forEach(link -> currentByTag.put(link.tag().id(), link));
        Set<String> requestedNames = new HashSet<>();
        names.forEach(name -> requestedNames.add(name.canonical()));
        var retainedByName = new LinkedHashMap<String, List<TagLink>>();
        for (long id : retained) {
            TagLink link = currentByTag.get(id);
            if (link == null || !requestedNames.contains(link.tag().canonical())) {
                throw new BizException(400, "保留标签必须已关联当前对象，并包含在本次名称中");
            }
            retainedByName.computeIfAbsent(link.tag().canonical(), ignored -> new ArrayList<>()).add(link);
        }
        var activeByName = new HashMap<String, Tag>();
        active.forEach(tag -> activeByName.put(tag.canonical(), tag));
        var desired = new ArrayList<Tag>();
        boolean created = false;
        for (TagName name : names) {
            List<TagLink> exact = retainedByName.get(name.canonical());
            if (exact != null) {
                exact.forEach(link -> desired.add(link.tag()));
            } else {
                Tag tag = activeByName.get(name.canonical());
                if (tag == null) {
                    tag = create(name);
                    activeByName.put(name.canonical(), tag);
                    created = true;
                }
                desired.add(tag);
            }
            assertLinkBudget(desired.size());
        }
        int changed = jdbc.update("""
                UPDATE biz_tag_object SET version=version+1,update_by=:actor,update_time=clock_timestamp()
                WHERE tenant_key=:tenant AND id=:id AND version=:version AND version<2147483647
                """, parameters().addValue("id", object.id()).addValue("version", expectedVersion));
        if (changed != 1) throw changed();
        saveLinks(object.id(), observed, desired);
        return new Change(new Snapshot(object.version(), observed), new Snapshot(object.version() + 1, links(object.id())), created);
    }

    private Tag create(TagName name) {
        List<Tag> rows = jdbc.query("""
                INSERT INTO biz_tag(tenant_key,name,canonical,create_by,update_by)
                VALUES(:tenant,:name,:canonical,:actor,:actor)
                ON CONFLICT (tenant_key,canonical,deleted_token) DO NOTHING
                RETURNING id,tenant_key,name,canonical,version,deleted_token
                """, parameters().addValue("name", name.display()).addValue("canonical", name.canonical()), TAG_MAPPER);
        // 所有正常写者持有同一名称锁，等待后 activeNames 会读到已提交行；此分支只拦不遵循协议的外部写入。
        if (rows.size() != 1) throw changed();
        return rows.getFirst();
    }

    private void saveLinks(long objectId, List<TagLink> existing, List<Tag> desired) {
        Set<Long> wanted = new HashSet<>();
        desired.forEach(tag -> wanted.add(tag.id()));
        List<Long> removed = existing.stream().filter(link -> !wanted.contains(link.tag().id())).map(TagLink::id).toList();
        if (!removed.isEmpty()) {
            int count = jdbc.update("DELETE FROM biz_tag_link WHERE tenant_key=:tenant AND object_ref_id=:object AND id IN (:ids)",
                    parameters().addValue("object", objectId).addValue("ids", removed));
            if (count != removed.size()) throw changed();
        }
        var byTag = new HashMap<Long, TagLink>();
        existing.forEach(link -> byTag.put(link.tag().id(), link));
        var moved = new ArrayList<TagLink>();
        var inserted = new ArrayList<TagLink>();
        for (int index = 0; index < desired.size(); index++) {
            Tag tag = desired.get(index);
            TagLink old = byTag.get(tag.id());
            if (old == null) inserted.add(new TagLink(0, objectId, index + 1, tag));
            else if (old.seq() != index + 1) moved.add(new TagLink(old.id(), objectId, index + 1, tag));
        }
        if (!moved.isEmpty()) {
            var args = parameters().addValue("object", objectId);
            var tuples = new ArrayList<String>();
            for (int index = 0; index < moved.size(); index++) {
                tuples.add("(CAST(:id" + index + " AS bigint),CAST(:seq" + index + " AS smallint))");
                args.addValue("id" + index, moved.get(index).id()).addValue("seq" + index, moved.get(index).seq());
            }
            int count = jdbc.update("UPDATE biz_tag_link l SET seq=v.seq FROM (VALUES " + String.join(",", tuples)
                    + ") AS v(id,seq) WHERE l.tenant_key=:tenant AND l.object_ref_id=:object AND l.id=v.id", args);
            if (count != moved.size()) throw changed();
        }
        if (!inserted.isEmpty()) {
            var args = parameters().addValue("object", objectId);
            var tuples = new ArrayList<String>();
            for (int index = 0; index < inserted.size(); index++) {
                tuples.add("(:tenant,:object,:tag" + index + ",:seq" + index + ")");
                args.addValue("tag" + index, inserted.get(index).tag().id()).addValue("seq" + index, inserted.get(index).seq());
            }
            int count = jdbc.update("INSERT INTO biz_tag_link(tenant_key,object_ref_id,tag_id,seq) VALUES "
                    + String.join(",", tuples), args);
            if (count != inserted.size()) throw changed();
        }
    }

    private List<TagLink> links(long objectId) {
        List<TagLink> result = jdbc.query("SELECT " + LINK_COLUMNS + """
                 FROM biz_tag_link l JOIN biz_tag t ON t.tenant_key=l.tenant_key AND t.id=l.tag_id
                 WHERE l.tenant_key=:tenant AND l.object_ref_id=:object ORDER BY l.seq,l.id LIMIT 31
                """, parameters().addValue("object", objectId), LINK_MAPPER);
        assertLinkBudget(result.size());
        return List.copyOf(result);
    }

    private List<Tag> activeNames(Collection<String> names) {
        if (names.isEmpty()) return List.of();
        return jdbc.query("SELECT " + TAG_COLUMNS + """
                 FROM biz_tag WHERE tenant_key=:tenant AND deleted_token=0 AND canonical IN (:names) ORDER BY id LIMIT 30
                """, parameters().addValue("names", names), TAG_MAPPER);
    }

    private Map<Long, Tag> lockTags(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        // ORDER BY 稳定主键，PostgreSQL 在排序后 LockRows；没有 canonical 可被 rename 改序的问题。
        List<Tag> rows = jdbc.query("SELECT " + TAG_COLUMNS + """
                 FROM biz_tag WHERE tenant_key=:tenant AND id IN (:ids) ORDER BY id FOR UPDATE
                """, parameters().addValue("ids", ids), TAG_MAPPER);
        var result = new LinkedHashMap<Long, Tag>();
        rows.forEach(tag -> result.put(tag.id(), tag));
        return result;
    }

    private static void checkExpectedTags(List<TagLink> links, Map<Long, Integer> versions) {
        if (links.size() != versions.size()) throw changed();
        for (TagLink link : links) {
            if (!Objects.equals(versions.get(link.tag().id()), link.tag().version())) throw changed();
        }
    }

    private static Map<Long, Integer> checkedVersions(Map<Long, Integer> versions) {
        if (versions == null || versions.size() > TagName.MAX_PER_OBJECT) {
            throw new BizException(400, "必须提交当前标签版本");
        }
        versions.forEach((id, version) -> {
            if (id == null || id <= 0 || version == null || version < 0) throw new BizException(400, "标签版本参数不合法");
        });
        return Map.copyOf(versions);
    }

    private static List<Long> retainedIds(List<Long> ids) {
        if (ids == null || ids.size() > TagName.MAX_PER_OBJECT) throw new BizException(400, "必须提交最多 30 个保留标签 ID");
        var unique = new HashSet<Long>();
        for (Long id : ids) {
            if (id == null || id <= 0 || !unique.add(id)) throw new BizException(400, "保留标签 ID 不合法或重复");
        }
        return List.copyOf(ids);
    }

    private static List<Long> optionIds(Collection<Long> ids) {
        if (ids == null || ids.size() > MAX_OPTIONS) throw new BizException(400, "一次最多查询 100 个标签 ID");
        var unique = new LinkedHashSet<Long>();
        for (Long id : ids) {
            if (id == null || id <= 0) throw new BizException(400, "标签 ID 不合法");
            unique.add(id);
        }
        return List.copyOf(unique);
    }

    static String literalPrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static void requireOptionSize(int size) {
        if (size < 1 || size > MAX_OPTIONS) throw new BizException(400, "标签查询条数必须为 1–100");
    }
    private static void requireId(long id) { if (id <= 0) throw new BizException(400, "标签 ID 不合法"); }
    private static void requireVersion(int version) { if (version < 0) throw new BizException(400, "标签版本不合法"); }
    private static void assertLinkBudget(int count) {
        if (count > TagName.MAX_PER_OBJECT) throw new BizException(400, "每个对象最多关联 30 个标签");
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("tag writes require the caller's transaction");
        }
    }

    private MapSqlParameterSource parameters() {
        return new MapSqlParameterSource("tenant", namespace.tenant()).addValue("actor", LoginUser.currentUserId());
    }
    private MapSqlParameterSource targetParameters(Target target) {
        Objects.requireNonNull(target, "target");
        return parameters().addValue("type", target.type().name()).addValue("owner", target.id()).addValue("company", target.companyId());
    }
    private static Tag tag(ResultSet rs) throws SQLException {
        return new Tag(rs.getLong("id"), rs.getString("tenant_key"), rs.getString("name"),
                rs.getString("canonical"), rs.getInt("version"), rs.getLong("deleted_token"));
    }
    private static BizException changed() { return new BizException(ErrorCodes.STATE_CHANGED, "标签已被其他操作修改，请刷新后重试"); }
    private static BizException missing() { return new BizException(404, "标签不存在或无权访问"); }

    private static <T> T translating(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof SQLException sql) {
                    String state = sql.getSQLState();
                    if (state != null && Set.of("55P03", "40P01", "40001", "23505").contains(state)) throw changed();
                    if ("23503".equals(state)) throw missing();
                }
                cause = cause.getCause();
            }
            throw failure;
        }
    }
}
