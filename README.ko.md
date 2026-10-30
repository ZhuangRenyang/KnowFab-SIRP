# KnowFab SIRP · AI 스마트 공장 관리 시스템

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · **한국어** · [Español](README.es.md) · [Deutsch](README.de.md) · [Русский](README.ru.md)

[![License: AGPL-3.0](https://img.shields.io/github/license/ZhuangRenyang/KnowFab-SIRP)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/ZhuangRenyang/KnowFab-SIRP)](https://github.com/ZhuangRenyang/KnowFab-SIRP/releases)

**작업자가 바코드를 찍는 대신, 기계가 스스로 실적을 보고합니다.**
설비에서 직접 데이터 수집 → 라인별 완제품 카운트 → 완제품 창고 자동 입고 →
구매·재고·판매·재무 통합 관리 → 경영 리포트 → 전사 AI 어시스턴트.
**자체 서버**에서 동작합니다. Java 17 + Spring Boot 3 + Vue 3 + MySQL, 단일 jar로 납품.
Java와 데이터베이스를 내장한 **포터블 버전**을 쓰면, 아무것도 설치되지 않은 PC에서도 명령 한 줄로 실행됩니다.

## 네 가지 특징 (모두 현장에서 나온 것)

- **명령 한 줄로 설치, 사전 설치 불필요** — Java 17 + MariaDB 내장. Windows / Linux 각각 한 줄.
  Docker도, 데이터베이스 사전 설치도 필요 없고 실행하면 브라우저가 자동으로 열립니다.
  데이터는 설치 폴더 하나에 모입니다 — **복사하면 백업, 삭제하면 제거**.
- **노후 설비도 카운트됩니다** — 이더넷 Modbus TCP, RS-485 패스스루, 릴레이+리미트 스위치 세 가지 방식.
  네트워크 포트가 없는 20년 된 프레스도 제품 하나마다 자동으로 셉니다.
- **생산 수량이 그대로 재고가 됩니다** — 카운트가 완제품 재고로 자동 기표되어 작업자 스캔에 의존하지 않습니다.
  구매·재고·판매·재무가 같은 원장을 공유하므로 "장부와 실물이 맞지 않는" 문제가 구조적으로 생기지 않습니다.
- **업그레이드에 데이터 이전이 없습니다** — 시스템 화면에서 "업데이트 확인" → 다운로드 + 서명 검증 → jar 교체.
  데이터베이스는 그대로이고, 자체 호스팅이므로 데이터가 공장 밖으로 나가지 않습니다.

## 설치 (명령 한 줄)

**Windows** (10 이상):

```powershell
# 중국 본토 (Gitee, 권장)
powershell -ExecutionPolicy Bypass -Command "irm https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.ps1 | iex"

# 그 외 지역 / 대체 (GitHub)
powershell -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.ps1 | iex"
```

**Linux / macOS (Intel)**:

```bash
# 중국 본토 (Gitee, 권장)
curl -fsSL https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.sh | bash

# 그 외 지역 / 대체 (GitHub)
curl -fsSL https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.sh | bash
```

첫 실행 시 **JRE 17 + MariaDB 11.4(약 130MB)** 를 자동으로 내려받고, 이후에는 몇 초 만에 시작합니다.
데이터베이스는 `127.0.0.1:3307` 을 쓰므로 기존 MySQL(3306)과 충돌하지 않습니다.
초기 관리자 비밀번호는 실행 창에 표시되고 `data/initial-admin.txt` 에도 저장됩니다.

다른 설치 방법:

- **Docker Compose** (Linux 서버): 저장소를 clone 한 뒤 `sudo bash deploy.sh` (중국 내수는 `deploy-cn.sh`)
- **이미 MySQL 8 + Java 17 이 있는 경우**: `sudo bash install.sh --db-pass 'MySQL 비밀번호'`

## 주요 기능

- **설비 연결** — Modbus TCP / RTU / 패스스루. 서브넷 + 슬레이브 ID 이중 스캔으로 기기 탐색.
  포인트 테이블은 JSON, 릴레이 + 리미트 스위치 카운트 지원. 시뮬레이터 내장으로 실기 없이 개발 가능
- **생산** — 설비별 카운터(오늘 / 이번 달 / 올해), 일·월·연·전체 4개 차트,
  라인 구성(공정 순서와 완제품 카운트 지점 지정), 설비별 리셋 시각(2교대 대응)
- **ERP** — 구매(발주 → 입고 → 반품) · 재고(잔액 / 7종 전표 / 경보 / 재고조사) ·
  판매(수주 → 출하 → 반품, 출하 전 재고 엄격 검증) · 재무(외상매출·외상매입, 수금/지급, 4구간 에이징)
- **리포트** — 경영 개요 / 재고 수불(기초 + 입고 − 출고 = 기말) / 입출고 통계 / 구매·판매 집행.
  모두 Excel 로 내보내기 가능. 전표에 **업무일자**가 있어 월말 소급 입력도 월이 섞이지 않습니다
- **에너지** — 부하별 계량, 피크·평시·경부하·최대부하 요금, 기본요금과 수요전력 이원 계산,
  15분 수요 그래프(계약값 초과 시 적색선)
- **AI** — 매일 07:00 브리핑(WeCom 푸시 가능)과 **전사 AI 어시스턴트**.
  재고 / 생산 / 설비 / 주문 / 재무를 자연어로 질의하고 역할별로 범위 제한(영업은 재고만).
  SQL 생성이 아닌 도구 호출 방식이며 DeepSeek / Qwen / Ollama 지원, 기능 전체 비활성화도 가능
- **권한** — 관리자 / 공장장 / 반장 / **영업**(재고 조회와 약정 가능 수량만, 원가 항목 없음) 4단계

## 문서

| 문서 | 내용 |
|---|---|
| [README.md](README.md) | 영어 README (정식·가장 상세) |
| [README.zh-CN.md](README.zh-CN.md) | 중국어 README |
| [docs/README.en.md](docs/README.en.md) | 영어 문서 색인: 설치, 설비 연결, 포인트 테이블 필드, API 개요 |

> 이 페이지는 **요약본**입니다. 자세한 내용과 문제 해결, API 목록은 영어 또는 중국어 버전을 참고하세요.
> 중국어 [HANDBOOK](docs/HANDBOOK.md)(1600줄 이상)은 현재 중국어만 제공됩니다. 번역 요청은 Issue 로 알려 주세요.

## 라이선스

[AGPL-3.0](LICENSE) 으로 공개되어 있습니다. 자사 공장 사용, 학습, 포크 후 공개는 **무료**.
클로즈드소스 상용 / SaaS / 화이트라벨은 [상용 라이선스](COMMERCIAL-LICENSE.md)가 필요합니다.

**문의** — 설비 연결·개조 상담, 구축 지원, 상용 라이선스: 996491835@qq.com

**커뮤니티 (中文交流群)** — 설비 연결·점표 설정·도입 문의는 QQ 그룹에서: **知造(KnowFab-SIRP) · 1132864448**
