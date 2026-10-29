# 发版流程（让所有客户机点一下"检查更新"就升级）

> 开源后升级源免配置：客户机默认盯 `github://ZhuangRenyang/KnowFab-SIRP`（GitHub Releases）。
> 你（维护者）要做的只是：**改版本号 → 构建签名包 → 打 tag → 把 zip 传到 Release**。

## 前提（一次性）

- 私钥在 `tools/keys/private.key`（**绝不能进 git**，.gitignore 已排除；丢了就升级不了，务必备份到密码管理器）
- GitHub CLI：`gh auth login`（或者网页手动传附件也行）

## 发版步骤

```bash
# 1. 改版本号（pom.xml 第 16 行左右的 <version>，去掉 -SNAPSHOT 语义直接改数字）
#    例如 1.0.0 → 1.1.0

# 2. 构建签名升级包（npm build + mvn package + PackUpdate 签名 + version.json）
bash tools/make-dist.sh --build
#    产物：dist/device-hub-1.1.0.zip（已用私钥签 manifest）
#          dist/version.json（version.json 模式镜像源也用它）

# 3. 提交 + 打 tag（tag 与包内版本必须一致，客户机按 tag 判断新版本）
git add -A && git commit -m "release: 1.1.0"
git tag v1.1.0 && git push origin main --tags

# 4. 发布 Release：标题随意，notes 写给用户看的更新说明（会显示在客户机的检查更新里）
gh release create v1.1.0 dist/device-hub-1.1.0.zip \
  --title "v1.1.0" \
  --notes "修复：xxx；新增：yyy"
```

## 客户机侧发生什么

1. 管理员打开「系统 → 检查更新」→ 系统请求 `api.github.com/repos/.../releases/latest`
2. 对比 tag 与本机版本 → 有新版本就显示版本号 + notes + 「下载并校验」按钮
3. 点击 → 下载 zip → **验签**（公钥内置，不是你签的包装不上）→ 登记待应用
4. 重启服务（网页一键或脚本）→ apply-pending.sh 换 jar → 新版本生效；出问题可「回滚」

## 验收清单（第一次发版时走一遍）

- [ ] Release 页能看到 `device-hub-x.y.z.zip` 附件
- [ ] 一台**没配置任何更新源**的客户机点「检查更新」→ 能看到新版本号与 notes
- [ ] 点击下载 → 提示"已下载并校验通过"（验签通过）
- [ ] 重启后版本号变化，数据完好（用户/设备/单据都在）
- [ ] 再点一次「检查更新」→ 提示"已是最新版本"

## 国内客户连不上 api.github.com？

三种办法（按推荐顺序）：

1. **自定义更新源**：系统设置 → 更新源 → 填一个能访问到的 `version.json` 地址
   （可放 Gitee Pages / 自己的 OSS / 客户内网文件服务器），格式见 `dist/version.json`；
   多个源逗号分隔按顺序试，GitHub 源仍可保留在后面兜底
2. **手工升级包**：「上传并校验」直接传 zip（U 盘拷进内网也行），验签机制不变
3. 反馈网络环境给维护者，看是否需要官方镜像

## 常见问题

- **忘改 pom 版本就发了 tag？** 客户机对比的是 tag 去掉 v 前缀，包内 manifest 也带版本，
  不一致会在升级后显示错乱——务必先改版本再构建
- **Release 里没有 zip 附件？** 客户机会提示"该版本没有升级包附件"，不会误升级
- **私钥丢了？** 老包还能升级已装机器，但新包签不出来；换钥需要给所有客户机换公钥配置，
  等于重新交付一遍——备份好私钥
