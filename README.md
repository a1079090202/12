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
docker compose up --build
```

- 应用：http://localhost:8080
- PostgreSQL：localhost:5432（库/用户/密码均为 `dockyard`）

首次启动 Flyway 自动建表，并写入初始化账号、6 个月台、费率和 **20 条样例预约**
（其中 1 条已出场并核费 60.00 元，可直接下载 CSV 对账 / 提异议）。

停止：

```bash
docker compose down          # 保留数据卷
docker compose down -v       # 连数据一起清空，下次启动重新播种
```

## 二、本地启动（不用 Docker）

前置：JDK 21、Maven 3.9+、一个可连通的 PostgreSQL 14+。

```bash
createdb dockyard            # 或在已有实例里建库
DB_URL=jdbc:postgresql://localhost:5432/dockyard \
DB_USER=dockyard DB_PASSWORD=dockyard \
mvn spring-boot:run
```

## 三、运行测试

测试使用 **Zonky 内嵌 PostgreSQL**，不需要本机安装 Docker 或 PG（首次运行会自动下载一个 PG 二进制，需要联网）。

```bash
mvn test
```

覆盖的关键场景（共 13 个用例）：

| 测试类 | 覆盖点 |
| --- | --- |
| `OverrideBookingTest` | 30 分钟槽容量拦截第 7 辆；无原因不许插单；写明原因插单成功并留痕 |
| `LateArrivalTest` | 早到进等候区；迟到 45 分钟边界（45 不标、46 标红）；同一预约码重复进场拒绝 |
| `ConcurrentDockTest` | 两个线程同时把两辆车派到同一月台，恰好一成一败，库里无双占 |
| `CrossRateDateFeeTest` | 计费等待跨上海自然日按当日费率分段（120 元/时 + 240 元/时）；核费后改价不影响快照；90 分钟内 0 元 |
| `DisputeHandlingTest` | 驳回维持原价；成立只改最终金额、原始金额保留；跨承运商提异议被拒；已处理不可覆盖 |
| `GoldenPathWalkthroughTest` | 完整 HTTP 走查：预约两车→放行→叫号→卸货→出场核费→异议，页面合计与 CSV 逐格一致 |
| `WebAuthorizationTest` | 跨角色直连 URL 返回 403（权限不靠藏按钮） |
| `PageSmokeTest` | 四个角色的全部页面真实渲染，捕获模板错误 |

## 四、初始化账号

初始密码统一为 **`dock1234`**（生产请通过修改 `DataInitializer` 或自行建号替换）。

| 用户名 | 角色 | 说明 |
| --- | --- | --- |
| `carrier1` | 承运商 | 顺达物流（标准货为主） |
| `carrier2` | 承运商 | 冷鲜运输（冷藏货） |
| `guard` | 门卫 | 凭预约码放行、标记早到/迟到 |
| `warehouse` | 仓库 | 靠台 / 开始卸货 / 完成 / 出场（自动核费） |
| `dispatcher` | 调度员 | 叫号派台、超额插单、费率维护、异议处理 |

月台：D1–D4 普通、D5 冷藏、D6 大件。
费率样例：顺达今日 60 元/时、次日 90 元/时；冷鲜今日 80 元/时、次日 100 元/时。

## 五、浏览器走查

见 **[`docs/WALKTHROUGH.md`](docs/WALKTHROUGH.md)**，按
「预约两辆车 → 门卫放行 → 调度叫号 → 仓库卸货 → 出场核费 → 承运商提异议」逐项核对，
每一步都写了预期页面数字与 CSV 对账方法。系统自带的样例出场单（车牌 `沪A00023`，60.00 元）可用于免操作快速对账。

## 六、业务规则口径

- **容量**：按上海墙钟切 30 分钟槽，每槽全园区 6 个名额（`app.slot.capacity` 可配）；
  插单不受容量限制，但原因必填，原因与操作人写入 `operation_event`。
- **进场**：只能凭有效预约码进场一次；早于时段起点标记「早到等候」；
  晚于时段终点 **45 分钟** 标记迟到（边界 45 分钟不算）。同车在场、同码重复使用都拒绝。
- **派台**：队列按实际进场先后；月台类型必须匹配；并发派台由
  `SELECT … FOR UPDATE` 串行化 + 数据库部分唯一索引 `uq_dock_occupancy` 双重保证「一个月台同时只有一辆车」。
- **等待时长**：`靠台时刻 − 进场时刻`（靠台时固化到预约单）。
- **核费**：免费 90 分钟；超出部分 = 起算点（进场+90 分）到靠台点；
  跨自然日按天分段，各段用承运商**当日费率**，费率与每段金额写进 `fee_segment` 快照；
  `fee_settlement.original_amount` 一旦生成永不更新。
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
