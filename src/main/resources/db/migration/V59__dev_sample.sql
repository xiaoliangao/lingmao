-- V59：样衣完整链。依据 RFC007 与 sample-completion-contract.md，本轮明确增加真实主体、双 CAS 和规范化选择。
-- 新表和索引先建立，再扩展样衣资料包 owner，最后字典/菜单；不改 V1–V58，不开启 outOfOrder。
-- 本迁移 ALTER 旧 tp 表需要短维护锁窗口；不声称是无锁零停机 DDL。
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

CREATE TABLE dev_sample (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    code VARCHAR(32) NOT NULL CHECK (length(btrim(code)) > 0),
    name VARCHAR(100) NOT NULL CHECK (length(btrim(name)) > 0),
    brand VARCHAR(64) NOT NULL,
    category_id BIGINT NOT NULL REFERENCES bas_category(id),
    year SMALLINT NOT NULL CHECK (year BETWEEN 2000 AND 2099),
    season VARCHAR(64) NOT NULL,
    wave VARCHAR(64),
    designer_id BIGINT REFERENCES sys_user(id),
    pattern_maker_id BIGINT REFERENCES sys_user(id),
    source VARCHAR(64) NOT NULL,
    customer_id BIGINT REFERENCES bas_customer(id),
    customer_style_no VARCHAR(64),
    size_group_id BIGINT NOT NULL REFERENCES bas_size_group(id),
    sample_factory_id BIGINT REFERENCES sup_supplier(id),
    fabric_supplier_id BIGINT REFERENCES sup_supplier(id),
    fabric_desc VARCHAR(255),
    expect_finish_date DATE,
    est_tag_price NUMERIC(14,2) CHECK (est_tag_price >= 0),
    est_cost NUMERIC(14,4) CHECK (est_cost >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DEVELOPING'
        CHECK (status IN ('DEVELOPING','PASSED','CONVERTED','ELIMINATED')),
    converted_style_id BIGINT REFERENCES sty_style(id),
    convert_by BIGINT,
    convert_time TIMESTAMPTZ,
    remark VARCHAR(500),
    version INT NOT NULL DEFAULT 0 CHECK (version >= 0),
    deleted_token BIGINT NOT NULL DEFAULT 0 CHECK (deleted_token = 0 OR deleted_token = id),
    create_by BIGINT,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_by BIGINT,
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_dev_sample_scope UNIQUE(tenant_key, company_id, id),
    CONSTRAINT uk_dev_sample_code UNIQUE(tenant_key, code, deleted_token),
    CONSTRAINT ck_dev_sample_conversion CHECK (
        (status = 'CONVERTED' AND converted_style_id IS NOT NULL AND convert_time IS NOT NULL)
        OR (status <> 'CONVERTED' AND converted_style_id IS NULL AND convert_by IS NULL AND convert_time IS NULL))
);
CREATE UNIQUE INDEX uk_dev_sample_style ON dev_sample(converted_style_id) WHERE converted_style_id IS NOT NULL;
CREATE INDEX idx_dev_sample_converted_style ON dev_sample(converted_style_id);
CREATE INDEX idx_dev_sample_company ON dev_sample(company_id);
CREATE INDEX idx_dev_sample_category ON dev_sample(category_id);
CREATE INDEX idx_dev_sample_designer ON dev_sample(designer_id);
CREATE INDEX idx_dev_sample_pattern_maker ON dev_sample(pattern_maker_id);
CREATE INDEX idx_dev_sample_customer ON dev_sample(customer_id);
CREATE INDEX idx_dev_sample_size_group ON dev_sample(size_group_id);
CREATE INDEX idx_dev_sample_sample_factory ON dev_sample(sample_factory_id);
CREATE INDEX idx_dev_sample_fabric_supplier ON dev_sample(fabric_supplier_id);
CREATE INDEX idx_dev_sample_created ON dev_sample(tenant_key,company_id,create_time DESC,id DESC) WHERE deleted_token=0;
CREATE INDEX idx_dev_sample_status ON dev_sample(tenant_key,company_id,status,create_time DESC,id DESC) WHERE deleted_token=0;
CREATE INDEX idx_dev_sample_code ON dev_sample(tenant_key,company_id,code,id) WHERE deleted_token=0;
CREATE INDEX idx_dev_sample_self_creator ON dev_sample(tenant_key,company_id,create_by,id) WHERE deleted_token=0;
CREATE INDEX idx_dev_sample_self_designer ON dev_sample(tenant_key,company_id,designer_id,id) WHERE deleted_token=0;
CREATE INDEX idx_dev_sample_name_search ON dev_sample USING gin(name gin_trgm_ops) WHERE deleted_token=0;
CREATE INDEX idx_dev_sample_code_search ON dev_sample USING gin(code gin_trgm_ops) WHERE deleted_token=0;
COMMENT ON TABLE dev_sample IS '样衣档案；真实主体、乐观锁与保留审计身份的草稿删除';
COMMENT ON COLUMN dev_sample.version IS '样衣主档 CAS 版本；子单动作请求字段 sampleVersionLock';
COMMENT ON COLUMN dev_sample.deleted_token IS '有效为0；删除未派单草稿设为自身id，不物理删除审计身份';

CREATE TABLE dev_sample_color (
    tenant_key VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    sample_id BIGINT NOT NULL,
    color_id BIGINT NOT NULL REFERENCES bas_color(id),
    seq INT NOT NULL CHECK (seq BETWEEN 1 AND 100),
    PRIMARY KEY(tenant_key,company_id,sample_id,color_id),
    CONSTRAINT uk_dev_sample_color_seq UNIQUE(tenant_key,company_id,sample_id,seq),
    CONSTRAINT fk_dev_sample_color_owner FOREIGN KEY(tenant_key,company_id,sample_id)
        REFERENCES dev_sample(tenant_key,company_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idx_dev_sample_color_company ON dev_sample_color(company_id);
CREATE INDEX idx_dev_sample_color_source ON dev_sample_color(color_id);
COMMENT ON TABLE dev_sample_color IS '样衣可选颜色';

CREATE TABLE dev_sample_order (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    no VARCHAR(32) NOT NULL,
    sample_id BIGINT NOT NULL,
    sample_version INT NOT NULL CHECK (sample_version >= 1),
    sample_type VARCHAR(16) NOT NULL CHECK (sample_type IN ('FIRST','REVISE','FULL_SET','PRE_PRODUCTION')),
    make_way VARCHAR(16) CHECK (make_way IN ('INTERNAL','EXTERNAL')),
    factory_id BIGINT REFERENCES sup_supplier(id),
    maker_id BIGINT REFERENCES sys_user(id),
    need_date DATE NOT NULL,
    qty INT NOT NULL CHECK (qty BETWEEN 1 AND 99999999),
    size_group_id BIGINT NOT NULL REFERENCES bas_size_group(id),
    reason VARCHAR(500),
    requirement VARCHAR(1000),
    status VARCHAR(16) NOT NULL DEFAULT 'WAIT_ASSIGN'
        CHECK (status IN ('WAIT_ASSIGN','MAKING','WAIT_REVIEW','PASSED','REVISE','ELIMINATED','CANCELLED')),
    version INT NOT NULL DEFAULT 0 CHECK (version >= 0),
    dispatched_by BIGINT,
    dispatched_time TIMESTAMPTZ,
    create_by BIGINT,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_by BIGINT,
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_dev_sample_order_scope UNIQUE(tenant_key,company_id,id),
    CONSTRAINT uk_dev_sample_order_size_owner UNIQUE(tenant_key,company_id,id,size_group_id),
    CONSTRAINT uk_dev_sample_order_no UNIQUE(tenant_key,no),
    CONSTRAINT uk_dev_sample_order_ver UNIQUE(sample_id,sample_version),
    CONSTRAINT fk_dev_sample_order_owner FOREIGN KEY(tenant_key,company_id,sample_id)
        REFERENCES dev_sample(tenant_key,company_id,id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_dev_sample_order_reason CHECK (sample_version=1 OR (reason IS NOT NULL AND length(btrim(reason))>0)),
    CONSTRAINT ck_dev_sample_order_assignee CHECK (
        CASE make_way WHEN 'INTERNAL' THEN factory_id IS NULL
            WHEN 'EXTERNAL' THEN maker_id IS NULL
            ELSE factory_id IS NULL AND maker_id IS NULL END),
    CONSTRAINT ck_dev_sample_order_dispatch CHECK (dispatched_time IS NULL OR
        (CASE make_way WHEN 'INTERNAL' THEN maker_id IS NOT NULL
            WHEN 'EXTERNAL' THEN factory_id IS NOT NULL ELSE false END))
);
CREATE INDEX idx_dev_sample_order_company ON dev_sample_order(company_id);
CREATE INDEX idx_dev_sample_order_factory ON dev_sample_order(factory_id);
CREATE INDEX idx_dev_sample_order_maker ON dev_sample_order(maker_id);
CREATE INDEX idx_dev_sample_order_size_group ON dev_sample_order(size_group_id);
CREATE INDEX idx_dev_sample_order_latest ON dev_sample_order(tenant_key,company_id,sample_id,sample_version DESC);
CREATE INDEX idx_dev_sample_order_maker_scope ON dev_sample_order(tenant_key,company_id,maker_id,sample_id);
CREATE INDEX idx_dev_sample_order_no_company ON dev_sample_order(tenant_key,company_id,no,sample_id);
CREATE INDEX idx_dev_sample_order_need ON dev_sample_order(tenant_key,company_id,need_date,id);
CREATE INDEX idx_dev_sample_order_no_search ON dev_sample_order USING gin(no gin_trgm_ops);
COMMENT ON TABLE dev_sample_order IS '打样单；每版独立单头和乐观锁';
COMMENT ON COLUMN dev_sample_order.sample_version IS '业务版次，父样衣锁内递增；不是主档 CAS 版本';
COMMENT ON COLUMN dev_sample_order.qty IS '整版实际总件数，所选色码不重复分配此数量';
COMMENT ON COLUMN dev_sample_order.dispatched_time IS '首次派单的不可清除时间；取消/恢复后仍证明该样衣曾派单';

CREATE TABLE dev_sample_order_color (
    tenant_key VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    sample_order_id BIGINT NOT NULL,
    color_id BIGINT NOT NULL REFERENCES bas_color(id),
    seq INT NOT NULL CHECK (seq BETWEEN 1 AND 100),
    color_code VARCHAR(3) NOT NULL,
    color_name VARCHAR(32) NOT NULL,
    color_hex VARCHAR(7),
    PRIMARY KEY(tenant_key,company_id,sample_order_id,color_id),
    CONSTRAINT uk_dev_sample_order_color_seq UNIQUE(tenant_key,company_id,sample_order_id,seq),
    CONSTRAINT fk_dev_sample_order_color_owner FOREIGN KEY(tenant_key,company_id,sample_order_id)
        REFERENCES dev_sample_order(tenant_key,company_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idx_dev_sample_order_color_company ON dev_sample_order_color(company_id);
CREATE INDEX idx_dev_sample_order_color_source ON dev_sample_order_color(color_id);
COMMENT ON TABLE dev_sample_order_color IS '打样单所选颜色和显示快照';

CREATE TABLE dev_sample_order_size (
    tenant_key VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    sample_order_id BIGINT NOT NULL,
    size_group_id BIGINT NOT NULL,
    seq INT NOT NULL CHECK (seq BETWEEN 1 AND 100),
    size_code VARCHAR(16) NOT NULL,
    PRIMARY KEY(tenant_key,company_id,sample_order_id,size_code),
    CONSTRAINT uk_dev_sample_order_size_seq UNIQUE(tenant_key,company_id,sample_order_id,seq),
    CONSTRAINT fk_dev_sample_order_size_owner FOREIGN KEY(tenant_key,company_id,sample_order_id,size_group_id)
        REFERENCES dev_sample_order(tenant_key,company_id,id,size_group_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_dev_sample_order_size_source FOREIGN KEY(size_group_id,size_code)
        REFERENCES bas_size_group_item(size_group_id,size_code)
);
CREATE INDEX idx_dev_sample_order_size_company ON dev_sample_order_size(company_id);
CREATE INDEX idx_dev_sample_order_size_source ON dev_sample_order_size(size_group_id,size_code);
CREATE INDEX idx_dev_sample_order_size_owner ON dev_sample_order_size(tenant_key,company_id,sample_order_id,size_group_id);
COMMENT ON TABLE dev_sample_order_size IS '打样单所选真实尺码组成员';

CREATE TABLE dev_sample_node (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    sample_order_id BIGINT NOT NULL,
    seq INT NOT NULL CHECK (seq BETWEEN 1 AND 100),
    node_code VARCHAR(64) NOT NULL,
    node_name VARCHAR(64) NOT NULL,
    duration_days INT NOT NULL CHECK (duration_days >= 0),
    plan_date DATE NOT NULL,
    done_date DATE,
    done_by BIGINT,
    owner_id BIGINT REFERENCES sys_user(id),
    remark VARCHAR(255),
    update_by BIGINT,
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_dev_sample_node_code UNIQUE(tenant_key,company_id,sample_order_id,node_code),
    CONSTRAINT uk_dev_sample_node_seq UNIQUE(tenant_key,company_id,sample_order_id,seq),
    CONSTRAINT fk_dev_sample_node_owner FOREIGN KEY(tenant_key,company_id,sample_order_id)
        REFERENCES dev_sample_order(tenant_key,company_id,id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_dev_sample_node_done_actor CHECK (done_date IS NOT NULL OR done_by IS NULL)
);
CREATE INDEX idx_dev_sample_node_company ON dev_sample_node(company_id);
CREATE INDEX idx_dev_sample_node_user ON dev_sample_node(owner_id);
CREATE INDEX idx_dev_sample_node_late ON dev_sample_node(tenant_key,company_id,plan_date,sample_order_id) WHERE done_date IS NULL;
CREATE INDEX idx_dev_sample_node_lock ON dev_sample_node(tenant_key,company_id,sample_order_id,id);
COMMENT ON TABLE dev_sample_node IS '打样节点；字典编码、名称、默认天数为派单时快照';

CREATE TABLE dev_sample_review (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    sample_order_id BIGINT NOT NULL,
    review_date DATE NOT NULL,
    result VARCHAR(16) NOT NULL CHECK (result IN ('PASS','REVISE','ELIMINATE')),
    opinion VARCHAR(1000),
    recorded_txid BIGINT NOT NULL DEFAULT txid_current(),
    create_by BIGINT,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_by BIGINT,
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_dev_sample_review_scope UNIQUE(tenant_key,company_id,id),
    CONSTRAINT uk_dev_sample_review_order UNIQUE(tenant_key,company_id,sample_order_id),
    CONSTRAINT fk_dev_sample_review_owner FOREIGN KEY(tenant_key,company_id,sample_order_id)
        REFERENCES dev_sample_order(tenant_key,company_id,id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_dev_sample_review_opinion CHECK (result='PASS' OR (opinion IS NOT NULL AND length(btrim(opinion))>0))
);
CREATE INDEX idx_dev_sample_review_company ON dev_sample_review(company_id);
COMMENT ON TABLE dev_sample_review IS '样衣评审；只增不改，每版一次';
COMMENT ON COLUMN dev_sample_review.recorded_txid IS '数据库写入的创建事务；评审人只能在相同事务首次插入';

CREATE TABLE dev_sample_reviewer (
    tenant_key VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    seq INT NOT NULL CHECK (seq BETWEEN 1 AND 100),
    PRIMARY KEY(tenant_key,company_id,review_id,user_id),
    CONSTRAINT uk_dev_sample_reviewer_seq UNIQUE(tenant_key,company_id,review_id,seq),
    CONSTRAINT fk_dev_sample_reviewer_owner FOREIGN KEY(tenant_key,company_id,review_id)
        REFERENCES dev_sample_review(tenant_key,company_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idx_dev_sample_reviewer_company ON dev_sample_reviewer(company_id);
CREATE INDEX idx_dev_sample_reviewer_user ON dev_sample_reviewer(user_id);
COMMENT ON TABLE dev_sample_reviewer IS '样衣评审人；评审创建事务内确定后不可变';

-- 只增加真实样衣 owner，不伪造旧 style 包归属。
ALTER TABLE tp_tech_pack ALTER COLUMN style_id DROP NOT NULL;
ALTER TABLE tp_tech_pack ADD COLUMN sample_id BIGINT REFERENCES dev_sample(id);
ALTER TABLE tp_tech_pack ADD CONSTRAINT ck_tp_pack_actual_owner CHECK (
    style_id IS NOT NULL OR (sample_id IS NOT NULL AND stage='DEV'));
CREATE INDEX idx_tp_pack_sample ON tp_tech_pack(sample_id);
CREATE UNIQUE INDEX uk_tp_pack_sample_version ON tp_tech_pack(sample_id,version)
    WHERE style_id IS NULL AND sample_id IS NOT NULL;
CREATE UNIQUE INDEX uk_tp_pack_sample_effective ON tp_tech_pack(sample_id)
    WHERE style_id IS NULL AND sample_id IS NOT NULL AND status='EFFECTIVE';
CREATE UNIQUE INDEX uk_tp_pack_sample_draft ON tp_tech_pack(sample_id)
    WHERE style_id IS NULL AND sample_id IS NOT NULL AND status='DRAFT';
COMMENT ON COLUMN tp_tech_pack.sample_id IS '真实来源样衣；未移交时为owner，移交后保留来源且style_id为owner';

-- 正常动作在 Java 先锁父、后锁子。下面 NOWAIT 是对反序原始 SQL 的失败保护，
-- 不拿它替代正常父行等待；同一样衣的业务动作通过父锁排队后重新检查两个版本。
CREATE FUNCTION scm_dev_lock_sample(t TEXT, c BIGINT, sid BIGINT) RETURNS dev_sample LANGUAGE plpgsql AS $$
DECLARE s dev_sample;
BEGIN
    SELECT * INTO s FROM dev_sample WHERE tenant_key=t AND company_id=c AND id=sid FOR UPDATE NOWAIT;
    IF NOT FOUND OR s.deleted_token<>0 THEN
        RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='样衣不存在或已删除';
    END IF;
    RETURN s;
END $$;

CREATE FUNCTION scm_dev_lock_order(t TEXT, c BIGINT, oid BIGINT) RETURNS dev_sample_order LANGUAGE plpgsql AS $$
DECLARE o dev_sample_order; s dev_sample;
BEGIN
    SELECT * INTO o FROM dev_sample_order WHERE tenant_key=t AND company_id=c AND id=oid;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='打样单不存在'; END IF;
    s := scm_dev_lock_sample(t,c,o.sample_id);
    SELECT * INTO o FROM dev_sample_order WHERE tenant_key=t AND company_id=c AND id=oid FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='打样单不存在'; END IF;
    RETURN o;
END $$;

CREATE FUNCTION scm_dev_sample_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_order dev_sample_order;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣只允许删除未派单草稿，必须保留审计身份';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.deleted_token<>0 OR NEW.status<>'DEVELOPING' THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='新样衣必须从开发中首版开始';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.version=2147483647 THEN
        RAISE EXCEPTION USING ERRCODE='40001',MESSAGE='样衣版本已到上限，请刷新并联系管理员';
    END IF;
    IF NEW.id<>OLD.id OR NEW.tenant_key<>OLD.tenant_key OR NEW.code<>OLD.code
       OR NEW.create_by IS DISTINCT FROM OLD.create_by OR NEW.create_time<>OLD.create_time
       OR OLD.deleted_token<>0 OR NEW.version<>OLD.version+1 THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣身份不可改，所有动作必须推进真实版本';
    END IF;
    IF NOT (CASE OLD.status
        WHEN 'DEVELOPING' THEN NEW.status IN ('DEVELOPING','PASSED','ELIMINATED')
        WHEN 'PASSED' THEN NEW.status IN ('PASSED','DEVELOPING','CONVERTED')
        WHEN 'ELIMINATED' THEN NEW.status='DEVELOPING'
        ELSE false END) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣状态流转不合法';
    END IF;
    SELECT * INTO current_order FROM dev_sample_order
    WHERE tenant_key=OLD.tenant_key AND company_id=OLD.company_id AND sample_id=OLD.id
    ORDER BY sample_version DESC LIMIT 1;
    IF NEW.company_id<>OLD.company_id AND (
        EXISTS(SELECT 1 FROM dev_sample_order WHERE sample_id=OLD.id AND dispatched_time IS NOT NULL)
        OR EXISTS(SELECT 1 FROM tp_tech_pack WHERE sample_id=OLD.id)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='已经派单或建立研发资料包，不能更换主体';
    END IF;
    IF NEW.size_group_id<>OLD.size_group_id AND (
        current_order.id IS NULL OR current_order.sample_version<>1 OR current_order.status<>'WAIT_ASSIGN'
        OR EXISTS(SELECT 1 FROM tp_tech_pack WHERE sample_id=OLD.id)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='已经开始打样，不能更换尺码组';
    END IF;
    IF NEW.deleted_token<>0 AND (
        NEW.deleted_token<>OLD.id OR NEW.status<>'DEVELOPING' OR current_order.sample_version<>1
        OR current_order.id IS NULL OR current_order.status<>'WAIT_ASSIGN'
        OR EXISTS(SELECT 1 FROM tp_tech_pack WHERE sample_id=OLD.id)
        OR EXISTS(SELECT 1 FROM dev_sample_node WHERE sample_order_id=current_order.id)
        OR EXISTS(SELECT 1 FROM dev_sample_review WHERE sample_order_id=current_order.id)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='仅无资料包、节点和评审的首版待派单草稿可删除';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dev_sample_guard BEFORE INSERT OR UPDATE OR DELETE ON dev_sample
    FOR EACH ROW EXECUTE FUNCTION scm_dev_sample_guard();
CREATE TRIGGER dev_sample_no_truncate BEFORE TRUNCATE ON dev_sample
    FOR EACH STATEMENT EXECUTE FUNCTION scm_dev_sample_guard();

CREATE FUNCTION scm_dev_order_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s dev_sample; latest dev_sample_order;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='打样单历史不可物理删除';
    END IF;
    s := scm_dev_lock_sample(NEW.tenant_key,NEW.company_id,NEW.sample_id);
    SELECT * INTO latest FROM dev_sample_order WHERE tenant_key=NEW.tenant_key AND sample_id=NEW.sample_id
        ORDER BY sample_version DESC LIMIT 1;
    IF TG_OP='INSERT' THEN
        IF latest.sample_version=2147483647 THEN
            RAISE EXCEPTION USING ERRCODE='40001',MESSAGE='打样业务版次已到上限';
        END IF;
        IF s.status='CONVERTED' OR NEW.status<>'WAIT_ASSIGN' OR NEW.version<>0 OR NEW.dispatched_time IS NOT NULL
           OR NEW.sample_version<>COALESCE(latest.sample_version,0)+1 OR NEW.size_group_id<>s.size_group_id
           OR (latest.id IS NOT NULL AND latest.status NOT IN ('REVISE','PASSED','ELIMINATED','CANCELLED')) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='新打样单须在父锁内按下一版次创建，前一版须已结束';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.id<>OLD.id OR NEW.tenant_key<>OLD.tenant_key OR NEW.sample_id<>OLD.sample_id OR NEW.no<>OLD.no
       OR NEW.sample_version<>OLD.sample_version OR NEW.create_by IS DISTINCT FROM OLD.create_by
       OR NEW.create_time<>OLD.create_time THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='打样单身份不可改';
    END IF;
    -- 公司同步仅可由已锁主档、尚未派单/无资料包的更换主体事务执行；其他内容完全不动。
    IF NEW.company_id<>OLD.company_id AND (to_jsonb(NEW)-'company_id')=(to_jsonb(OLD)-'company_id') THEN
        IF OLD.dispatched_time IS NOT NULL OR EXISTS(SELECT 1 FROM tp_tech_pack WHERE sample_id=OLD.sample_id) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='已派单打样单不能换主体';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.version=2147483647 THEN
        RAISE EXCEPTION USING ERRCODE='40001',MESSAGE='打样单版本已到上限，请刷新并联系管理员';
    END IF;
    IF NEW.company_id<>OLD.company_id OR latest.id<>OLD.id OR NEW.version<>OLD.version+1
       OR OLD.status NOT IN ('WAIT_ASSIGN','MAKING','WAIT_REVIEW') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='仅当前活动版允许以真实版本更新';
    END IF;
    IF NOT (CASE OLD.status
        WHEN 'WAIT_ASSIGN' THEN NEW.status IN ('WAIT_ASSIGN','MAKING','CANCELLED')
        WHEN 'MAKING' THEN NEW.status IN ('MAKING','WAIT_REVIEW','CANCELLED')
        WHEN 'WAIT_REVIEW' THEN NEW.status IN ('MAKING','WAIT_REVIEW','PASSED','REVISE','ELIMINATED','CANCELLED')
        ELSE false END) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='打样单状态流转不合法';
    END IF;
    IF OLD.status<>'WAIT_ASSIGN' AND (NEW.sample_type<>OLD.sample_type OR NEW.qty<>OLD.qty
        OR NEW.size_group_id<>OLD.size_group_id OR NEW.reason IS DISTINCT FROM OLD.reason) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='派单后不能改本版类型、数量、尺码组和改版原因';
    END IF;
    IF OLD.status='WAIT_REVIEW' AND (NEW.make_way IS DISTINCT FROM OLD.make_way
        OR NEW.factory_id IS DISTINCT FROM OLD.factory_id OR NEW.maker_id IS DISTINCT FROM OLD.maker_id
        OR NEW.need_date<>OLD.need_date OR NEW.requirement IS DISTINCT FROM OLD.requirement) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='待评审不能改派或修改打样单内容';
    END IF;
    IF OLD.dispatched_time IS NOT NULL AND (NEW.dispatched_time IS DISTINCT FROM OLD.dispatched_time
        OR NEW.dispatched_by IS DISTINCT FROM OLD.dispatched_by) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='首次派单记录不可清除或改写';
    END IF;
    IF OLD.dispatched_time IS NULL AND NEW.dispatched_time IS NOT NULL
        AND NOT (OLD.status='WAIT_ASSIGN' AND NEW.status='MAKING') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='首次派单记录只允许由派单动作写入';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dev_sample_order_guard BEFORE INSERT OR UPDATE OR DELETE ON dev_sample_order
    FOR EACH ROW EXECUTE FUNCTION scm_dev_order_guard();
CREATE TRIGGER dev_sample_order_no_truncate BEFORE TRUNCATE ON dev_sample_order
    FOR EACH STATEMENT EXECUTE FUNCTION scm_dev_order_guard();

CREATE FUNCTION scm_dev_selection_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_value JSONB; s dev_sample; o dev_sample_order; sid BIGINT; oid BIGINT;
BEGIN
    IF TG_OP='TRUNCATE' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣选择范围不可清空历史'; END IF;
    row_value := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    IF TG_OP='UPDATE' AND ((to_jsonb(NEW)->>'tenant_key')<>(to_jsonb(OLD)->>'tenant_key')
        OR (to_jsonb(NEW)->>'sample_id') IS DISTINCT FROM (to_jsonb(OLD)->>'sample_id')
        OR (to_jsonb(NEW)->>'sample_order_id') IS DISTINCT FROM (to_jsonb(OLD)->>'sample_order_id')
        OR (to_jsonb(NEW)->>'color_id') IS DISTINCT FROM (to_jsonb(OLD)->>'color_id')
        OR (to_jsonb(NEW)->>'size_code') IS DISTINCT FROM (to_jsonb(OLD)->>'size_code')) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣选择关联不可重新挂靠';
    END IF;
    IF TG_TABLE_NAME='dev_sample_color' THEN
        sid := (row_value->>'sample_id')::bigint;
        s := scm_dev_lock_sample(row_value->>'tenant_key',(row_value->>'company_id')::bigint,sid);
        IF s.status NOT IN ('DEVELOPING','PASSED') THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='当前样衣状态不能修改颜色';
        END IF;
    ELSE
        oid := (row_value->>'sample_order_id')::bigint;
        o := scm_dev_lock_order(row_value->>'tenant_key',(row_value->>'company_id')::bigint,oid);
        IF TG_OP='UPDATE' AND (to_jsonb(NEW)-'company_id')=(to_jsonb(OLD)-'company_id') THEN
            RETURN NEW;
        END IF;
        IF o.status<>'WAIT_ASSIGN' OR o.id<>(SELECT id FROM dev_sample_order WHERE sample_id=o.sample_id
            ORDER BY sample_version DESC LIMIT 1) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='派单后本版颜色尺码快照不可变';
        END IF;
        IF TG_TABLE_NAME='dev_sample_order_color' AND TG_OP<>'DELETE' AND NOT EXISTS(
            SELECT 1 FROM dev_sample_color WHERE tenant_key=o.tenant_key AND company_id=o.company_id
            AND sample_id=o.sample_id AND color_id=(row_value->>'color_id')::bigint) THEN
            RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='打样颜色不属于样衣档案';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dev_sample_color_guard BEFORE INSERT OR UPDATE OR DELETE ON dev_sample_color
    FOR EACH ROW EXECUTE FUNCTION scm_dev_selection_guard();
CREATE TRIGGER dev_sample_order_color_guard BEFORE INSERT OR UPDATE OR DELETE ON dev_sample_order_color
    FOR EACH ROW EXECUTE FUNCTION scm_dev_selection_guard();
CREATE TRIGGER dev_sample_order_size_guard BEFORE INSERT OR UPDATE OR DELETE ON dev_sample_order_size
    FOR EACH ROW EXECUTE FUNCTION scm_dev_selection_guard();
CREATE TRIGGER dev_sample_color_no_truncate BEFORE TRUNCATE ON dev_sample_color
    FOR EACH STATEMENT EXECUTE FUNCTION scm_dev_selection_guard();
CREATE TRIGGER dev_sample_order_color_no_truncate BEFORE TRUNCATE ON dev_sample_order_color
    FOR EACH STATEMENT EXECUTE FUNCTION scm_dev_selection_guard();
CREATE TRIGGER dev_sample_order_size_no_truncate BEFORE TRUNCATE ON dev_sample_order_size
    FOR EACH STATEMENT EXECUTE FUNCTION scm_dev_selection_guard();

CREATE FUNCTION scm_dev_node_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o dev_sample_order;
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='打样节点历史不可删除'; END IF;
    o := scm_dev_lock_order(NEW.tenant_key,NEW.company_id,NEW.sample_order_id);
    IF o.id<>(SELECT id FROM dev_sample_order WHERE sample_id=o.sample_id ORDER BY sample_version DESC LIMIT 1)
        OR o.status NOT IN ('WAIT_ASSIGN','MAKING','WAIT_REVIEW') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='历史打样版本节点只读';
    END IF;
    IF TG_OP='INSERT' THEN
        IF o.status NOT IN ('WAIT_ASSIGN','MAKING') OR NEW.done_date IS NOT NULL OR NEW.done_by IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='派单只可生成未完成节点';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.id<>OLD.id OR NEW.tenant_key<>OLD.tenant_key OR NEW.company_id<>OLD.company_id
        OR NEW.sample_order_id<>OLD.sample_order_id OR NEW.seq<>OLD.seq OR NEW.node_code<>OLD.node_code
        OR NEW.node_name<>OLD.node_name OR NEW.duration_days<>OLD.duration_days THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='打样节点身份和字典快照不可改';
    END IF;
    IF o.status NOT IN ('MAKING','WAIT_REVIEW') OR (o.status='WAIT_REVIEW' AND
        (NEW.plan_date<>OLD.plan_date OR NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.remark IS DISTINCT FROM OLD.remark)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='当前状态不能修改节点计划';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dev_sample_node_guard BEFORE INSERT OR UPDATE OR DELETE ON dev_sample_node
    FOR EACH ROW EXECUTE FUNCTION scm_dev_node_guard();
CREATE TRIGGER dev_sample_node_no_truncate BEFORE TRUNCATE ON dev_sample_node
    FOR EACH STATEMENT EXECUTE FUNCTION scm_dev_node_guard();

CREATE FUNCTION scm_dev_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o dev_sample_order;
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣评审只增不改'; END IF;
    o := scm_dev_lock_order(NEW.tenant_key,NEW.company_id,NEW.sample_order_id);
    IF o.id<>(SELECT id FROM dev_sample_order WHERE sample_id=o.sample_id ORDER BY sample_version DESC LIMIT 1)
        OR o.status NOT IN ('WAIT_REVIEW','PASSED','REVISE','ELIMINATED') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='只有当前送样版本可评审';
    END IF;
    NEW.recorded_txid := txid_current();
    RETURN NEW;
END $$;
CREATE TRIGGER dev_sample_review_guard BEFORE INSERT OR UPDATE OR DELETE ON dev_sample_review
    FOR EACH ROW EXECUTE FUNCTION scm_dev_review_guard();
CREATE TRIGGER dev_sample_review_no_truncate BEFORE TRUNCATE ON dev_sample_review
    FOR EACH STATEMENT EXECUTE FUNCTION scm_dev_review_guard();

CREATE FUNCTION scm_dev_reviewer_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r dev_sample_review; o dev_sample_order;
BEGIN
    IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣评审人记录不可变'; END IF;
    SELECT * INTO r FROM dev_sample_review WHERE tenant_key=NEW.tenant_key AND company_id=NEW.company_id AND id=NEW.review_id;
    IF NOT FOUND OR r.recorded_txid<>txid_current() THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='评审人只能在创建评审的同一事务中确定';
    END IF;
    o := scm_dev_lock_order(NEW.tenant_key,NEW.company_id,r.sample_order_id);
    RETURN NEW;
END $$;
CREATE TRIGGER dev_sample_reviewer_guard BEFORE INSERT OR UPDATE OR DELETE ON dev_sample_reviewer
    FOR EACH ROW EXECUTE FUNCTION scm_dev_reviewer_guard();
CREATE TRIGGER dev_sample_reviewer_no_truncate BEFORE TRUNCATE ON dev_sample_reviewer
    FOR EACH STATEMENT EXECUTE FUNCTION scm_dev_reviewer_guard();

-- 提交时校验最终组合，允许同一事务先写主档/子单/评审的任意合法中间状态。
-- 不在跨事务瞬间伪造成功：缺少首版、只改一层状态、派单没生成节点或评审无评审人都会回滚。
CREATE FUNCTION scm_dev_validate_sample(t TEXT, sid BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE s dev_sample; o dev_sample_order; color_count BIGINT;
BEGIN
    SELECT * INTO s FROM dev_sample WHERE tenant_key=t AND id=sid;
    IF NOT FOUND OR s.deleted_token<>0 THEN RETURN; END IF;
    SELECT * INTO o FROM dev_sample_order WHERE tenant_key=t AND company_id=s.company_id AND sample_id=s.id
        ORDER BY sample_version DESC LIMIT 1;
    IF o.id IS NULL OR NOT (CASE s.status
        WHEN 'DEVELOPING' THEN o.status IN ('WAIT_ASSIGN','MAKING','WAIT_REVIEW')
        WHEN 'PASSED' THEN o.status='PASSED'
        WHEN 'CONVERTED' THEN o.status='PASSED'
        WHEN 'ELIMINATED' THEN o.status IN ('ELIMINATED','CANCELLED') ELSE false END) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣与当前版状态不一致或缺少首版';
    END IF;
    SELECT count(*) INTO color_count FROM dev_sample_color WHERE tenant_key=t AND company_id=s.company_id AND sample_id=sid;
    IF color_count<1 OR color_count>100 THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣必须保留1至100个真实颜色';
    END IF;
    IF o.status='WAIT_ASSIGN' AND (o.size_group_id<>s.size_group_id OR EXISTS(
        SELECT 1 FROM dev_sample_order_color c WHERE c.tenant_key=t AND c.company_id=s.company_id AND c.sample_order_id=o.id
        AND NOT EXISTS(SELECT 1 FROM dev_sample_color sc WHERE sc.tenant_key=t AND sc.company_id=s.company_id
            AND sc.sample_id=s.id AND sc.color_id=c.color_id))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='待派单选择必须与样衣颜色尺码范围一并更新';
    END IF;
    IF s.status='CONVERTED' AND EXISTS(SELECT 1 FROM tp_tech_pack p WHERE p.sample_id=sid AND
        (p.style_id IS NULL OR p.style_id<>s.converted_style_id)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣转大货必须在同一事务移交全部资料包';
    END IF;
END $$;

CREATE FUNCTION scm_dev_validate_order(t TEXT, oid BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE o dev_sample_order; s dev_sample; r dev_sample_review; colors BIGINT; sizes BIGINT;
    node_count BIGINT; send_count BIGINT; max_seq INT; send_seq INT; send_done DATE; people BIGINT; latest_id BIGINT;
BEGIN
    SELECT * INTO o FROM dev_sample_order WHERE tenant_key=t AND id=oid;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT * INTO s FROM dev_sample WHERE tenant_key=t AND company_id=o.company_id AND id=o.sample_id;
    IF s.id IS NULL THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='打样单实际主体与样衣不一致'; END IF;
    IF s.deleted_token<>0 THEN RETURN; END IF;
    SELECT id INTO latest_id FROM dev_sample_order WHERE tenant_key=t AND company_id=o.company_id AND sample_id=o.sample_id
        ORDER BY sample_version DESC LIMIT 1;
    IF latest_id<>o.id AND o.status IN ('WAIT_ASSIGN','MAKING','WAIT_REVIEW') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='新版本建立后旧版本不能继续活动';
    END IF;
    SELECT count(*) INTO colors FROM dev_sample_order_color WHERE tenant_key=t AND company_id=o.company_id AND sample_order_id=oid;
    SELECT count(*) INTO sizes FROM dev_sample_order_size WHERE tenant_key=t AND company_id=o.company_id AND sample_order_id=oid;
    IF colors NOT BETWEEN 1 AND 100 OR sizes NOT BETWEEN 1 AND 100 OR colors*sizes>10000 THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='打样单颜色尺码范围缺失或超限';
    END IF;
    IF o.status='WAIT_ASSIGN' AND (o.size_group_id<>s.size_group_id OR EXISTS(
        SELECT 1 FROM dev_sample_order_color c WHERE c.tenant_key=t AND c.company_id=o.company_id AND c.sample_order_id=oid
        AND NOT EXISTS(SELECT 1 FROM dev_sample_color sc WHERE sc.tenant_key=t AND sc.company_id=o.company_id
            AND sc.sample_id=s.id AND sc.color_id=c.color_id))) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='待派单的颜色尺码必须同步样衣当前范围';
    END IF;
    SELECT count(*),count(*) FILTER(WHERE node_code='SEND'),max(seq),
           max(seq) FILTER(WHERE node_code='SEND'),max(done_date) FILTER(WHERE node_code='SEND')
        INTO node_count,send_count,max_seq,send_seq,send_done
        FROM dev_sample_node WHERE tenant_key=t AND company_id=o.company_id AND sample_order_id=oid;
    IF o.dispatched_time IS NULL THEN
        IF o.status NOT IN ('WAIT_ASSIGN','CANCELLED') OR node_count<>0 THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='未派单版本不能有节点或已派单状态';
        END IF;
    ELSE
        IF o.status='WAIT_ASSIGN' OR node_count NOT BETWEEN 1 AND 100 OR send_count<>1 OR send_seq<>max_seq
            OR (o.status='MAKING' AND send_done IS NOT NULL)
            OR (o.status IN ('WAIT_REVIEW','PASSED','REVISE','ELIMINATED') AND send_done IS NULL) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='打样节点送样记录与真实单据状态不一致';
        END IF;
    END IF;
    SELECT * INTO r FROM dev_sample_review WHERE tenant_key=t AND company_id=o.company_id AND sample_order_id=oid;
    IF o.status IN ('PASSED','REVISE','ELIMINATED') THEN
        IF r.id IS NULL OR r.result<>(CASE o.status WHEN 'PASSED' THEN 'PASS' WHEN 'REVISE' THEN 'REVISE' ELSE 'ELIMINATE' END) THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='评审结论与打样单状态不一致';
        END IF;
    ELSIF r.id IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='尚未评审或已取消版本不能伪造评审记录';
    END IF;
    IF r.id IS NOT NULL THEN
        SELECT count(*) INTO people FROM dev_sample_reviewer WHERE tenant_key=t AND company_id=o.company_id AND review_id=r.id;
        IF people NOT BETWEEN 1 AND 100 THEN
            RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='评审必须有1至100位真实评审人';
        END IF;
    END IF;
    PERFORM scm_dev_validate_sample(t,o.sample_id);
END $$;

CREATE FUNCTION scm_dev_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value JSONB; t TEXT; sid BIGINT; oid BIGINT;
BEGIN
    value := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    t := value->>'tenant_key';
    CASE TG_TABLE_NAME
        WHEN 'dev_sample' THEN PERFORM scm_dev_validate_sample(t,(value->>'id')::bigint);
        WHEN 'dev_sample_color' THEN PERFORM scm_dev_validate_sample(t,(value->>'sample_id')::bigint);
        WHEN 'dev_sample_order' THEN PERFORM scm_dev_validate_order(t,(value->>'id')::bigint);
        WHEN 'dev_sample_reviewer' THEN
            SELECT sample_order_id INTO oid FROM dev_sample_review WHERE tenant_key=t AND id=(value->>'review_id')::bigint;
            PERFORM scm_dev_validate_order(t,oid);
        ELSE PERFORM scm_dev_validate_order(t,(value->>'sample_order_id')::bigint);
    END CASE;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER dev_sample_consistency AFTER INSERT OR UPDATE ON dev_sample
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_dev_consistency();
CREATE CONSTRAINT TRIGGER dev_sample_color_consistency AFTER INSERT OR UPDATE OR DELETE ON dev_sample_color
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_dev_consistency();
CREATE CONSTRAINT TRIGGER dev_sample_order_consistency AFTER INSERT OR UPDATE ON dev_sample_order
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_dev_consistency();
CREATE CONSTRAINT TRIGGER dev_sample_order_color_consistency AFTER INSERT OR UPDATE OR DELETE ON dev_sample_order_color
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_dev_consistency();
CREATE CONSTRAINT TRIGGER dev_sample_order_size_consistency AFTER INSERT OR UPDATE OR DELETE ON dev_sample_order_size
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_dev_consistency();
CREATE CONSTRAINT TRIGGER dev_sample_node_consistency AFTER INSERT OR UPDATE ON dev_sample_node
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_dev_consistency();
CREATE CONSTRAINT TRIGGER dev_sample_review_consistency AFTER INSERT ON dev_sample_review
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_dev_consistency();
CREATE CONSTRAINT TRIGGER dev_sample_reviewer_consistency AFTER INSERT ON dev_sample_reviewer
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_dev_consistency();

-- 资料包实际 owner 的并发守卫。Service 正常先锁 sample，原始反序 SQL 只允许立即失败，不能互等。
CREATE FUNCTION scm_tp_sample_owner_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s dev_sample; sid BIGINT;
BEGIN
    IF TG_OP='DELETE' THEN sid:=OLD.sample_id; ELSE sid:=NEW.sample_id; END IF;
    IF TG_OP='UPDATE' AND (NEW.sample_id IS DISTINCT FROM OLD.sample_id
        OR (OLD.style_id IS NOT NULL AND NEW.style_id IS DISTINCT FROM OLD.style_id)) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='资料包真实来源和既定款式不可重新挂靠';
    END IF;
    IF sid IS NULL THEN IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF;
    -- 已移交的包属于款式，样衣来源不是它的写权限或锁归属。
    IF TG_OP='DELETE' AND OLD.style_id IS NOT NULL THEN RETURN OLD; END IF;
    IF TG_OP='UPDATE' AND OLD.style_id IS NOT NULL THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' AND NEW.style_id IS NOT NULL THEN RETURN NEW; END IF;
    SELECT * INTO s FROM dev_sample WHERE id=sid FOR UPDATE NOWAIT;
    IF NOT FOUND OR s.deleted_token<>0 OR s.status NOT IN ('DEVELOPING','PASSED') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='样衣已淘汰、删除或移交，不能修改其研发资料包';
    END IF;
    IF TG_OP='UPDATE' AND NEW.style_id IS NOT NULL AND
        (s.status<>'PASSED' OR NEW.version_lock<>OLD.version_lock+1) THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='只有已通过样衣可移交资料包且必须推进版本';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER tp_sample_owner_guard BEFORE INSERT OR UPDATE OR DELETE ON tp_tech_pack
    FOR EACH ROW EXECUTE FUNCTION scm_tp_sample_owner_guard();

CREATE FUNCTION scm_tp_sample_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p tp_tech_pack; s dev_sample;
BEGIN
    SELECT * INTO p FROM tp_tech_pack WHERE id=NEW.id;
    IF NOT FOUND OR p.sample_id IS NULL THEN RETURN NULL; END IF;
    SELECT * INTO s FROM dev_sample WHERE id=p.sample_id;
    IF s.id IS NULL OR s.deleted_token<>0 OR (p.style_id IS NOT NULL AND
        (s.status<>'CONVERTED' OR p.style_id IS DISTINCT FROM s.converted_style_id)) OR
        (p.style_id IS NULL AND s.status='CONVERTED') THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='资料包真实样衣来源与移交款式不一致';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER tp_sample_consistency AFTER INSERT OR UPDATE ON tp_tech_pack
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION scm_tp_sample_consistency();

-- V58 已冻结；这里在新领域上线时追加第十三个真实父头，保留原十二类行为。
ALTER TABLE biz_tag_object DROP CONSTRAINT biz_tag_object_object_type_check;
ALTER TABLE biz_tag_object ADD CONSTRAINT biz_tag_object_object_type_check CHECK (object_type IN (
    'STYLE','MATERIAL','SUPPLIER','TECH_PACK','ORDER','MRP_CALC','PURCHASE_ORDER',
    'INV_DOC','INV_COUNT','PRODUCTION_ORDER','PRODUCTION_RECEIPT','FG_DOC','SAMPLE'));
COMMENT ON COLUMN biz_tag_object.object_id IS '13类真实父头的多态ID；固定CASE父锁和清理触发器保护，SAMPLE由V59接入';

CREATE OR REPLACE FUNCTION scm_tag_lock_owner(kind TEXT, owner_id BIGINT) RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE actual_company BIGINT; before_style BIGINT; before_sample BIGINT; after_style BIGINT; after_sample BIGINT;
BEGIN
    CASE kind
        WHEN 'STYLE' THEN SELECT NULL::bigint INTO actual_company FROM sty_style WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'MATERIAL' THEN SELECT NULL::bigint INTO actual_company FROM bas_material WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'SUPPLIER' THEN SELECT NULL::bigint INTO actual_company FROM sup_supplier WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'TECH_PACK' THEN
            SELECT style_id,sample_id INTO before_style,before_sample FROM tp_tech_pack WHERE id=owner_id;
            IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='标签对象不存在'; END IF;
            actual_company:=NULL;
            IF before_style IS NULL THEN
                SELECT company_id INTO actual_company FROM dev_sample WHERE id=before_sample AND deleted_token=0 FOR SHARE NOWAIT;
                IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='资料包样衣不存在'; END IF;
            END IF;
            SELECT style_id,sample_id INTO after_style,after_sample FROM tp_tech_pack WHERE id=owner_id FOR SHARE NOWAIT;
            IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='标签对象不存在'; END IF;
            IF before_style IS DISTINCT FROM after_style OR before_sample IS DISTINCT FROM after_sample THEN
                RAISE EXCEPTION USING ERRCODE='40001',MESSAGE='资料包归属已变化，请刷新后再试';
            END IF;
        WHEN 'ORDER' THEN SELECT company_id INTO actual_company FROM ord_order WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'MRP_CALC' THEN SELECT company_id INTO actual_company FROM mrp_calc WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'PURCHASE_ORDER' THEN SELECT company_id INTO actual_company FROM pur_order WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'INV_DOC' THEN SELECT company_id INTO actual_company FROM inv_doc WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'INV_COUNT' THEN SELECT company_id INTO actual_company FROM inv_count WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'PRODUCTION_ORDER' THEN SELECT company_id INTO actual_company FROM prd_order WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'PRODUCTION_RECEIPT' THEN SELECT company_id INTO actual_company FROM prd_receipt WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'FG_DOC' THEN SELECT company_id INTO actual_company FROM fg_doc WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'SAMPLE' THEN SELECT company_id INTO actual_company FROM dev_sample WHERE id=owner_id AND deleted_token=0 FOR SHARE NOWAIT;
        ELSE RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='不支持的标签对象类型';
    END CASE;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='标签对象不存在'; END IF;
    RETURN actual_company;
END $$;

CREATE FUNCTION scm_dev_tag_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.deleted_token=0 AND NEW.deleted_token<>0 THEN
        -- BEFORE UPDATE，父头仍能只读验证；object 自身守卫按 link→object 清理并保留 Diff。
        DELETE FROM biz_tag_object WHERE tenant_key=OLD.tenant_key AND object_type='SAMPLE' AND object_id=OLD.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tag_sample_tombstone BEFORE UPDATE OF deleted_token ON dev_sample
    FOR EACH ROW EXECUTE FUNCTION scm_dev_tag_tombstone();
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON dev_sample
    FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('SAMPLE');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON dev_sample
    FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('SAMPLE');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON dev_sample
    FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('SAMPLE');

CREATE FUNCTION scm_tp_tag_owner_company() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_company BIGINT;
BEGIN
    IF NEW.style_id IS DISTINCT FROM OLD.style_id OR NEW.sample_id IS DISTINCT FROM OLD.sample_id THEN
        IF NEW.style_id IS NULL THEN SELECT company_id INTO actual_company FROM dev_sample WHERE id=NEW.sample_id;
        ELSE actual_company:=NULL; END IF;
        UPDATE biz_tag_object SET company_id=actual_company,update_by=NEW.update_by,update_time=clock_timestamp()
        WHERE object_type='TECH_PACK' AND object_id=NEW.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tag_tp_owner_company AFTER UPDATE OF style_id,sample_id ON tp_tech_pack
    FOR EACH ROW EXECUTE FUNCTION scm_tp_tag_owner_company();

-- 字段审计固定根增加 SAMPLE；不扩大公开 DataLog 读取权限，公开鉴权由独立审计接线完成。
CREATE OR REPLACE FUNCTION scm_capture_tag_diff() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    before_row JSONB := CASE WHEN TG_OP='INSERT' THEN '{}'::jsonb ELSE scm_tag_audit_project(TG_TABLE_NAME,to_jsonb(OLD)) END;
    after_row JSONB := CASE WHEN TG_OP='DELETE' THEN '{}'::jsonb ELSE scm_tag_audit_project(TG_TABLE_NAME,to_jsonb(NEW)) END;
    actual JSONB := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    changes JSONB; namespace TEXT; owner_kind TEXT; owner_id BIGINT;
    root_name TEXT; domain TEXT; actor TEXT := NULLIF(current_setting('scm.audit_actor',true),'');
    actor_id BIGINT;
BEGIN
    changes := scm_audit_diff(before_row,after_row);
    IF changes IS NULL THEN RETURN NULL; END IF;
    SELECT tenant_id INTO STRICT namespace FROM sys_deployment_namespace WHERE singleton;
    IF actual->>'tenant_key'<>namespace THEN
        RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='标签命名空间与部署不一致';
    END IF;
    IF TG_TABLE_NAME='biz_tag' THEN
        root_name := 'biz_tag'; owner_id := (actual->>'id')::bigint;
    ELSE
        IF TG_TABLE_NAME='biz_tag_object' THEN
            owner_kind := actual->>'object_type'; owner_id := (actual->>'object_id')::bigint;
        ELSE
            SELECT object_type,object_id INTO STRICT owner_kind,owner_id FROM biz_tag_object
            WHERE tenant_key=namespace AND id=(actual->>'object_ref_id')::bigint;
        END IF;
        root_name := CASE owner_kind
            WHEN 'STYLE' THEN 'sty_style' WHEN 'MATERIAL' THEN 'bas_material' WHEN 'SUPPLIER' THEN 'sup_supplier'
            WHEN 'TECH_PACK' THEN 'tp_tech_pack' WHEN 'ORDER' THEN 'ord_order' WHEN 'MRP_CALC' THEN 'mrp_calc'
            WHEN 'PURCHASE_ORDER' THEN 'pur_order' WHEN 'INV_DOC' THEN 'inv_doc' WHEN 'INV_COUNT' THEN 'inv_count'
            WHEN 'SAMPLE' THEN 'dev_sample' WHEN 'PRODUCTION_ORDER' THEN 'prd_order' WHEN 'PRODUCTION_RECEIPT' THEN 'prd_receipt' WHEN 'FG_DOC' THEN 'fg_doc'
            ELSE NULL END;
        IF root_name IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='不支持的标签审计归属'; END IF;
    END IF;
    domain := CASE WHEN root_name IN ('ord_order','fg_doc') THEN 'ORDER'
                   WHEN root_name IN ('prd_order','prd_receipt') THEN 'PRODUCTION' ELSE 'COST' END;
    IF actor ~ '^[0-9]{1,19}$' THEN
        IF actor::numeric<=9223372036854775807 THEN actor_id := actor::bigint; END IF;
    END IF;
    INSERT INTO sys_data_log(tenant_key,table_name,row_id,root_table,root_id,price_domain,action,diff,
        actor_user_id,client_ip,request_id,operation,transaction_id)
    VALUES(namespace,TG_TABLE_NAME,(actual->>'id')::bigint,root_name,owner_id,domain,TG_OP,changes,actor_id,
        NULLIF(left(current_setting('scm.audit_ip',true),64),''),
        NULLIF(left(current_setting('scm.audit_request',true),64),''),
        NULLIF(left(current_setting('scm.audit_operation',true),255),''),txid_current());
    RETURN NULL;
END $$;

-- 只插入系统配置，不在部署 namespace 初始化前插入任何样衣或标签业务数据。
INSERT INTO sys_dict_type(code,name,num_label,remark)
VALUES('sample_node','打样节点','默认天数','SEND为送样语义节点，须唯一启用且排最后；已派单版本保留快照')
ON CONFLICT(code) WHERE deleted=false DO NOTHING;
UPDATE sys_dict_type SET num_label='默认天数' WHERE code='sample_node' AND NOT deleted AND num_label IS NULL;
INSERT INTO sys_dict_data(type_code,label,value,sort,num_value,builtin)
VALUES('sample_node','接单','RECEIVE',10,1,false),
      ('sample_node','纸样','PATTERN',20,2,false),
      ('sample_node','裁剪','CUT',30,1,false),
      ('sample_node','车版','SEW',40,3,false),
      ('sample_node','送样','SEND',50,1,true)
ON CONFLICT(type_code,value) WHERE deleted=false DO NOTHING;

INSERT INTO sys_menu(parent_id,name,type,path,component,perms,icon,sort,visible,admin_only)
SELECT id,'样衣',2,'/samples','dev/sample/index','dev:sample:list','EditPen',20,true,false
FROM sys_menu WHERE parent_id=0 AND path='/dev' AND NOT deleted
AND NOT EXISTS(SELECT 1 FROM sys_menu m WHERE m.perms='dev:sample:list' AND NOT m.deleted);
INSERT INTO sys_menu(parent_id,name,type,perms,sort,visible,admin_only)
SELECT m.id,p.name,3,p.perm,p.sort,true,false
FROM sys_menu m CROSS JOIN (VALUES
    ('新建','dev:sample:add',1),('编辑','dev:sample:edit',2),('删除草稿','dev:sample:remove',3),
    ('派单、报节点','dev:sample:make',4),('评审、终止、恢复','dev:sample:review',5),('转大货','dev:sample:convert',6)
) p(name,perm,sort)
WHERE m.perms='dev:sample:list' AND NOT m.deleted
AND NOT EXISTS(SELECT 1 FROM sys_menu b WHERE b.perms=p.perm AND NOT b.deleted);
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.perms IN (
    'dev:sample:list','dev:sample:add','dev:sample:edit','dev:sample:remove','dev:sample:make','dev:sample:review','dev:sample:convert')
WHERE NOT r.deleted AND NOT m.deleted AND (r.code IN ('dev','dev_lead')
    OR r.code='manager' AND m.perms IN ('dev:sample:list','dev:sample:review'))
ON CONFLICT DO NOTHING;
WITH RECURSIVE up(role_id,menu_id) AS (
    SELECT rm.role_id,m.parent_id FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
    JOIN sys_role r ON r.id=rm.role_id
    WHERE r.code IN ('dev','dev_lead','manager') AND NOT r.deleted AND NOT m.deleted
        AND m.perms='dev:sample:list' AND m.parent_id<>0
    UNION SELECT up.role_id,m.parent_id FROM up JOIN sys_menu m ON m.id=up.menu_id WHERE m.parent_id<>0 AND NOT m.deleted
)
INSERT INTO sys_role_menu(role_id,menu_id) SELECT role_id,menu_id FROM up ON CONFLICT DO NOTHING;
