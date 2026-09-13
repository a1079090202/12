-- ============================================================================
-- V3__dock_maintenance.sql
-- 月台保养停用 + 受影响预约处置。
-- 约定沿用 V1：业务记录不做物理删除，停用可逻辑取消（CANCELLED）；
-- 改期/待定处置只追加（appointment_reschedule），原时段在此固化快照，
-- appointment 主表的预约内容（车牌/订单/货类/预约码）永不被覆盖。
-- ============================================================================

-- 月台停用登记：某月台某天某时段因保养等原因不可用
create table dock_maintenance (
    id            bigserial primary key,
    dock_id       bigint       not null references dock(id),
    window_start  timestamptz  not null,                    -- 停用窗起（上海墙钟半小时整点对应的绝对时刻）
    window_end    timestamptz  not null,                    -- 停用窗止（半开区间上界）
    reason        varchar(500) not null,                    -- 停用原因（必填）
    status        varchar(20)  not null default 'SCHEDULED'
                  check (status in ('SCHEDULED','CANCELLED')),
    created_by    bigint       not null,
    created_at    timestamptz  not null default now(),
    cancelled_by  bigint,
    cancelled_at  timestamptz,
    cancel_reason varchar(500),
    constraint chk_maint_window check (window_end > window_start)
);
create index idx_maint_dock_window on dock_maintenance(dock_id, window_start, window_end);

-- 受影响预约的逐条处置记录（一次处置一行，只追加）：
--  action=PENDING     标记改约待定（原预约状态置 RESCHEDULE_PENDING，释放容量、禁止进场）
--  action=RESCHEDULED 改期到新时段（appointment.slot_start/end 更新，原时段在此留快照）
create table appointment_reschedule (
    id                   bigserial primary key,
    appointment_id       bigint       not null references appointment(id),
    maintenance_id       bigint       references dock_maintenance(id),
    action               varchar(20)  not null check (action in ('RESCHEDULED','PENDING')),
    original_slot_start  timestamptz  not null,
    original_slot_end    timestamptz  not null,
    original_dock_type   varchar(20)  not null,
    new_slot_start       timestamptz,                      -- PENDING 时为空
    new_slot_end         timestamptz,
    reason               varchar(500) not null,            -- 处置原因（必填，随操作人/时间一起留痕）
    acted_by             bigint       not null,
    acted_at             timestamptz  not null default now()
);
create index idx_resched_appt on appointment_reschedule(appointment_id);
