# KnowFab SIRP — Documentation (English)

[English](README.en.md) · [简体中文](../README.zh-CN.md) · [Back to main README](../README.md)

This page is the English entry point into the documentation. It covers the things you need in the
first hour: installing, connecting a machine, and where the API lives.

> **Language status** — the main README exists in English and Chinese. The deep handbook
> [`HANDBOOK.md`](HANDBOOK.md) (1600+ lines: architecture rationale, protocol internals, energy
> billing, shift reports, upgrade mechanics) is currently **Chinese only**. The sections below
> summarise the operational parts; if you need a specific chapter translated, open an issue and
> describe your use case.

---

## 1. Install

Three supported paths, all covered in the [main README](../README.md#install-one-command-is-enough-docker--existing-mysql-also-supported):

| Path | Command | When to use |
|---|---|---|
| Portable (no prerequisites) | one line, Windows or Linux | shop-floor PC or a quick evaluation — bundles Java 17 + MariaDB |
| Docker Compose | `sudo bash deploy.sh` | Linux server that already has Docker |
| Script + existing MySQL | `sudo bash install.sh --db-pass '...'` | you already run MySQL 8 / PostgreSQL and Java 17 |

What the portable build does on first start: downloads JRE 17 and MariaDB 11.4 into
`runtime/`, initialises the schema in `runtime/data`, writes `config/application.yml` with a random
database password, generates a random admin password into `data/initial-admin.txt`, then serves
`http://localhost:8080/app/`.

Ports and data:

- application `8080` (change with `start.bat -Port 9000` / `PORT=9000 ./start.sh`)
- bundled database `127.0.0.1:3307` (deliberately not 3306, so it never fights an existing MySQL)
- all state under the install directory; stopping is `stop.bat` / `./stop.sh`

## 2. Connect a real machine

The system never assumes a specific vendor. A device is described by **how to reach it** plus a
**point table** that maps register addresses to business meaning.

### Three wiring paths

| Path | Protocol value | Typical hardware |
|---|---|---|
| Direct Ethernet | `modbus-tcp` | PLC / controller with an Ethernet port (e.g. Siemens S7-200 SMART, Mitsubishi FX5U) |
| RS-485 through a serial gateway | `modbus-rtu-tcp` | meters, VFDs, temperature controllers behind a serial-to-Ethernet box |
| Direct serial port | `modbus-rtu` | a USB-to-485 adapter plugged into the server |
| No protocol at all | counter point on a digital input | relay + limit switch on a legacy press (one pulse = one part) |

### Finding devices

Admin UI → *Device discovery*: it enumerates the server's own subnets, scans a port range, then
probes candidate units. The probe also **infers roles from how values move** while you start/stop the
machine — monotonic values are suggested as production counters, 0/1 toggles as run status.

### Point table

Points are plain JSON. A production counter looks like this:

```json
[
  {
    "key": "cnt",
    "name": "Output counter",
    "address": 0,
    "functionCode": 3,
    "dataType": "u16",
    "scale": 1.0,
    "offset": 0.0,
    "unit": "pcs",
    "decimals": 0,
    "role": "count"
  }
]
```

- `address` is the zero-based register/coil address (Modbus 40001 → `0`)
- `functionCode` is `3` (holding registers) or `4` (input registers) for values, `1` (coils) or `2`
  (discrete inputs) for bits
- `dataType` is one of `u16`, `s16`, `u32`, `s32`, `f32`, `bool`
- `role` drives business behaviour: `count` feeds production statistics and auto stock-in, `status`
  drives the running/stopped badge, `alarm` raises alarms, `value` is an ordinary number

Templates are supported in two layers: built-in templates shipped with the release (Siemens, Delta,
Mitsubishi, Inovance, meters, VFDs, temperature controllers, generic 485) and **your own templates
saved in the database** — configure one machine, save it as a template, reuse it for the rest of the
fleet by entering an IP.

## 3. API

Two surfaces:

| Surface | Auth | Purpose |
|---|---|---|
| `/api/**` | session cookie (`POST /api/auth/login`) | the web UI; do not expose to the internet |
| `/open/v1/**` | per-device API key | integration with a customer MES / ERP / mini-program |

The full endpoint list lives in [`HANDBOOK.md` §四](HANDBOOK.md) (Chinese). Endpoints you will touch
most often:

```
POST /api/auth/login                 {username, password} → session cookie
GET  /api/devices                    device list with live point values
POST /api/devices                    create device (protocol, host, port, unitId, points)
PUT  /api/devices/{id}               update device / point table
GET  /api/devices/{code}/history     point history (?point=cnt&limit=180)
GET  /api/discovery/local-subnets    subnets of the server's own NICs
POST /api/discovery/scan             {prefixes, port, timeoutMs} → discovered hosts
POST /api/discovery/probe            read registers from a candidate device
GET  /api/device-templates           saved templates
POST /api/erp/prod/status?code=...   today / month / year production KPI
GET  /api/erp/prod/chart?code=...    production chart (mode=day|month|year|all)
```

## 4. Adding a protocol

The collector is a driver interface: implement `read`/`write` for your protocol, register it under a
protocol name, and the rest of the system (polling, offline detection, history, statistics,
auto stock-in) works unchanged. `HANDBOOK.md` §五 walks through OPC UA / Mitsubishi MC / private
serial protocols. A simulator driver (`sim`) ships in the box so you can develop without hardware.

## 5. Energy, reports, AI

- **Energy** — points can be tagged with energy roles (voltage, current, power, energy); tariffs are
  configured per meter with peak/flat/valley/super-peak rates, and demand is tracked on a 15-minute
  window against a contract limit.
- **Reports** — production is aggregated into an hourly table, which is the single source for shift
  output, OEE-style availability, downtime reasons and Excel export.
- **AI** — the assistant answers in natural language by calling tools (not by generating SQL), so
  permissions apply exactly as in the UI. It supports DeepSeek / Qwen / Ollama and can be disabled
  entirely in settings.

## 6. Upgrades

The System page checks the release feed (Gitee first, GitHub as fallback), downloads the signed
update archive, verifies the signature against the embedded public key, and swaps the jar without
touching the database. Offline sites can upload the archive manually. Details:
[`RELEASE.md`](RELEASE.md) (Chinese) and the *Delivery* bullet in the main README.

## 7. Documentation map

| Document | Language | Contents |
|---|---|---|
| [../README.md](../README.md) | EN | Overview, install, features, comparison |
| [../README.zh-CN.md](../README.zh-CN.md) | 中文 | 中文主 README |
| [HANDBOOK.md](HANDBOOK.md) | 中文 | Architecture, connecting devices, adding protocols, energy, reports, upgrades, API list |
| [ROADMAP.md](ROADMAP.md) | 中文 | Roadmap and complete changelog with measured results |
| [COMPETITORS.md](COMPETITORS.md) | 中文 | Competitor comparison and talk tracks |
| [DEMO.md](DEMO.md) | 中文 | Demo script and tour |
| [RELEASE.md](RELEASE.md) | 中文 | Release and upgrade procedure |
| [../tools/portable/README-portable.txt](../tools/portable/README-portable.txt) | 中文 | Portable build: layout, ports, offline install, backup |

## 8. Contributing

Run the two test suites before sending a pull request:

```bash
python tools/smoke-all.py      # 99 API smoke checks
python tools/selftest.py       # contract regression
```

Both must stay green. Translations of this page and of `HANDBOOK.md` are welcome — keep the English
README as the canonical entry point and link new files from the tables above.
