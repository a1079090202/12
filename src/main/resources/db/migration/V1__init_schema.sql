-- ============================================================================
-- V1__init_schema.sql
-- 园区月台预约调度系统 初始结构
-- 约定：
--  * 所有业务时间使用 timestamp with time zone 存储绝对时刻，
--    业务读写一律按 Asia/Shanghai 解释（见 YardTimeService）。
--  * 金额一律 numeric(12,2)，Java 侧仅使用 BigDecimal。
--  * 业务记录不做物理删除：仅允许状态流转 / active=false 逻辑失效。
-- ============================================================================

-- 预约码 / 业务流水
create sequence appt_code_seq;

-- 角色：CARRIER 承运商 / GUARD 门卫 / WAREHOUSE 仓库 / DISPATCHER 调度员
create table app_user (
    id              bigserial primary key,
    username        varchar(64)  not null unique,
    password_hash   varchar(100) not null,
    display_name    varchar(64)  not null,
    role            varchar(20)  not null check (role in ('CARRIER','GUARD','WAREHOUSE','DISPATCHER')),
    carrier_id      bigint,
    active          boolean      not null default true,
    created_at      timestamptz  not null default now()
);

create table carrier (
    id              bigserial primary key,
    code            varchar(32)  not null unique,
    name            varchar(128) not null,
    active          boolean      not null default true,
    created_at      timestamptz  not null default now()
);

alter table app_user add constraint fk_user_carrier
    foreign key (carrier_id) references carrier(id);

-- 承运商当日费率（按自然日 Asia/Shanghai 生效）；核费时另存快照，改价不影响历史单
create table carrier_daily_rate (
    id              bigserial primary key,
    carrier_id      bigint       not null references carrier(id),
    rate_date       date         not null,
    rate_per_hour   numeric(12,2) not null check (rate_per_hour >= 0),
    created_by      bigint,
    created_at      timestamptz  not null default now(),
    unique (carrier_id, rate_date)
);

-- 月台类型：STANDARD 普通 / COLD 冷藏 / OVERSIZE 大件
create table dock (
    id              bigserial primary key,
    code            varchar(16)  not null unique,
    name            varchar(64)  not null,
    dock_type       varchar(20)  not null check (dock_type in ('STANDARD','COLD','OVERSIZE')),
    active          boolean      not null default true
);

-- 预约主表 / 车辆在场状态全部在这一张表上随流程推进
-- BOOKED 已预约(超额被拦截前的正常单) / OVERRIDDEN 插单 / GATED_IN 已进场等候 /
-- CALLED 已叫号派台 / DOCKED 已靠台 / UNLOADING 卸货中 / COMPLETED 卸货完成 /
-- EXITED 已出场 / CANCELLED 已取消 / NO_SHOW 失约
create table appointment (
    id                   bigserial primary key,
    code                 varchar(24)   not null unique,            -- 预约码
    carrier_id           bigint        not null references carrier(id),
    order_no             varchar(64)   not null,
    plate_no             varchar(16)   not null,
    driver_name          varchar(64)   not null,
    driver_phone         varchar(32),
    cargo_type           varchar(64)   not null,
    dock_type            varchar(20)   not null check (dock_type in ('STANDARD','COLD','OVERSIZE')),
    slot_start           timestamptz   not null,                   -- 预计到达时段起
    slot_end             timestamptz   not null,                   -- 预计到达时段止
    status               varchar(20)   not null default 'BOOKED',
    -- 插单
    override_reason      varchar(500),
    overridden_by        bigint,
    -- 门卫
    arrived_at           timestamptz,                             -- 实际进场时间
    early_arrival        boolean      not null default false,     -- 早到进入等候区
    late_flag            boolean      not null default false,     -- 迟到超过 45 分钟
    late_minutes         integer      not null default 0,
    -- 调度派台 / 仓库作业
    called_at            timestamptz,
    assigned_dock_id     bigint references dock(id),
    docked_at            timestamptz,
    unload_start_at      timestamptz,
    completed_at         timestamptz,
    exited_at            timestamptz,
    -- 等待分钟数 = 靠台 - 进场（核费时固化，保留现场口径）
    wait_minutes         integer,
    created_by           bigint,
    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now(),
    constraint chk_slot check (slot_end > slot_start)
);

create index idx_appt_slot_start on appointment(slot_start);
create index idx_appt_status on appointment(status);
create index idx_appt_carrier on appointment(carrier_id);
create index idx_appt_plate on appointment(plate_no);

-- 同一车辆同一时间只允许有一张“在场中”的预约单（同时拦住重复进场、重复靠台）
create unique index uq_appt_plate_in_yard on appointment(plate_no)
    where status in ('GATED_IN','CALLED','DOCKED','UNLOADING','COMPLETED');

-- 同一月台同一时间只允许一辆车占用：并发派车时数据库层兜底，只赢一单
create unique index uq_dock_occupancy on appointment(assigned_dock_id)
    where assigned_dock_id is not null
      and status in ('CALLED','DOCKED','UNLOADING','COMPLETED');

-- 全流程操作留痕（只追加）
create table operation_event (
    id              bigserial primary key,
    appointment_id  bigint       not null references appointment(id),
    event_type      varchar(40)  not null,
    actor_id        bigint       not null,
    occurred_at     timestamptz  not null default now(),
    dock_id         bigint,
    detail          varchar(1000)
);
create index idx_event_appt on operation_event(appointment_id, occurred_at);

-- 核费单（出场时生成；金额与费率快照永不被 UPDATE 覆盖）
create table fee_settlement (
    id                   bigserial primary key,
    appointment_id       bigint       not null unique references appointment(id),
    carrier_id           bigint       not null references carrier(id),
    plate_no             varchar(16)  not null,
    free_minutes         integer      not null,
    wait_minutes         integer      not null,
    chargeable_minutes   integer      not null,
    -- 核费当时的费率快照（主费率，等于首段费率，便于对账）
    rate_snapshot        numeric(12,2) not null,
    rate_date            date         not null,
    -- original_amount：系统原始核算金额，任何异议处理都不得修改
    original_amount      numeric(12,2) not null,
    -- final_amount：当前生效金额；维持=原值，调整=新值
    final_amount         numeric(12,2) not null,
    status               varchar(20)  not null default 'CONFIRMED'
        check (status in ('CONFIRMED','DISPUTED','UPHELD','ADJUSTED')),
    generated_by         bigint,
    generated_at         timestamptz  not null default now()
);

-- 跨费率日期时，按上海自然日分段计费，每段固化当天费率
create table fee_segment (
    id              bigserial primary key,
    settlement_id   bigint       not null references fee_settlement(id),
    segment_date    date         not null,
    minutes         integer      not null,
    rate_per_hour   numeric(12,2) not null,   -- 费率快照
    amount          numeric(12,2) not null
);
create index idx_fee_seg_settlement on fee_segment(settlement_id);

-- 异议（只追加；处理只更新本行处理结论，原金额保存在 fee_settlement.original_amount）
create table dispute (
    id                   bigserial primary key,
    appointment_id       bigint       not null references appointment(id),
    carrier_id           bigint       not null references carrier(id),
    reason               varchar(1000) not null,
    status               varchar(20)  not null default 'OPEN'
        check (status in ('OPEN','ADJUSTED','REJECTED')),
    original_amount      numeric(12,2) not null,   -- 提起异议瞬间的金额快照
    adjusted_amount      numeric(12,2),            -- 调度调整后的金额（UPHELD/REJECTED 为空）
    resolution_note      varchar(1000),
    raised_by            bigint       not null,
    raised_at            timestamptz  not null default now(),
    resolved_by          bigint,
    resolved_at          timestamptz
);
create index idx_dispute_appt on dispute(appointment_id);
