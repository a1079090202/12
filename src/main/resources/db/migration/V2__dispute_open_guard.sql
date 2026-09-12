-- ============================================================================
-- V2__dispute_open_guard.sql
-- 同一核费单（预约）同一时刻只允许存在一条 OPEN 异议：
-- 数据库层兜底并发双击 / 重复提交。服务层“先查 OPEN 再插入”的检查在
-- ReadCommitted 下存在 TOCTOU 竞态（两个事务都查不到 OPEN 后各自插入），
-- 仅靠应用层无法拦住，必须由数据库唯一约束保证。
-- 已处理（ADJUSTED / REJECTED）的历史异议不在索引内，仍可再次提起新异议。
-- ============================================================================
create unique index if not exists uq_dispute_open_per_appt
    on dispute(appointment_id)
    where status = 'OPEN';
