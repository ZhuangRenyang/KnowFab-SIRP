# KnowFab SIRP · AI-powered Smart Factory Management System

**English** · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Русский](README.ru.md)

[![License: AGPL-3.0](https://img.shields.io/github/license/ZhuangRenyang/KnowFab-SIRP)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/ZhuangRenyang/KnowFab-SIRP)](https://github.com/ZhuangRenyang/KnowFab-SIRP/releases)
[![Java 17](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)

**Let the machines report their own output — instead of asking workers to scan barcodes.**
Device-level data acquisition → finished-goods counting per line → automatic stock-in → purchasing /
inventory / sales / finance in one ledger → business reports → an AI assistant for the whole team.
It runs on **your own server**. Java 17 + Spring Boot 3 + Vue 3 + MySQL, shipped as a single jar —
or use the **self-contained portable build** that bundles Java and the database, so a bare machine
can start it with one command.

**Four traits, all grown on the shop floor** (not features invented at a desk):

- **One command, zero prerequisites.** Bundled Java 17 + MariaDB; a single line on Windows or Linux.
  No Docker, no pre-installed database, and the browser opens by itself. Everything lives in one
  directory — **copy it as a backup, delete it to uninstall**.
- **Old machines can count too.** Three wiring paths: Ethernet Modbus TCP, RS-485 pass-through, and
  relay + limit-switch pulse counting. A twenty-year-old press with no network port still gets one
  count per part.
- **Output becomes inventory automatically.** Every count posts into finished-goods stock — nobody
  scans anything. Purchasing / inventory / sales / finance share one ledger, so "the books don't
  match the warehouse" cannot happen by construction.
- **Upgrades never migrate data.** System page → *Check for updates* → download + signature check →
  swap the jar. The database stays untouched. Self-hosted, so data never leaves the plant.

---

## Install

Three options — **① is the recommended one**: nothing to install on the target machine, a single
command, roughly 3–5 minutes on first run (it downloads the JRE and the database; later starts are
instant). ② and ③ are for machines that already have a server or a database.

### ① Zero-dependency (recommended): one command, no Java / MySQL / Docker

**Windows** (10 or later):

```powershell
# China (Gitee, recommended)
powershell -ExecutionPolicy Bypass -Command "irm https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.ps1 | iex"

# Global / fallback (GitHub)
powershell -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.ps1 | iex"
```

**Linux / macOS (Intel)**:

```bash
# China (Gitee, recommended)
curl -fsSL https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.sh | bash

# Global / fallback (GitHub)
curl -fsSL https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.sh | bash
```

When it finishes, `http://localhost:8080/app/` opens automatically. **The initial admin password is
printed in the launcher window** and also written to `data/initial-admin.txt`.

The first run downloads **JRE 17 + MariaDB 11.4 (portable)** — about 130 MB from Chinese mirrors —
and every start after that takes seconds:

| | |
|---|---|
| Database | `127.0.0.1:3307` — dedicated port, **never touches an existing MySQL on 3306** |
| Where data lives | Inside the install directory (`runtime/data`) — **copy = backup, delete = uninstall** |
| Change the port | `start.bat -Port 9000` / `PORT=9000 ./start.sh` |
| Stop it | `stop.bat` / `./stop.sh` |

Prefer no command line? Grab `device-hub-<version>-portable.zip` from
[Gitee](https://gitee.com/ZhuangRenyang/KnowFab-SIRP/releases) or
[GitHub](https://github.com/ZhuangRenyang/KnowFab-SIRP/releases) → unzip → double-click `start.bat`
on Windows, or run `./start.sh` on Linux.

> **Fully offline shop-floor PC**: run it once on a connected machine (so it fetches `runtime/jre`
> and `runtime/mariadb`), then copy the whole folder over — no further network access needed.
> Already downloaded the zip? Point the installer at the local file:
> `KNOWFAB_PORTABLE_URL=D:\Downloads\device-hub-1.0.1-portable.zip` (`set` the same env var on Windows).
> First start ships with demo data (4 devices, energy and report curves), so you can click around
> without any real hardware.

> The installer looks for the package on **Gitee first, then GitHub** (identical packages). It also
> honours `KNOWFAB_DIR` — default `%LOCALAPPDATA%\KnowFabSIRP` on Windows, `~/KnowFabSIRP` on Linux.

### ② Docker Compose (Linux server, least effort)

```bash
git clone https://github.com/ZhuangRenyang/KnowFab-SIRP.git
cd KnowFab-SIRP
sudo bash deploy.sh        # in China: sudo bash deploy-cn.sh
```

The script generates a random database password, starts the containers, creates the schema, and
prints the access URL plus the initial password.

### ③ A machine that already has MySQL 8 and Java 17

```bash
sudo bash install.sh --db-pass 'your-mysql-password'
```

Installs to `/opt/device-hub` and registers a systemd service. Use `sudo PORT=9000 ./install.sh ...`
to change the port, or `--db pg` for PostgreSQL. You can also skip the script and run
`java -jar device-hub.jar` directly (database password via `DEVICEHUB_DB_PASSWORD`, or change it
later on the System page).

---

**Whichever path you take, the first thing to do afterwards**: open `http://<host>:8080/app/` and
**change the initial password** (top right).

## What problem it solves

Small and mid-sized factories trying to get output, inventory and energy under control usually hit
three walls:

1. **Legacy machines have no network port** — only relays and a buzzer; MES vendors say "replace the
   PLC first".
2. **Barcode reporting depends on discipline** — workers forget, or scan the wrong thing; the data is
   always half a shift late.
3. **Machine data and business data live in two worlds** — output on the floor, inventory in a
   spreadsheet, and the books never reconcile.

KnowFab SIRP's answer: **three integration paths cover machines of any vintage** (direct Ethernet,
RS-485 pass-through, relay + limit-switch counting). Every finished part posts into stock
automatically, purchasing–inventory–sales–finance share one ledger, and the data never leaves your
server room.

| A good fit if you… | Not for you if you… |
|---|---|
| run a mix of old and new machines and want automatic counting | need operation-level dispatching and piece-rate payroll (a heavyweight MES) |
| must self-host because the data is sensitive | want zero-install software (SaaS is simpler) |
| want machine data **and** ERP books from one system | only want a dashboard (this project is too much) |

### How the data flows

![KnowFab SIRP architecture](docs/screenshots/architecture.svg)

> Every arrow in that diagram is a runnable code path — simulators are included, so the whole loop
> works without any real hardware.

## See it working (no hardware required)

The portable and Docker builds **ship with demo data** (4 devices, energy curves, reports), so you
can click through immediately. For the full factory dataset (9 SKUs, 7 states each for purchase and
sales orders, 45 days of production history), clone the repo and run:

```bash
python tools/seed-demo.py    # seed a simulated factory
python tools/smoke-all.py    # 99 API smoke checks — all green means the install is healthy
```

| Production lines (devices by workstation) | Inventory (balances / alerts / movements) |
|---|---|
| ![lines](docs/screenshots/lines.png) | ![stock](docs/screenshots/stock.png) |
| **Finance (AR/AP, aging)** | **Business reports (stock movement, Excel export)** |
| ![finance](docs/screenshots/finance.png) | ![report](docs/screenshots/report.png) |
| **Machine output (day / month / year / all)** | **Stock documents (7 types, fully traceable)** |
| ![output](docs/screenshots/prod-month.png) | ![bills](docs/screenshots/stock-bills.png) |

## Feature map

- **Device connectivity** — Modbus TCP / RTU / pass-through; subnet + slave-id double scan for
  discovery; point tables as JSON; relay + limit-switch counting (the old-press lifesaver); a
  built-in simulator so you can develop without hardware
- **Production** — per-machine counters (today / this month / this year); **four output charts**
  (day / month / year / all; integer axes, and the sum of the parts equals the KPI); production-line
  layout with workstation order and a designated finished-goods counting point; per-device reset
  hour (two-shift friendly)
- **ERP** — purchasing (order → receipt → return, progress derived from documents) · inventory
  (balances / 7 document types / alerts / stocktake) · sales (order → shipment → return, hard stock
  check before shipment) · finance (AR/AP, receipts and payments, four aging buckets) — inventory
  has exactly one algorithm (sum of valid movements), so the books always match the warehouse
- **Reports** — business overview / inventory movement (opening + in − out = closing) /
  inbound-outbound statistics / purchasing and sales execution, all exportable to Excel; documents
  carry a **business date**, so month-end back-filling never leaks into the wrong month
- **Energy** — sub-metering, peak / flat / valley / super-peak tariffs, dual-track basic-charge vs.
  demand billing, 15-minute demand chart with a contract red line
- **AI** — a daily briefing at 07:00 (optionally pushed to WeCom), plus an **AI assistant for the
  whole team**: ask about inventory / output / devices / orders / finance in natural language,
  scoped by role (sales can only ask about stock), implemented as tool calls rather than generated
  SQL, works with DeepSeek / Qwen / Ollama, and can be switched off entirely
- **Roles** — four levels: admin / plant manager / team lead / **sales** (read-only stock plus
  available-to-promise, no cost fields)
- **Delivery** — **self-contained portable build (bundled JRE + MariaDB, one command, Windows and
  Linux)**, one-click install script, Docker Compose, built-in database migrations, backup script,
  upgrades that only replace the jar; **zero-config auto-update** watches Gitee Releases by default
  (direct in China, GitHub as fallback) and lets the System page download, verify and install a new
  version in one click

## How it differs

| | KnowFab SIRP | jshERP | SaaS MES (e.g. KuaiGongDan) |
|---|---|---|---|
| Machine data | **protocol-level direct + legacy counting** | none | mostly barcode reporting |
| Production ↔ inventory | counting posts stock automatically, documents close the loop | manual documents | work-order driven |
| Getting started | **one command, bundled Java + database** (no Docker/MySQL) | bring your own JDK + MySQL | sign up, data lives in the cloud |
| Deployment | self-hosted, one command, data stays inside the plant | deploy it yourself | cloud SaaS |
| License | AGPL-3.0 + commercial | AGPL-3.0 | closed source |

## Why this project actually understands equipment

The author didn't imagine a factory and then write code. He spent 4.5 years as an electrician in
factories (non-standard automation), visiting machine builders of many brands, and only then moved
into full-stack development (2.5 years):

- **The relay + limit-switch counting path** comes from an electrician's everyday knowledge: which
  wire carries an auxiliary contact, how to tap a buzzer signal. Those are "how do I wire it on
  site" questions, not "how do I parse the protocol" questions.
- **Per-device reset hours and two-shift semantics** come from real handover habits on the line.
- **Aging buckets, demand billing and the stock-movement identity** come from the very plain
  pressure that "if the books don't match, the plant argues".

That is why the feature list contains almost no invented features — they all grew on the shop floor.
Device integration and retrofit questions are welcome by email.

## License

The code is open source under [AGPL-3.0](LICENSE): running it in your own factory, learning from it,
or forking it and keeping the fork open is **free**. Closed-source commercial use, SaaS or white
labelling requires a [commercial license](COMMERCIAL-LICENSE.md).

**Contact** — device integration, retrofit design, deployment, commercial licensing:
996491835@qq.com (please read the [handbook](docs/HANDBOOK.md) first; most answers are already
there).

## Documentation

| Document | What is inside |
|---|---|
| [docs/README.en.md](docs/README.en.md) | English docs index: quickstart, deployment, device integration, API overview |
| [docs/HANDBOOK.md](docs/HANDBOOK.md) | Development and operations handbook *(Chinese)*: architecture, connecting real devices, adding protocols, deployment and upgrades, API list |
| [tools/portable/README-portable.txt](tools/portable/README-portable.txt) | Portable build guide *(Chinese)*: layout, ports, offline install, backup and migration |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Roadmap and full changelog *(Chinese; every entry carries measured results)* |
| [docs/COMPETITORS.md](docs/COMPETITORS.md) | Competitor comparison and talk tracks *(Chinese)* |
| [docs/DEMO.md](docs/DEMO.md) | Demo guide: seeding data and a recommended tour *(Chinese)* |
| [docs/RELEASE.md](docs/RELEASE.md) | Release process: tag → upload release assets → clients upgrade via *Check for updates* *(Chinese)* |

## Contributing

Issues and pull requests are welcome. Before changing code, run `python tools/smoke-all.py` and
`python tools/selftest.py` and keep them green — the project's testing discipline is "for every
feature: seed → smoke → contract regression".

---

**Translations**: [English](README.md) *(canonical)* · [简体中文](README.zh-CN.md) *(full)* · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Русский](README.ru.md) *(condensed)*.
Each condensed version covers the pitch, install commands and feature summary and links here
for details. Want another language? Open an issue or send a pull request.
