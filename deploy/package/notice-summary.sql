-- psql 专用；一次调用只做一个有界动作，不在客户端自动循环整库。
-- 例：psql -X -v action=backfill -v batch=500 -f notice-summary.sql
-- action: status / restart / backfill / check-start / check / enable / fallback
\set ON_ERROR_STOP on
\if :{?action}
\else
  \set action status
\endif
\if :{?batch}
\else
  \set batch 500
\endif

BEGIN ISOLATION LEVEL READ COMMITTED;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '5s';
SET LOCAL idle_in_transaction_session_timeout = '10s';

-- :'变量' 使用 psql 的 SQL 字面量转义；不把 action/批量参数拼成 SQL 代码。
SELECT :'action' IN ('status','restart','backfill','check-start','check','enable','fallback') AS valid_action,
       :'action' = 'restart' AS do_restart,
       :'action' = 'backfill' AS do_backfill,
       :'action' = 'check-start' AS do_check_start,
       :'action' = 'check' AS do_check,
       :'action' = 'enable' AS do_enable,
       :'action' = 'fallback' AS do_fallback
\gset

\if :valid_action
\else
  DO $$ BEGIN RAISE EXCEPTION 'Unknown notice summary action'; END $$;
\endif

\if :do_restart
  SELECT scm_notice_pending_restart();
\endif
\if :do_backfill
  SELECT * FROM scm_notice_pending_backfill(CAST(:'batch' AS integer));
\endif
\if :do_check_start
  SELECT scm_notice_pending_start_reconciliation();
\endif
\if :do_check
  SELECT * FROM scm_notice_pending_reconcile(CAST(:'batch' AS integer));
\endif
\if :do_enable
  SELECT scm_notice_pending_set_use_summary(true);
\endif
\if :do_fallback
  SELECT scm_notice_pending_set_use_summary(false);
\endif

SELECT tenant_key,backfill_after_type,backfill_after_id,backfill_complete,
       reconcile_after_type,reconcile_after_id,reconcile_complete,reconcile_mismatches,
       ready,use_summary,update_time
FROM biz_doc_notice_pending_state WHERE tenant_key=scm_notice_pending_namespace();
COMMIT;
