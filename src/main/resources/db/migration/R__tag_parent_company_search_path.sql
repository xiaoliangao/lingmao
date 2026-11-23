-- MRP 历史主体修复会同步已有标签 registry。恢复/受限连接的空 search_path 不能打断这条守卫链。
-- 仅固定 V58/V59 现有函数的名字解析，不改原函数体、父头锁、主体一致性或标签版本规则。
-- 不扫描/回填任何业务数据，不改已发布版本迁移。
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

ALTER FUNCTION public.scm_tag_parent_company() SET search_path = pg_catalog, public;
ALTER FUNCTION public.scm_tag_object_guard() SET search_path = pg_catalog, public;
ALTER FUNCTION public.scm_tag_lock_owner(TEXT, BIGINT) SET search_path = pg_catalog, public;
ALTER FUNCTION public.scm_capture_tag_diff() SET search_path = pg_catalog, public;
ALTER FUNCTION public.scm_tag_audit_project(TEXT, JSONB) SET search_path = pg_catalog, public;
