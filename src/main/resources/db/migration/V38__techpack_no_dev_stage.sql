-- V38：资料包去掉研发阶段（甲方 11-18 答 Q24：研发不填物料清单，只有转大货后的标准资料包，PRD v0.22 §6.4.1）。
-- 代码里 PackStage、TechPackStatus 已经没有 DEV，库里要是有 DEV 的行，读出来就会报错。
-- 写这个迁移时开发库、试用环境都没有这种行（试用环境还没有资料包表），所以不搬数据，只在万一有的时候停下来，
-- 让人先决定怎么处理，不悄悄删。
-- stage、source_pack_id 两列和 V20 的三个唯一索引（WHERE stage IN ('DEV', 'PROD')）不动：二期生产单资料包（ORDER）还要用 stage。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tp_tech_pack WHERE stage = 'DEV') THEN
        RAISE EXCEPTION '库里还有研发 BOM（tp_tech_pack.stage = DEV），新版本不认了：先决定删掉还是改成生产 BOM，再升级';
    END IF;
    IF EXISTS (SELECT 1 FROM sty_style WHERE tech_pack_status = 'DEV') THEN
        RAISE EXCEPTION '库里还有款式的资料包状态是 DEV（研发中），新版本不认了：先改成 NONE 或 PROD，再升级';
    END IF;
END
$$;
