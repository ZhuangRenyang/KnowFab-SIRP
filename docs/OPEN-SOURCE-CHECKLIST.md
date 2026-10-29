# 开源检查清单（2026-10-29 执行记录与后续动作）

## 已完成 ✅

| 项 | 结果 |
|---|---|
| 密码值泄露扫描 | `DhLocal8471` 等真实凭据**未出现在工作区与全部 34 个提交的历史**中 |
| API Key 模式扫描（sk-/ghp_/xox/AIza） | 无 |
| 内网 IP | 仅 `10.0.0.5` 文档示例（install.sh 注释、脚本 --help），安全 |
| data/、日志、备份文件 | 从未被 git 跟踪 |
| LICENSE | AGPL-3.0 官方全文（gnu.org 原文） |
| 双轨授权说明 | `COMMERCIAL-LICENSE.md`（免费场景/需购买场景/FAQ） |
| README | 重写为对外版（原 1674 行手册移至 `docs/HANDBOOK.md`） |
| 过时口径 | 手册中"私有仓库 + Deploy Key"章节已替换为 AGPL 口径 |
| 截图 | `docs/screenshots/` 6 张（演示数据截图，无真实客户信息） |

## 开源（公开仓库）当天要做 ⚠️

1. **GitHub 仓库改 Public**：Settings → General → Danger Zone → Change visibility
   （改完先无痕窗口访问一遍，确认 clone 不需要凭据）
2. **确认仓库描述与 Topics**：建议 description：
   `设备直连采集+产线计数自动入库+进销存财务一体的私有化 AI 智慧工厂系统（Java/Spring Boot/Vue3）`
   topics：`mes` `erp` `modbus` `iot` `spring-boot` `vue3` `factory` `scada`
3. **About 页链接**：指向 README 或 DEMO.md

## 已决策 ✅

- **git 作者邮箱**（`996491835@qq.com`）：**保留公开**，兼作联系方式——README 与 COMMERCIAL-LICENSE 均已署名，无需改写历史。

## 日常维护约定

- 任何新提交前自查：`git grep -nE "(sk-[A-Za-z0-9]{8,}|ghp_|密码 *= *['\"][^'\"]{6,})"` 确认无凭据
- 默认密码 `123456` 只允许出现在 seed-demo/smoke 演示脚本与文档的"演示环境"语境里
- `docker-compose.yml` 的 `knowfab2026` 默认值仅用于一键体验，README/HANDBOOK 必须保留"生产必改"提示
- 新增配置项一律走环境变量占位符（`${XXX:}`），禁止写死真实值
