# 发版流程（一条命令，GitHub + Gitee 双站发布）

> 客户机「检查更新」默认读 **Gitee Releases**（主源），失败自动兜底 GitHub。
> 所以每次发版两个站点都要有同一个版本，`tools/release.sh` 一次搞定。

## 先说一个坑：Gitee 的“仓库镜像”不同步 Release

Gitee 镜像只同步**代码**（分支、tag），**不会同步 Releases 和附件**。
所以“在 GitHub 发版，Gitee 自动就有了”这个预期靠镜像是实现不了的——
必须发版时调用 Gitee API 建发行版并上传附件（本仓库的 `tools/gitee-release.sh` 就是干这个的）。
本项目提供两种自动化：

| 方式 | 触发 | 适合 |
|------|------|------|
| 本地一键 | `bash tools/release.sh 1.0.2 "说明"` | 自己电脑发版（推荐，构建在本机，快） |
| GitHub Actions | `git push origin v1.0.2` | 不想占本机、多人协作 |

## 前提（一次性）

- 签名私钥 `tools/keys/private.key`（**绝不进 git**，丢了所有客户机都升不了级，务必备份）
- GitHub CLI：`gh auth login`（没装也能发，脚本会打印手动命令）
- Gitee 令牌：Gitee → 设置 → **私人令牌** → 生成（勾 `projects`），发版时 `GITEE_TOKEN=xxx`
- Actions 方式还需要在 GitHub 仓库 Secrets 配：
  - `UPDATE_PRIVATE_KEY`：私钥文件完整内容（PEM）
  - `GITEE_TOKEN`：上面那个令牌

## 发版（本地一键）

```bash
GITEE_TOKEN=你的令牌 bash tools/release.sh 1.0.2 "设备模板支持 485 串口预设；修复探测拆包"
```

它会依次做：

1. 改 `pom.xml` 版本号
2. `tools/make-dist.sh --build`（前端构建 + 后端打包 + 安装包 + 审计）
3. `PackUpdate` 生成**签名升级包**
4. 更新 `dist/version.json`（指向签名升级包）
5. `git commit` + 打 tag `v1.0.2` + push
6. `gh release create` 发 GitHub Release（两个附件）
7. `tools/gitee-release.sh` 发 Gitee Release（同样的两个附件）

产物命名（**不要改**）：

| 文件 | 用途 |
|------|------|
| `device-hub-<版本>-update.zip` | 签名升级包，检查更新只认带 `update` 的附件 |
| `device-hub-<版本>-installer.zip` | 新客户安装包（install.sh/README/SHA256SUMS） |

## 发版（GitHub Actions）

```bash
git tag v1.0.2 && git push origin v1.0.2
```

`.github/workflows/release.yml` 会：校验 tag 与 pom 版本一致 → 构建 → 签名 → 发 GitHub Release
→ 调 `tools/gitee-release.sh` 同步 Gitee（没配 `GITEE_TOKEN` 会自动跳过 Gitee 那步）。

## 客户机侧发生什么

1. 管理员点「系统 → 检查更新」→ 请求 `https://gitee.com/api/v5/repos/ZhuangRenyang/KnowFab-SIRP/releases/latest`
2. 对比 tag 与本机版本 → 有新版本显示版本号 + notes + 「下载并校验」
3. 下载 `*-update.zip` → **验签**（公钥内置，不是你签的包装不上）→ 登记待应用
4. 重启服务 → 换 jar → 新版本生效；出问题可「回滚」
5. Gitee 不可达时自动试 GitHub；两者都不行就用「上传并校验」离线升级

## 验收清单（每次发版走一遍）

- [ ] Gitee 发行版页面能看到 tag 和两个附件
- [ ] GitHub Release 页面同样有两个附件
- [ ] 一台客户机点「检查更新」→ 看到新版本号与 notes
- [ ] 点下载 → 提示“已下载并校验通过”
- [ ] 重启后版本号变化，业务数据完好
- [ ] 再点一次 → “已是最新版本”

## 常见问题

- **Release 里放错附件？** 客户机优先取名字带 `update` 的 zip；没有才退回 `device-hub-*.zip`。
  把安装包误放成唯一附件会验签失败，脚本已用命名区分，别手工改名。
- **忘改 pom 版本就打了 tag？** Actions 会直接失败；本地脚本会自动改版本，不用担心。
- **同一个版本重发？** tag 已存在时脚本会拒绝。只想补传附件用
  `gh release upload v1.0.2 dist/xxx.zip --clobber`，Gitee 用 `tools/gitee-release.sh`（会复用已有发行版）。
- **私钥丢了？** 新包签不出来，换钥等于给所有客户机重发公钥，成本极高——备份到密码管理器。
