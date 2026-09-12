-- ============================================================================
-- V3__audit_actor_foreign_keys.sql
-- 审计可信度：所有“谁做的 / 谁拍板的”列必须指向真实存在的用户与月台，
-- 杜绝悬空 actor_id（此前这些列无外键，可写入不存在的用户 id，
-- 时间线只能降级显示“用户#id”，审计身份不可核实）。
-- 系统不提供用户/月台物理删除路径，默认 NO ACTION 即可阻止误删破坏留痕。
-- 可空列（如 created_by / resolved_by / dock_id）保持可空，外键只约束非空值。
-- ============================================================================

-- 全流程操作留痕：操作人必须是真实用户，涉及月台时必须指向真实月台
alter table operation_event
    add constraint fk_event_actor
        foreign key (actor_id) references app_user(id),
    add constraint fk_event_dock
        foreign key (dock_id) references dock(id);

-- 预约：提交人 / 插单拍板人
alter table appointment
    add constraint fk_appt_created_by
        foreign key (created_by) references app_user(id),
    add constraint fk_appt_overridden_by
        foreign key (overridden_by) references app_user(id);

-- 核费单：生成人
alter table fee_settlement
    add constraint fk_fee_generated_by
        foreign key (generated_by) references app_user(id);

-- 异议：提起人 / 处理人
alter table dispute
    add constraint fk_dispute_raised_by
        foreign key (raised_by) references app_user(id),
    add constraint fk_dispute_resolved_by
        foreign key (resolved_by) references app_user(id);

-- 费率：配置人
alter table carrier_daily_rate
    add constraint fk_rate_created_by
        foreign key (created_by) references app_user(id);
