# KnowFab SIRP · KI-gestütztes Smart-Factory-Management-System

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · **Deutsch** · [Русский](README.ru.md)

[![License: AGPL-3.0](https://img.shields.io/github/license/ZhuangRenyang/KnowFab-SIRP)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/ZhuangRenyang/KnowFab-SIRP)](https://github.com/ZhuangRenyang/KnowFab-SIRP/releases)

**Dass die Maschinen ihre Stückzahlen selbst melden — statt dass Werker Barcodes scannen.**
Datenerfassung direkt aus der Maschine → Stückzählung je Linie → automatische Einlagerung ins
Fertigwarenlager → Einkauf, Lager, Vertrieb und Finanzen in einem einzigen Buch → Auswertungen →
KI-Assistent für alle. Läuft auf **deinem eigenen Server**. Java 17 + Spring Boot 3 + Vue 3 + MySQL,
geliefert als ein einziges Jar. Oder die **portable Variante** mit gebündeltem Java und Datenbank:
ein nackter Rechner startet mit einem einzigen Befehl.

## Vier Merkmale, alle im Werk entstanden

- **Ein Befehl, keine Voraussetzungen** — Java 17 + MariaDB an Bord; eine Zeile unter Windows oder
  Linux. Kein Docker, keine vorinstallierte Datenbank, der Browser öffnet sich von selbst. Alles
  liegt in einem Verzeichnis: **kopieren = Backup, löschen = deinstallieren**.
- **Auch alte Maschinen zählen mit** — drei Wege: Modbus TCP über Ethernet, RS-485-Durchleitung und
  Zählung per Relais + Endschalter. Eine zwanzig Jahre alte Presse ohne Netzwerkanschluss zählt
  trotzdem jedes Teil.
- **Produktion wird automatisch zum Lagerbestand** — jeder Zählimpuls geht ins Fertigwarenlager,
  niemand scannt. Einkauf, Lager, Vertrieb und Finanzen teilen ein Buch – „die Bücher stimmen nicht"
  kann konstruktionsbedingt nicht passieren.
- **Updates migrieren keine Daten** — Systemseite → *Nach Updates suchen* → Download und
  Signaturprüfung → Jar austauschen. Die Datenbank bleibt unangetastet, Daten verlassen das Werk
  nicht.

## Installation (ein Befehl)

**Windows** (ab 10):

```powershell
# China (Gitee, empfohlen)
powershell -ExecutionPolicy Bypass -Command "irm https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.ps1 | iex"

# International / Alternative (GitHub)
powershell -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.ps1 | iex"
```

**Linux / macOS (Intel)**:

```bash
# China (Gitee, empfohlen)
curl -fsSL https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.sh | bash

# International / Alternative (GitHub)
curl -fsSL https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.sh | bash
```

Beim ersten Start werden **JRE 17 + MariaDB 11.4** (ca. 130 MB) heruntergeladen, danach startet das
System in Sekunden. Die Datenbank nutzt `127.0.0.1:3307` und kollidiert nie mit einem vorhandenen
MySQL auf 3306. Das anfängliche Admin-Passwort steht im Startfenster und in `data/initial-admin.txt`.

Weitere Wege:

- **Docker Compose** (Linux-Server): `git clone`, dann `sudo bash deploy.sh` (in China `deploy-cn.sh`)
- **Wenn MySQL 8 und Java 17 schon vorhanden sind**: `sudo bash install.sh --db-pass 'dein-passwort'`

## Funktionsumfang

- **Maschinenanbindung** — Modbus TCP / RTU / Durchleitung; doppeltes Scannen von Subnetz und
  Slave-ID; Punktliste als JSON; Zählung per Relais + Endschalter; Simulator inklusive, damit du
  ohne Hardware entwickeln kannst
- **Produktion** — Zähler je Maschine (heute / Monat / Jahr), vier Diagramme (Tag / Monat / Jahr /
  gesamt), Linienkonfiguration mit Arbeitsplatzreihenfolge und Zählpunkt, Rücksetzzeit je Gerät
  (zwei Schichten)
- **ERP** — Einkauf (Bestellung → Wareneingang → Retoure) · Lager (Bestände / 7 Belegarten /
  Warnungen / Inventur) · Vertrieb (Auftrag → Lieferung → Retoure, harte Bestandsprüfung vor
  Lieferung) · Finanzen (Forderungen/Verbindlichkeiten, Zahlungen, vier Fälligkeitsstufen) — das
  Lager kennt genau einen Algorithmus, deshalb stimmen die Bücher immer
- **Auswertungen** — Geschäftsüberblick / Lagerbewegung (Anfang + Zu − Ab = Ende) / Ein- und
  Auslagerungsstatistik / Einkaufs- und Vertriebsausführung; alles als Excel exportierbar; Belege
  tragen ein **Geschäftsdatum**, damit Nachbuchungen am Monatsende nicht im falschen Monat landen
- **Energie** — Unterzähler, Tarife HT/NT/Spitze, Grundpreis und Leistungspreis,
  15-Minuten-Lastgang mit Vertragslinie
- **KI** — Tagesbericht um 07:00 und **KI-Assistent für alle**: natürliche Fragen zu Lager,
  Produktion, Maschinen, Aufträgen und Finanzen, rollenabhängig, Werkzeugaufrufe statt generiertem
  SQL, DeepSeek / Qwen / Ollama, komplett abschaltbar
- **Rollen** — vier Stufen: Admin / Werkleiter / Schichtführer / **Vertrieb** (nur Lager und
  zugesagte Menge, keine Kostenfelder)

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [README.md](README.md) | Englisches README (maßgeblich und am ausführlichsten) |
| [README.zh-CN.md](README.zh-CN.md) | Chinesisches README |
| [docs/README.en.md](docs/README.en.md) | Englisches Doku-Verzeichnis: Installation, Maschinenanbindung, Punktliste, API |

> Diese Seite ist eine **Zusammenfassung**. Details, Fehlersuche und die vollständige API-Liste
> findest du in der englischen oder chinesischen Version.
> Das [HANDBOOK](docs/HANDBOOK.md) (über 1600 Zeilen) ist derzeit nur auf Chinesisch verfügbar;
> für eine Übersetzung bitte ein Issue öffnen.

## Lizenz

Der Code steht unter [AGPL-3.0](LICENSE): Nutzung im eigenen Werk, Lernen oder ein offener Fork sind
**kostenlos**. Geschlossene kommerzielle Nutzung, SaaS oder White-Label erfordern eine
[kommerzielle Lizenz](COMMERCIAL-LICENSE.md).

**Kontakt** — Maschinenanbindung und Nachrüstung, Einführung, kommerzielle Lizenzen: 996491835@qq.com
