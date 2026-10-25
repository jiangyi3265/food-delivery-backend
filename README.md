# 食材配送后端 · food-delivery-backend

为门店、供应商和平台提供统一食材配送 API、数据隔离及线下结算能力。

## 项目简介

基于若依多模块工程扩展食材业务。业务接口位于 /food，包含账号登录、商品与价格管理、购物车、按门店供应商关系自动分单、逐项备货、配送与收货、异常处理、反馈、Excel 导出及线下结算登记。

门店只能访问本店数据；供应商按订单归属访问且不接收销售价与结算字段。更换门店供应商只影响新订单。订单使用价格、地址快照；下单及结算具有幂等和事务控制。

## 技术栈

- Java，建议 JDK 17（Maven 编译目标为 Java 8）与 Maven 3.9。
- Spring Boot 2.5.15、Spring Security、Spring JDBC / JdbcTemplate。
- MySQL、Redis、Druid；若依基础模块使用 MyBatis 与 PageHelper，并非 MyBatis Plus。
- Apache POI 导出 Excel、BCrypt 密码散列、随机令牌会话。

## 关联仓库

| 项目 | 说明 | GitHub |
| --- | --- | --- |
| food-delivery-backend | 后端 API 与业务数据 | [food-delivery-backend](https://github.com/jiangyi3265/food-delivery-backend) |
| food-delivery-admin | 平台 Web 管理入口 | [food-delivery-admin](https://github.com/jiangyi3265/food-delivery-admin) |
| food-delivery-app | 门店、供应商及平台业务应用 | [food-delivery-app](https://github.com/jiangyi3265/food-delivery-app) |

## 快速启动

安装 JDK 17、Maven、MySQL、Redis、MySQL 命令行工具和 PowerShell 7，并确保进入 PATH。

```powershell
Copy-Item config/local.example.json config/local.json
# 编辑 config/local.json：填写自己的数据库密码、FOOD_SEED_PASSWORD（8～72字符）及 RUOYI_TOKEN_SECRET。
# 可以用 [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N') 生成本地令牌密钥。
pwsh -File scripts/start-backend.ps1
```

脚本加载本地配置、创建独立的 food_delivery41 数据库、导入若依基础表、构建并启动后端。业务表与示例数据在首次启动创建，已有数据保留。配置文件已被 Git 忽略；不要提交密码。环境变量优先于本地配置。MySQL 用户须有创建数据库权限，或先创建符合命名规则的空库。Redis 默认使用数据库 14。

健康检查：http://127.0.0.1:8086/food/health 。直接运行 JAR 时，须自行设置 FOOD_DB_*、FOOD_SEED_PASSWORD、RUOYI_TOKEN_SECRET 等环境变量，并完成基础 SQL 导入：

```bash
mvn package -DskipTests
java -Dfile.encoding=UTF-8 -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=druid,food
```

业务示例账号为 admin、store_a、store_b、store_c、supplier_a、supplier_b，初始密码取自本地 FOOD_SEED_PASSWORD。修改此配置不会重置已有账号。框架 sys_user 演示账号不预置可登录密码，与食材业务账号不同。

## 项目结构

- ruoyi-admin/src/main/java/com/ruoyi/web/controller/food/：业务 API、鉴权、事务及状态流转。
- ruoyi-admin/src/main/resources/food-schema.sql：食材业务表。
- ruoyi-admin/src/main/resources/food-assets/：食材照片资源。
- ruoyi-framework、ruoyi-system、ruoyi-common：若依框架、系统及公共模块。
- ruoyi-quartz、ruoyi-generator：任务调度和代码生成基础模块。
- sql/：基础表初始化脚本；scripts/、config/：本地启动和配置示例。

## 开发与测试

在独立终端设置 FOOD_DB_NAME=food_delivery41_test、FOOD_PORT=8087，再运行启动脚本。集成测试保存在 food-delivery-app/tests，使用同一测试密码；运行说明见该仓库。停止旧进程后再重新打包，避免 Windows 正在运行的 JAR 被占用。

当前验证范围及未完成事项见 [测试交接状态](docs/TEST_STATUS.md)。对账的供应商视图是门店销售订单分组，不是供应商采购款结算。

## 简历描述示例

参与食材供应链后端开发，实现门店定向分单、订单履约状态流转和角色数据隔离；通过事务、幂等控制与订单快照支撑三端共享数据及线下对账结算。

## 开源说明

基于 RuoYi-Vue，保留原有 MIT LICENSE 及版权声明。
