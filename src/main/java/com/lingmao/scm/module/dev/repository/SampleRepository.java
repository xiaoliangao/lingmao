package com.lingmao.scm.module.dev.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.dev.core.SampleNodePlanner;
import com.lingmao.scm.module.dev.core.SampleRules;
import com.lingmao.scm.module.dev.entity.Sample;
import com.lingmao.scm.module.dev.entity.SampleColor;
import com.lingmao.scm.module.dev.entity.SampleNode;
import com.lingmao.scm.module.dev.entity.SampleOrder;
import com.lingmao.scm.module.dev.entity.SampleOrderColor;
import com.lingmao.scm.module.dev.entity.SampleOrderSize;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import com.lingmao.scm.module.dev.entity.SampleReview;
import com.lingmao.scm.module.dev.entity.SampleReviewer;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.module.dev.mapper.SampleMapper;
import com.lingmao.scm.module.dev.mapper.SampleNodeMapper;
import com.lingmao.scm.module.dev.mapper.SampleOrderMapper;
import com.lingmao.scm.module.dev.mapper.SampleReviewMapper;
import com.lingmao.scm.module.dev.mapper.SampleRow;
import com.lingmao.scm.module.dev.mapper.SampleSearch;
import com.lingmao.scm.module.dev.mapper.SampleSelectionMapper;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 样衣持久化和锁/CAS 核心。公开方法不接收 tenant，全部使用可信 DeploymentNamespace。
 * 业务权限、校验主数据、状态动作、业务号、附件和事务性日志由 dev Service 负责；这里不开独立写事务。
 * Service 必须在同一事务先 lock，再修改节点/选择/评审，再完成主档及子单双 CAS。
 */
@Repository
public class SampleRepository {
    public static final int MAX_PAGE_SIZE = 100;
    private final SampleMapper samples;
    private final SampleOrderMapper orders;
    private final SampleSelectionMapper selections;
    private final SampleNodeMapper nodes;
    private final SampleReviewMapper reviews;
    private final DeploymentNamespace namespace;
    private final Clock clock;

    public SampleRepository(SampleMapper samples, SampleOrderMapper orders, SampleSelectionMapper selections,
                            SampleNodeMapper nodes, SampleReviewMapper reviews,
                            DeploymentNamespace namespace, Clock clock) {
        this.samples = samples;
        this.orders = orders;
        this.selections = selections;
        this.nodes = nodes;
        this.reviews = reviews;
        this.namespace = namespace;
        this.clock = clock;
    }

    public record Snapshot(Sample sample, SampleOrder order) { }

    /** 捕获原版本/状态/主体，Service 修改实体字段不会把 WHERE 的旧值一起覆盖。 */
    public record Locked(Sample sample, SampleOrder order, long companyId, long sampleId, long orderId,
                         int businessVersion, int sampleVersionLock, int orderVersion,
                         SampleStatus sampleStatus, SampleOrderStatus orderStatus) { }

    public record VersionPage(List<SampleOrder> records, boolean hasMore, Integer nextBeforeVersion) {
        public VersionPage { records = List.copyOf(records); }
    }

    public record OrderBundle(List<SampleOrder> orders, List<SampleOrderColor> colors, List<SampleOrderSize> sizes,
                              List<SampleNode> nodes, List<SampleReview> reviews, List<SampleReviewer> reviewers) {
        public OrderBundle {
            orders = List.copyOf(orders); colors = List.copyOf(colors); sizes = List.copyOf(sizes);
            nodes = List.copyOf(nodes); reviews = List.copyOf(reviews); reviewers = List.copyOf(reviewers);
        }
    }

    /** 由 Service 从真实颜色档案/已有历史快照构造，不从可写 HTTP DTO 接受标签或色号文本。 */
    public record ColorChoice(long id, String code, String name, String hex) { }

    public enum HomeKind { REVIEW, LATE }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(long companyId, long id, DataScope scope) {
        company(companyId); id(id); Objects.requireNonNull(scope, "scope");
        Sample sample = samples.selectVisible(namespace.tenant(), companyId, id, scope);
        if (sample == null) throw missing();
        SampleOrder order = orders.selectLatest(namespace.tenant(), companyId, id);
        if (order == null) throw changed();
        return new Snapshot(sample, order);
    }

    /** 删除成功后的幂等重放仍检查真实主体和数据范围，不把已删除对象的记录当不存在而重执行。 */
    public Sample replayIdentity(long companyId, long id, DataScope scope) {
        company(companyId); id(id); Objects.requireNonNull(scope, "scope");
        Sample sample = samples.selectIdentity(namespace.tenant(), companyId, id, scope);
        if (sample == null) throw missing();
        return sample;
    }

    public Sample byStyle(long companyId, long styleId, DataScope scope) {
        company(companyId); id(styleId); Objects.requireNonNull(scope, "scope");
        return samples.selectByStyle(namespace.tenant(), companyId, styleId, scope);
    }

    /** 内部引用完整性检查；不能直接序列化给客户端，Service 必须按实际主体重新鉴权。 */
    public Sample convertedSource(long styleId) {
        id(styleId); return samples.selectConvertedSource(namespace.tenant(), styleId);
    }

    public SampleOrder orderIdentity(long companyId, long orderId, DataScope scope) {
        id(orderId); return orderIdentities(companyId, scope, List.of(orderId)).getFirst();
    }

    public SampleReview reviewIdentity(long companyId, long reviewId, DataScope scope) {
        id(reviewId); return reviewIdentities(companyId, scope, List.of(reviewId)).getFirst();
    }

    public List<Sample> sampleIdentities(long companyId, DataScope scope, Collection<Long> sampleIds) {
        company(companyId); Objects.requireNonNull(scope, "scope"); List<Long> ids = boundedIds(sampleIds);
        if (ids.isEmpty()) return List.of();
        List<Sample> found = samples.selectVisibleIds(namespace.tenant(), companyId, ids, scope);
        if (found.size() != ids.size()) throw missing();
        return List.copyOf(found);
    }

    /** 历史单据按样衣当前负责人范围授权，一次 SQL；不因为旧版只读而误报不存在。 */
    public List<SampleOrder> orderIdentities(long companyId, DataScope scope, Collection<Long> orderIds) {
        company(companyId); Objects.requireNonNull(scope, "scope"); List<Long> ids = boundedIds(orderIds);
        if (ids.isEmpty()) return List.of();
        List<SampleOrder> found = orders.selectVisibleBatch(namespace.tenant(), companyId, ids, scope);
        if (found.size() != ids.size()) throw missing();
        return List.copyOf(found);
    }

    public List<SampleReview> reviewIdentities(long companyId, DataScope scope, Collection<Long> reviewIds) {
        company(companyId); Objects.requireNonNull(scope, "scope"); List<Long> ids = boundedIds(reviewIds);
        if (ids.isEmpty()) return List.of();
        List<SampleReview> found = reviews.selectVisibleBatch(namespace.tenant(), companyId, ids, scope);
        if (found.size() != ids.size()) throw missing();
        return List.copyOf(found);
    }

    public List<SampleRow> homePreview(long companyId, DataScope scope, HomeKind kind, Long ownerId, int limit) {
        homeArguments(companyId, scope, kind, ownerId);
        if (limit < 1 || limit > 20) throw invalid("首页样衣预览须为1至20条");
        return List.copyOf(samples.selectHomePreview(namespace.tenant(), companyId, scope, kind.name(), ownerId, LocalDate.now(clock), limit));
    }

    public long homeCount(long companyId, DataScope scope, HomeKind kind, Long ownerId) {
        homeArguments(companyId, scope, kind, ownerId);
        return samples.selectHomeCount(namespace.tenant(), companyId, scope, kind.name(), ownerId, LocalDate.now(clock));
    }

    public IPage<SampleRow> page(long companyId, DataScope scope, SampleSearch query, long page, long size) {
        company(companyId); Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(query, "query"); pageSize(size);
        if (page < 1 || (page - 1) > 1_000_000L / size) throw invalid("样衣页码超限，请缩小筛选范围");
        List<String> codes = Keywords.codes(query.keyword());
        String keyword = codes == null ? Keywords.contains(query.keyword(), 100) : null;
        return samples.selectRows(new Page<>(page, size), namespace.tenant(), companyId, query, scope,
                codes, keyword, LocalDate.now(clock));
    }

    /** 顶部状态角标使用独立查询，不随列表每次分页重新聚合。showStatus 不进入此计数的 WHERE。 */
    public List<SampleMapper.StatusCount> counts(long companyId, DataScope scope, SampleSearch query) {
        company(companyId); Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(query, "query");
        List<String> codes = Keywords.codes(query.keyword());
        return samples.selectCounts(namespace.tenant(), companyId, query, scope, codes,
                codes == null ? Keywords.contains(query.keyword(), 100) : null, LocalDate.now(clock));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionPage versions(long companyId, long sampleId, DataScope scope, Integer beforeVersion, int size) {
        read(companyId, sampleId, scope); pageSize(size);
        if (beforeVersion != null && beforeVersion < 1) throw invalid("历史版次游标不合法");
        List<SampleOrder> found = orders.selectVersions(namespace.tenant(), companyId, sampleId, beforeVersion, size + 1);
        boolean more = found.size() > size;
        List<SampleOrder> page = more ? found.subList(0, size) : found;
        return new VersionPage(page, more, more ? page.getLast().getSampleVersion() : null);
    }

    /** 一次授权整组版本；混入跨主体或不可见 ID 时整批 404，不能静默返回可见子集。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderBundle bundle(long companyId, DataScope scope, Collection<Long> orderIds) {
        company(companyId); Objects.requireNonNull(scope, "scope");
        List<Long> ids = boundedIds(orderIds);
        if (ids.isEmpty()) return new OrderBundle(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        List<SampleOrder> visible = orders.selectVisibleBatch(namespace.tenant(), companyId, ids, scope);
        if (visible.size() != ids.size()) throw missing();
        List<SampleReview> reviewRows = reviews.selectByOrders(namespace.tenant(), companyId, ids);
        List<SampleReviewer> people = reviewRows.isEmpty() ? List.of() : reviews.selectReviewers(namespace.tenant(), companyId,
                reviewRows.stream().map(SampleReview::getId).toList());
        var colors = selections.selectOrderColors(namespace.tenant(), companyId, ids);
        var sizes = selections.selectOrderSizes(namespace.tenant(), companyId, ids);
        var nodeRows = nodes.selectByOrders(namespace.tenant(), companyId, ids);
        if (colors.size() > 10_000 || sizes.size() > 10_000 || nodeRows.size() > 10_000 || people.size() > 10_000
                || reviewRows.size() > ids.size()) throw invalid("样衣版本数据超出允许范围");
        return new OrderBundle(visible, colors, sizes, nodeRows, reviewRows, people);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<SampleColor> colors(long companyId, long sampleId, DataScope scope) {
        read(companyId, sampleId, scope);
        List<SampleColor> found = selections.selectSampleColors(namespace.tenant(), companyId, sampleId);
        if (found.size() > SampleRules.MAX_SELECTIONS) throw invalid("样衣颜色超过 100 项");
        return found;
    }

    /** 普通业务等待父锁；取得后重读最新版本并再次检查 SELF，避免等待期间改派造成授权旁路。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Locked lock(long companyId, long sampleId, DataScope scope, Integer sampleVersionLock, Integer orderVersion) {
        transaction(); company(companyId); id(sampleId); Objects.requireNonNull(scope, "scope");
        Sample sample = samples.lockVisible(namespace.tenant(), companyId, sampleId, scope);
        if (sample == null) throw missing();
        SampleOrder order = orders.lockLatest(namespace.tenant(), companyId, sampleId);
        if (order == null) throw changed();
        scope.check(sample.getCreateBy(), sample.getDesignerId(), order.getMakerId());
        if (sampleVersionLock != null && (sampleVersionLock < 0 || !sampleVersionLock.equals(sample.getVersion()))
                || orderVersion != null && (orderVersion < 0 || !orderVersion.equals(order.getVersion()))) throw changed();
        return new Locked(sample, order, companyId, sample.getId(), order.getId(), order.getSampleVersion(),
                sample.getVersion(), order.getVersion(), sample.getStatus(), order.getStatus());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Locked lockByOrder(long companyId, long orderId, DataScope scope, int sampleVersionLock, int version) {
        transaction(); company(companyId); id(orderId);
        SampleOrder identity = orders.selectScoped(namespace.tenant(), companyId, orderId);
        if (identity == null) throw missing();
        Locked locked = lock(companyId, identity.getSampleId(), scope, sampleVersionLock, version);
        if (locked.orderId() != orderId) throw changed();
        return locked;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<SampleNode> lockNodes(Locked locked) {
        transaction();
        List<SampleNode> rows = nodes.lockByOrder(namespace.tenant(), locked.companyId(), locked.orderId());
        if (rows.size() > SampleNodePlanner.MAX_NODES) throw invalid("打样节点超过 100 项");
        return rows;
    }

    public int nextVersion(Locked locked) {
        transaction();
        try { return Math.addExact(locked.businessVersion(), 1); }
        catch (ArithmeticException ex) { throw invalid("样衣版次超出允许范围"); }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertSample(Sample sample) {
        transaction(); company(sample.getCompanyId());
        sample.setTenantKey(namespace.tenant()); sample.setVersion(0); sample.setDeletedToken(0L);
        sample.setStatus(SampleStatus.DEVELOPING); sample.setConvertedStyleId(null); sample.setConvertBy(null); sample.setConvertTime(null);
        sample.setEstTagPrice(SampleRules.price(sample.getEstTagPrice(), 2, "预估吊牌价"));
        sample.setEstCost(SampleRules.price(sample.getEstCost(), 4, "预估成本"));
        if (samples.insert(sample) != 1) throw changed();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertOrder(SampleOrder order) {
        transaction(); company(order.getCompanyId()); id(order.getSampleId());
        order.setTenantKey(namespace.tenant()); order.setVersion(0); order.setStatus(SampleOrderStatus.WAIT_ASSIGN);
        order.setDispatchedBy(null); order.setDispatchedTime(null); SampleRules.quantity(order.getQty());
        if (orders.insert(order) != 1) throw changed();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateSample(Locked locked, Sample after) {
        transaction(); sameId(locked.sampleId(), after.getId()); company(after.getCompanyId());
        after.setTenantKey(namespace.tenant()); after.setUpdateBy(LoginUser.currentUserId());
        after.setEstTagPrice(SampleRules.price(after.getEstTagPrice(), 2, "预估吊牌价"));
        after.setEstCost(SampleRules.price(after.getEstCost(), 4, "预估成本"));
        one(samples.updateCas(namespace.tenant(), locked.companyId(), after, locked.sampleVersionLock(), locked.sampleStatus()));
        if (after.getCompanyId() != locked.companyId()) {
            // 没有节点/评审/资料包才可改公司，DB 首次派单守卫负责兜底；全部复合 FK 延迟到本事务结束校验。
            orders.moveCompany(namespace.tenant(), after.getId(), locked.companyId(), after.getCompanyId());
            selections.moveSampleColors(namespace.tenant(), after.getId(), locked.companyId(), after.getCompanyId());
            selections.moveOrderColors(namespace.tenant(), after.getId(), locked.companyId(), after.getCompanyId());
            selections.moveOrderSizes(namespace.tenant(), after.getId(), locked.companyId(), after.getCompanyId());
        }
        after.setVersion(locked.sampleVersionLock() + 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void touchSample(Locked locked) {
        transaction(); one(samples.touchCas(namespace.tenant(), locked.companyId(), locked.sampleId(),
                locked.sampleVersionLock(), locked.sampleStatus(), LoginUser.currentUserId()));
        locked.sample().setVersion(locked.sampleVersionLock() + 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateOrder(Locked locked, SampleOrder after) {
        transaction(); sameId(locked.orderId(), after.getId()); sameId(locked.sampleId(), after.getSampleId());
        if (!Objects.equals(after.getCompanyId(), locked.companyId())) throw changed();
        after.setTenantKey(namespace.tenant()); after.setUpdateBy(LoginUser.currentUserId()); SampleRules.quantity(after.getQty());
        one(orders.updateCas(namespace.tenant(), locked.companyId(), after, locked.orderVersion(), locked.orderStatus()));
        after.setVersion(locked.orderVersion() + 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteDraft(Locked locked) {
        transaction(); one(samples.tombstoneCas(namespace.tenant(), locked.companyId(), locked.sampleId(),
                locked.sampleVersionLock(), LoginUser.currentUserId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceColors(Sample sample, Collection<Long> colorIds) {
        transaction(); company(sample.getCompanyId()); id(sample.getId());
        List<Long> ids = SampleRules.colorIds(colorIds, null);
        var values = new ArrayList<SampleColor>(ids.size());
        for (int i = 0; i < ids.size(); i++) values.add(new SampleColor(namespace.tenant(), sample.getCompanyId(), sample.getId(), ids.get(i), i + 1));
        selections.deleteSampleColors(namespace.tenant(), sample.getCompanyId(), sample.getId());
        if (selections.insertSampleColors(values) != values.size()) throw changed();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceSelection(SampleOrder order, List<ColorChoice> colors, Collection<String> sizeCodes) {
        transaction(); company(order.getCompanyId()); id(order.getId());
        if (colors == null) throw invalid("请选择打样颜色");
        SampleRules.colorIds(colors.stream().map(ColorChoice::id).toList(), null);
        List<String> sizes = SampleRules.sizeCodes(sizeCodes, null); SampleRules.combinations(colors.size(), sizes.size());
        var colorRows = new ArrayList<SampleOrderColor>(colors.size());
        for (int i = 0; i < colors.size(); i++) {
            ColorChoice color = colors.get(i);
            colorRows.add(new SampleOrderColor(namespace.tenant(), order.getCompanyId(), order.getId(), color.id(), i + 1,
                    color.code(), color.name(), color.hex()));
        }
        var sizeRows = new ArrayList<SampleOrderSize>(sizes.size());
        for (int i = 0; i < sizes.size(); i++) sizeRows.add(new SampleOrderSize(namespace.tenant(), order.getCompanyId(),
                order.getId(), order.getSizeGroupId(), i + 1, sizes.get(i)));
        selections.deleteOrderColors(namespace.tenant(), order.getCompanyId(), order.getId());
        selections.deleteOrderSizes(namespace.tenant(), order.getCompanyId(), order.getId());
        if (selections.insertOrderColors(colorRows) != colorRows.size() || selections.insertOrderSizes(sizeRows) != sizeRows.size()) throw changed();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertNodes(SampleOrder order, List<SampleNodePlanner.Planned> plans, Long ownerId) {
        transaction(); company(order.getCompanyId()); id(order.getId());
        if (plans == null || plans.isEmpty() || plans.size() > SampleNodePlanner.MAX_NODES) throw invalid("打样节点数量不合法");
        var rows = new ArrayList<SampleNode>(plans.size());
        for (var plan : plans) {
            SampleNode node = new SampleNode();
            node.setTenantKey(namespace.tenant()); node.setCompanyId(order.getCompanyId()); node.setSampleOrderId(order.getId());
            node.setSeq(plan.seq()); node.setNodeCode(plan.code()); node.setNodeName(plan.name()); node.setDurationDays(plan.durationDays());
            node.setPlanDate(plan.planDate()); node.setOwnerId(ownerId); node.setUpdateBy(LoginUser.currentUserId()); rows.add(node);
        }
        if (nodes.insertAll(rows) != rows.size()) throw changed();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updatePlans(Locked locked, List<SampleNode> plans) {
        transaction();
        if (plans == null || plans.isEmpty() || plans.size() > SampleNodePlanner.MAX_NODES) throw invalid("请提交全部打样节点");
        List<SampleNode> current = lockNodes(locked);
        var requested = new HashSet<Long>();
        for (SampleNode plan : plans) {
            if (plan == null || plan.getId() == null || plan.getPlanDate() == null || !requested.add(plan.getId())) throw invalid("节点参数缺失或重复");
        }
        if (current.size() != plans.size() || !requested.equals(new HashSet<>(current.stream().map(SampleNode::getId).toList()))) throw changed();
        if (nodes.updatePlans(namespace.tenant(), locked.companyId(), locked.orderId(), plans, LoginUser.currentUserId()) != plans.size()) throw changed();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void setDone(Locked locked, long nodeId, LocalDate doneDate) {
        transaction(); id(nodeId);
        if (doneDate != null) SampleRules.notFuture(doneDate, LocalDate.now(clock), "完成日期");
        one(nodes.setDone(namespace.tenant(), locked.companyId(), locked.orderId(), nodeId, doneDate, LoginUser.currentUserId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reassignUndone(Locked locked, Long ownerId) {
        transaction(); lockNodes(locked);
        nodes.reassignUndone(namespace.tenant(), locked.companyId(), locked.orderId(), ownerId, LoginUser.currentUserId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertReview(SampleOrder order, SampleReview review, Collection<Long> reviewerIds) {
        transaction(); company(order.getCompanyId()); id(order.getId());
        List<Long> ids = SampleRules.reviewerIds(reviewerIds);
        review.setTenantKey(namespace.tenant()); review.setCompanyId(order.getCompanyId()); review.setSampleOrderId(order.getId());
        SampleRules.notFuture(review.getReviewDate(), LocalDate.now(clock), "评审日期");
        if (reviews.insert(review) != 1) throw changed();
        var people = new ArrayList<SampleReviewer>(ids.size());
        for (int i = 0; i < ids.size(); i++) people.add(new SampleReviewer(namespace.tenant(), order.getCompanyId(), review.getId(), ids.get(i), i + 1));
        if (reviews.insertReviewers(people) != people.size()) throw changed();
    }

    private static List<Long> boundedIds(Collection<Long> values) {
        if (values == null || values.size() > MAX_PAGE_SIZE) throw invalid("一次最多查询 100 个打样版本");
        var ids = new LinkedHashSet<Long>();
        for (Long value : values) { id(value); ids.add(value); }
        return List.copyOf(ids);
    }

    private static void sameId(Long expected, Long actual) { if (!Objects.equals(expected, actual)) throw changed(); }
    private static void homeArguments(long companyId, DataScope scope, HomeKind kind, Long ownerId) {
        company(companyId); Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(kind, "kind");
        if (ownerId != null) id(ownerId);
    }
    private static void pageSize(long size) { if (size < 1 || size > MAX_PAGE_SIZE) throw invalid("每页须为 1–100 条"); }
    private static void company(Long value) { if (value == null || value <= 0) throw invalid("请选择主体公司"); }
    private static void id(Long value) { if (value == null || value <= 0) throw invalid("编号不合法"); }
    private static void one(int updated) { if (updated != 1) throw changed(); }
    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("样衣写入必须加入业务事务");
    }
    private static BizException invalid(String message) { return new BizException(400, message); }
    private static BizException changed() { return new BizException(ErrorCodes.STATE_CHANGED, "状态或版本已变化，请刷新后再试"); }
    private static BizException missing() { return new BizException(404, "样衣不存在"); }
}
