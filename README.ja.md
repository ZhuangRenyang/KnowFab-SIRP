# KnowFab SIRP · AI スマート工場管理システム

[English](README.md) · [简体中文](README.zh-CN.md) · **日本語** · [한국어](README.ko.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Русский](README.ru.md)

[![License: AGPL-3.0](https://img.shields.io/github/license/ZhuangRenyang/KnowFab-SIRP)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/ZhuangRenyang/KnowFab-SIRP)](https://github.com/ZhuangRenyang/KnowFab-SIRP/releases)

**「作業者がバーコードを読む」のではなく、「機械が自分で実績を報告する」。**
設備から直接データを収集 → ラインごとに完成品をカウント → 完成品倉庫へ自動入庫 →
購買・在庫・販売・財務を一元管理 → 経営レポート → 全員向け AI アシスタント。
**自社サーバー**で動きます。Java 17 + Spring Boot 3 + Vue 3 + MySQL、単一 jar で納品。
Java とデータベースを同梱した**ポータブル版**なら、何も入っていない PC でも 1 コマンドで起動できます。

## 4 つの特徴（すべて現場から出てきたもの）

- **1 コマンドで導入、事前インストール不要** — Java 17 + MariaDB を同梱。Windows / Linux それぞれ 1 行。
  Docker 不要、データベースの事前インストールも不要で、起動後にブラウザが自動で開きます。
  データは 1 つのフォルダに集約 — **コピーすればバックアップ、削除すればアンインストール**。
- **古い設備でもカウントできる** — Ethernet Modbus TCP、RS-485 透過、リレー＋リミットスイッチの 3 方式。
  ネットワークポートのない 20 年前のプレス機でも、1 個ごとに自動でカウントします。
- **生産数がそのまま在庫になる** — カウントが完成品在庫に自動計上され、作業者のスキャンに依存しません。
  購買・在庫・販売・財務が同じ台帳を共有するため、「帳簿と現物が合わない」が構造的に起きません。
- **アップグレードでデータ移行なし** — システム画面の「更新を確認」→ ダウンロード＋署名検証 → jar を差し替えるだけ。
  データベースはそのまま。オンプレミスなのでデータは工場の外に出ません。

## インストール（1 コマンド）

**Windows**（10 以降）:

```powershell
# 中国本土（Gitee、推奨）
powershell -ExecutionPolicy Bypass -Command "irm https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.ps1 | iex"

# その他の地域 / 予備（GitHub）
powershell -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.ps1 | iex"
```

**Linux / macOS（Intel）**:

```bash
# 中国本土（Gitee、推奨）
curl -fsSL https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.sh | bash

# その他の地域 / 予備（GitHub）
curl -fsSL https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.sh | bash
```

初回起動時に **JRE 17 と MariaDB 11.4（約 130MB）** を自動ダウンロードし、2 回目以降は数秒で起動します。
データベースは `127.0.0.1:3307` を使用するため、既存の MySQL（3306）と競合しません。
初期管理者パスワードは起動ウィンドウに表示され、`data/initial-admin.txt` にも保存されます。

その他の導入方法:

- **Docker Compose**（Linux サーバー）: `git clone` して `sudo bash deploy.sh`（中国国内は `deploy-cn.sh`）
- **既存の MySQL 8 + Java 17 がある場合**: `sudo bash install.sh --db-pass 'あなたのMySQLパスワード'`

## 主な機能

- **設備接続** — Modbus TCP / RTU / 透過。サブネット＋スレーブ ID の二重スキャンで機器を検出。
  点表は JSON、リレー＋リミットスイッチのカウントにも対応。シミュレータ同梱で実機なしでも開発可能
- **生産** — 設備別カウント（今日 / 今月 / 今年）、日・月・年・全期間の 4 グラフ、
  ライン構成（工程順と完成品カウント点の指定）、設備ごとのリセット時刻（二交代制に対応）
- **ERP** — 購買（注文 → 入荷 → 返品）· 在庫（残高 / 7 種の伝票 / アラート / 棚卸）·
  販売（注文 → 出荷 → 返品、出荷前に在庫を厳格チェック）· 財務（売掛・買掛、入出金、4 区分のエイジング）
- **レポート** — 経営概況 / 在庫受払（期首 ＋ 入 − 出 ＝ 期末）/ 入出庫統計 / 購買・販売の執行状況。
  すべて Excel に出力可能。伝票には**業務日付**を持たせているため、月末の後入れ入力でも月がずれません
- **エネルギー** — 子メーター計量、峰・平・谷・尖峰の料金体系、基本料金とデマンドの二方式、
  15 分デマンドグラフ（契約値を超えたら赤線）
- **AI** — 毎朝 7:00 のサマリー（WeCom へ推送可）と、**全員向け AI アシスタント**。
  在庫 / 生産 / 設備 / 注文 / 財務を自然言語で照会でき、ロールで範囲を制限（営業は在庫のみ）。
  SQL 生成ではなくツール呼び出し方式。DeepSeek / Qwen / Ollama に対応し、機能ごと停止も可能
- **権限** — 管理者 / 工場長 / 班長 / **営業**（在庫の参照と引当可能数のみ、原価項目なし）の 4 階層

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [README.md](README.md) | 英語版 README（正式版・最も詳しい） |
| [README.zh-CN.md](README.zh-CN.md) | 中国語版 README |
| [docs/README.en.md](docs/README.en.md) | 英語ドキュメント索引：導入手順、設備接続、点表の項目、API 概要 |

> このページは**要約版**です。詳細・トラブルシューティング・API 一覧は英語版または中国語版をご覧ください。
> 中国語の [HANDBOOK](docs/HANDBOOK.md)（1600 行超）は現在中国語のみです。翻訳のリクエストは Issue でどうぞ。

## ライセンス

[AGPL-3.0](LICENSE) で公開しています。自社工場での利用、学習、フォークして公開する場合は**無償**。
クローズドソースでの商用利用 / SaaS / ホワイトラベルは[商業ライセンス](COMMERCIAL-LICENSE.md)が必要です。

**連絡先** — 設備接続・改造のご相談、導入支援、商業ライセンス: 996491835@qq.com

**コミュニティ (中文交流群)** — 設備接続・点表設定・導入の答疑は QQ 群で: **知造(KnowFab-SIRP) · 1132864448**
