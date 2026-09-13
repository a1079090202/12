# 园区月台预约调度系统（dockyard）

覆盖 **预约 → 进场 → 排队叫号 → 靠台卸货 → 出场核费 → 异议处理** 全流程的浏览器系统，
解决「纸排队、仓库不知道装什么、滞留费三方扯皮」的问题：每一步都有操作人、时间和状态，
等待时长与费用按统一规则自动核算，原始金额与费率快照永久保留。

- 技术栈：Java 21 · Spring Boot 3.3 · Spring Security · Spring Data JPA · PostgreSQL 16 · Flyway · Thymeleaf（少量原生 JavaScript，无 React/Vue）
- 金额：全程 `BigDecimal`，数据库 `numeric(12,2)`
- 业务时间：存储 `timestamptz`，所有业务口径一律按 **Asia/Shanghai**
- 历史记录：只追加 / 状态流转，**没有任何物理删除路径**（见 `V1__init_schema.sql` 的部分唯一索引与服务层约定）

---

## 一、快速启动（Docker Compose，推荐）

前置：已安装 Docker 与 Docker Compose。

```bash
cp .env.example .env          # 然后编辑 .env，把 DB_PASSWORD 改成强口令
# 本地体验样例数据可在 .env 中设 SEED_SAMPLE=true
docker compose up --build
```

- 应用：http://localhost:8080
- **PostgreSQL 不映射宿主端口**，只在 compose 内部网络可达；没有默认数据库口令，
  `DB_PASSWORD` 未设置时容器会直接拒绝启动。

首次启动 Flyway 自动建表并写入初始化账号、6 个月台、费率；
仅当 `SEED_SAMPLE=true` 时才额外写入 **20 条样例预约**（生产默认关闭）。

初始账号密码由 `INIT_PASSWORD` 指定；**未指定时首次启动会随机生成并在应用日志中只打印一次**。

停止：

```bash
docker compose down          # 保留数据卷
docker compose down -v       # 连数据一起清空，下次启动重新播种
```

### 生产部署清单

- `.env` 中设置强随机 `DB_PASSWORD`、专属 `INIT_PASSWORD`，首次登录后立即改密；
- 置于 HTTPS 反向代理后，并在 `.env` 设 `COOKIE_SECURE=true`；
- 保持 `SEED_SAMPLE=false`，不要把数据库端口暴露到公网；
- 容器默认以非 root 用户运行。

## 二、本地启动（不用 Docker）

前置：JDK 21、Maven 3.9+、一个可连通的 PostgreSQL 14+。

```bash
createdb dockyard            # 或在已有实例里建库
DB_URL=jdbc:postgresql://localhost:5432/dockyard \
DB_USER=dockyard DB_PASSWORD='你的强口令' \
SEED_SAMPLE=true \
mvn spring-boot:run
```

注意：`DB_PASSWORD` 没有默认值，不设置会拒绝启动（避免弱口令上线）。

## 三、运行测试

测试使用 **Zonky 内嵌 PostgreSQL**，不需要本机安装 Docker 或 PG（首次运行会自动下载一个 PG 二进制，需要联网）。

```bash
mvn test
```

覆盖的关键场景（共 24 个用例）：

| 测试类 | 覆盖点 |
| --- | --- |
| `OverrideBookingTest` | 30 分钟槽容量拦截第 7 辆；无原因不许插单；写明原因插单成功并留痕 |
| `LateArrivalTest` | 早到进等候区；迟到 45 分钟边界（45 不标、46 标红）；同一预约码重复进场拒绝 |
| `ConcurrentDockTest` | 两个线程同时把两辆车派到同一月台，恰好一成一败，库里无双占 |
| `CoreFlowConcurrencyTest` | 同槽 12 线程并发预约不超卖；同码/同车并发进场只成功一次；进场与取消互斥；异议不重复提起、结论不被并发覆盖 |
| `CodeGenerationTest` | 预约码批量生成不重号、格式统一（YY+yyMMdd+≥6 位序列）；序列跨过 10000 不与同日旧码撞号 |
| `AuditTrailTest` | 取消记 CANCELLED 而非 BOOKED；异议提起/驳回/调整各有专属事件，不再伪装成出场核费 |
| `CrossRateDateFeeTest` | 计费等待跨上海自然日按当日费率分段（120 元/时 + 240 元/时）；核费后改价不影响快照；90 分钟内 0 元 |
| `DisputeHandlingTest` | 驳回维持原价；成立只改最终金额、原始金额保留；跨承运商提异议被拒；已处理不可覆盖 |
| `GoldenPathWalkthroughTest` | 完整 HTTP 走查：预约两车→放行→叫号→卸货→出场核费→异议，页面合计与 CSV 逐格一致 |
| `WebAuthorizationTest` | 跨角色直连 URL 返回 403（权限不靠藏按钮） |
| `PageSmokeTest` | 四个角色的全部页面真实渲染，捕获模板错误 |

> 测试基类 `AbstractIntegrationTest` 显式钉住用例依赖的规则参数（槽长 30 分钟 /
> 免费 90 分钟 / 迟到宽限 45 分钟）：`application.yml` 默认值日后调整不会悄悄改变测试口径。
> 槽位容量不是配置项，由“当时可用（未停用）月台数”动态推导（普通 4 / 冷藏 1 / 大件 1）。

## 四、初始化账号

初始密码**不再写死**：部署时用环境变量 `INIT_PASSWORD` 指定（5 个账号共用，登录后请立即修改）；
未指定则首次启动随机生成，在应用日志中只打印一次。登录页不展示任何账号名/密码提示。

| 用户名 | 角色 | 说明 |
| --- | --- | --- |
| `carrier1` | 承运商 | 顺达物流（标准货为主） |
| `carrier2` | 承运商 | 冷鲜运输（冷藏货） |
| `guard` | 门卫 | 凭预约码放行、标记早到/迟到 |
| `warehouse` | 仓库 | 靠台 / 开始卸货 / 完成 / 出场（自动核费） |
| `dispatcher` | 调度员 | 叫号派台、超额插单、费率维护、异议处理、月台保养停用 |

月台：D1–D4 普通、D5 冷藏、D6 大件。
费率样例：顺达今日 60 元/时、次日 90 元/时（种子仅覆盖今/明两日，其余日期由调度员在「费率配置」页维护）；
冷鲜今日 80 元/时、次日 100 元/时。**当日费率必须显式配置：计费区间覆盖到的任何一天缺费率，出场核费会被整体拒绝**（出场事务回滚，车不出场、不产生核费单），补齐费率后重新办理出场即可，系统不设静默兜底单价。

## 五、浏览器走查

见 **[`docs/WALKTHROUGH.md`](docs/WALKTHROUGH.md)**，按
「预约两辆车 → 门卫放行 → 调度叫号 → 仓库卸货 → 出场核费 → 承运商提异议」逐项核对，
每一步都写了预期页面数字与 CSV 对账方法。系统自带的样例出场单（车牌 `沪A00023`，60.00 元）可用于免操作快速对账。

## 六、业务规则口径

- **容量**：按上海墙钟切 30 分钟槽（`app.slot.length-minutes` 可配），某类型某槽容量 =
  当时**未停用的该类型活动月台数**（普通 D1–D4=4 / 冷藏 D5=1 / 大件 D6=1）；
  并发下单/保养改期由事务级咨询锁按槽串行化，容量不超卖；
  插单可超容量但原因必填（该类型容量为 0 时连插单也拒），原因与操作人写入 `operation_event`。
- **月台保养停用**：调度员在「月台保养」登记某月台某天某时段（半小时整点）+ 原因；
  停用窗内该类型容量自动扣减，窗内同类型未进场预约列入受影响清单，可逐条**改期**
  （槽位咨询锁内复查目标槽容量）或标记**改约待定**（释放容量、门卫拒入）。
  处置只追加到 `appointment_reschedule`（原时段快照、原因、操作人、时间），预约内容不被覆盖；
  停用可逻辑取消，容量即时恢复；看板对保养中的月台显示红色 🔧 标识，派台自动跳过停用月台。
- **预约码**：`YY + 槽位上海日 yyMMdd + 数据库序列（至少 6 位，不截断）`，
  由全站唯一的 `AppointmentCodeGenerator` 生成（运行时预约与启动播种共用同一实现）。
- **进场**：只能凭有效预约码进场一次；早于时段起点标记「早到等候」；
  晚于时段终点 **45 分钟** 标记迟到（边界 45 分钟不算；宽限 `app.gate.late-tolerance-minutes` 可配）。
  同车在场、同码重复使用都拒绝。
- **派台**：队列按实际进场先后；月台类型必须匹配；并发派台由
  `SELECT … FOR UPDATE` 串行化 + 数据库部分唯一索引 `uq_dock_occupancy` 双重保证「一个月台同时只有一辆车」。
- **等待时长**：`靠台时刻 − 进场时刻`（秒级计算，展示分钟向下取整，靠台时固化到预约单）。
- **核费**：免费 90 分钟（`app.fee.free-minutes` 可配）；超出部分 = 起算点（进场+免费时长）到靠台点，
  计费总分钟向上取整（超出免费时长不足 1 分钟也计 1 分钟）；
  跨自然日按天先按秒切分，再用最大余数法把整分钟分配到各天，各段用承运商**当日费率**，
  费率与每段分钟/金额写进 `fee_segment` 快照（`rate_snapshot` 只是首个计费段费率，完整单价看分段）；
  计费区间内某日缺费率则拒绝核费，不套用默认价；
  `fee_settlement.original_amount` 一旦生成永不更新；“今日核费”按出场（核费单生成）时刻归属自然日。
- **异议**：承运商发起（只能对本司单）；调度驳回=维持原价；成立=只写 `final_amount`，
  原价、费率快照、分段明细全部保留；异议只追加、已处理不可覆盖。
- **防重**：`uq_appt_plate_in_yard`（同车在场唯一）与 `uq_dock_occupancy`（同月台占用唯一）
  是数据库级约束，应用层绕不过去。

## 七、主要代码结构

```
src/main/java/com/example/dockyard/
├── domain/          JPA 实体与枚举（状态机 AppointmentStatus 等）
├── repo/            Spring Data 仓储（含加锁查询、占用/在场检测）
├── security/        Spring Security：URL + @PreAuthorize 双层鉴权
├── service/
│   ├── AppointmentService.java       容量校验 + 插单
│   ├── AppointmentCodeGenerator.java 预约码全站唯一生成器（运行时/播种共用）
│   ├── GateService.java              进场 / 早到 / 迟到
│   ├── QueueAllocationService.java   排队、叫号派台（并发安全）
│   ├── WarehouseOperationService.java 靠台/卸货/完成/出场
│   ├── FeeService.java               90 分钟免费 + 跨日分段 + 费率快照
│   ├── DisputeService.java           异议（原金额不可抹）
│   ├── YardClock.java                全站唯一 Asia/Shanghai 时钟（测试可固定）
│   └── DashboardService.java         首页聚合
├── web/             控制器（只收参数、调服务、渲染）+ CSV 下载
└── bootstrap/       DataInitializer：账号 / 月台 / 费率 / 20 条样例
src/main/resources/
├── db/migration/V1__init_schema.sql  Flyway 建表
├── templates/                        Thymeleaf 页面
└── static/                           一个 CSS + 两处少量原生 JS
```
