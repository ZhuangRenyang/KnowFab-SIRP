# Contributing to KnowFab SIRP

Thanks for looking. This project is maintained by **one person**, so the fastest way to get a change
merged is to keep it small and focused.

Issues and pull requests are welcome in **Chinese or English** — whichever you write more comfortably.

---

## What is most wanted right now

### 1. Protocol drivers (highest value)

Currently supported: Modbus TCP, Modbus RTU, RTU-over-TCP, OPC UA, serial 485, DI pulse counting.

Missing, and most requested by users in the field:

| Brand | Protocol | Notes |
|-------|----------|-------|
| Mitsubishi FX / Q series | MELSEC (MC protocol) | binary or ASCII framing |
| Inovance (汇川) H3U / AM400 | Modbus + proprietary | some models speak plain Modbus |
| Delta (台达) AS / DVP | Modbus + proprietary | |
| Omron (欧姆龙) CJ / CP | FINS | |
| Xinje (信捷) XD series | Modbus + proprietary | |

A driver is one class implementing `ProtocolDriver` (see `src/main/java/com/factory/hub/driver/`).
If you have the hardware on your desk, you are the best person to write it — I cannot test what I
do not own.

### 2. Verify alarm code tables

`src/main/resources/alarmcodes/` holds per-brand alarm dictionaries (code → human-readable cause).
Built-in today: Delta and Mitsubishi servo drives.

If you have a manual for another brand, adding a table is a pure data change — no Java required.
Accuracy matters more than coverage: a wrong cause is worse than no cause.

### 3. Field reports from real machines

The most useful issue you can open is: *"I connected device X, here is what happened."*
Include the model, communication parameters, and the raw error. These reports become documentation.

### 4. Frontend and reports

The UI is Vue 3. It works, but it is not beautiful. If you enjoy making dense industrial screens
readable, this is the area with the most room.

---

## Reporting a bug

Please include all three:

1. **Device model** and communication parameters (IP, port, baud rate, slave id)
2. **What you expected** vs what happened
3. **The error text or a screenshot** — from `logs/` or the browser console

Without the device model and parameters, most connection problems cannot be diagnosed.

---

## Development setup

Requirements: JDK 17, Maven, Node 18+, and a MySQL 8 or MariaDB instance.

```bash
# 1. database
mysql -uroot -e "CREATE DATABASE devicehub CHARACTER SET utf8mb4;
  CREATE USER 'devicehub'@'localhost' IDENTIFIED BY 'DeviceHub2026';
  GRANT ALL ON devicehub.* TO 'devicehub'@'localhost';"

# 2. backend (http://localhost:8080)
mvn spring-boot:run

# 3. frontend dev server (hot reload)
cd frontend && npm install && npm run dev
```

Configuration lives in `src/main/resources/application.yml`. Environment variables override it —
see the comments in that file.

To test without hardware, use a device with protocol `sim`: it generates plausible output, status
and alarm values so the whole chain (collection → rollup → stock → documents) can be exercised.

---

## Pull request rules

1. **One thing per PR.** A bug fix and a refactor in the same PR will be asked to split.
2. **Do not change the database schema** in a feature PR. Schema changes ship with a migration and
   are released separately (users upgrade in place; a broken migration loses their data).
3. **Never write to a PLC in a collection path.** Reading is always safe; writing is gated behind
   an explicit per-point whitelist. If your change touches the write path, say so in the PR.
4. **Keep files under ~300 lines** and business logic in a service, not in a controller.
5. **Match the existing style.** No reformatting of untouched files.

Commit messages: `type(scope): what changed` (`fix(alarm): ...`, `feat(driver): ...`).
Chinese or English is fine.

---

## Licence

By contributing you agree that your contribution is licensed under **AGPL-3.0**, the same as the
rest of the project. There is no CLA to sign.

Commercial licensing (closed-source redistribution, SaaS, white-label) is separate — see
`LICENSE-COMMERCIAL.md` if present, or open an issue to ask.
