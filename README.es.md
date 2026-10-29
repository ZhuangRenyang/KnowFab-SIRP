# KnowFab SIRP · Sistema de gestión de fábrica inteligente con IA

[English](README.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · **Español** · [Deutsch](README.de.md) · [Русский](README.ru.md)

[![License: AGPL-3.0](https://img.shields.io/github/license/ZhuangRenyang/KnowFab-SIRP)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/ZhuangRenyang/KnowFab-SIRP)](https://github.com/ZhuangRenyang/KnowFab-SIRP/releases)

**Que las máquinas reporten su propia producción, en lugar de que los operarios escaneen códigos.**
Adquisición de datos directamente del equipo → conteo de producto terminado por línea → entrada
automática en almacén → compras, inventario, ventas y finanzas en un solo libro → informes de
gestión → asistente de IA para todo el equipo. Se ejecuta en **tu propio servidor**.
Java 17 + Spring Boot 3 + Vue 3 + MySQL, entregado como un único jar. O usa la **versión portátil**,
que incluye Java y la base de datos: un equipo sin nada instalado arranca con un solo comando.

## Cuatro rasgos, todos nacidos en el taller

- **Un comando, sin requisitos previos** — Java 17 + MariaDB incluidos; una línea en Windows o Linux.
  Sin Docker y sin instalar la base de datos; el navegador se abre solo. Todo vive en una carpeta:
  **copiarla es la copia de seguridad, borrarla es desinstalar**.
- **Las máquinas antiguas también cuentan** — tres vías: Modbus TCP por Ethernet, pasarela RS-485 y
  conteo por relé + final de carrera. Una prensa de hace veinte años sin puerto de red sigue contando
  una pieza por golpe.
- **La producción se convierte en inventario** — cada conteo entra en existencias de producto
  terminado sin que nadie escanee nada. Compras, inventario, ventas y finanzas comparten un libro,
  así que «los números no cuadran» no puede ocurrir por construcción.
- **Las actualizaciones no migran datos** — panel del sistema → *Buscar actualizaciones* → descarga y
  verificación de firma → se reemplaza el jar. La base de datos no se toca y los datos no salen de la
  planta.

## Instalación (un solo comando)

**Windows** (10 o superior):

```powershell
# China (Gitee, recomendado)
powershell -ExecutionPolicy Bypass -Command "irm https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.ps1 | iex"

# Internacional / alternativa (GitHub)
powershell -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.ps1 | iex"
```

**Linux / macOS (Intel)**:

```bash
# China (Gitee, recomendado)
curl -fsSL https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/tools/portable/install.sh | bash

# Internacional / alternativa (GitHub)
curl -fsSL https://raw.githubusercontent.com/ZhuangRenyang/KnowFab-SIRP/main/tools/portable/install.sh | bash
```

La primera ejecución descarga **JRE 17 + MariaDB 11.4** (unos 130 MB) y las siguientes arrancan en
segundos. La base de datos usa `127.0.0.1:3307`, así que nunca choca con un MySQL existente en 3306.
La contraseña inicial de administrador se muestra en la ventana de arranque y se guarda en
`data/initial-admin.txt`.

Otras formas de instalación:

- **Docker Compose** (servidor Linux): `git clone` y luego `sudo bash deploy.sh` (`deploy-cn.sh` en China)
- **Si ya tienes MySQL 8 y Java 17**: `sudo bash install.sh --db-pass 'tu-contraseña-de-MySQL'`

## Funciones principales

- **Conexión de equipos** — Modbus TCP / RTU / passthrough; doble escaneo de subred y esclavo;
  tabla de puntos en JSON; conteo por relé + final de carrera; simulador incluido para desarrollar
  sin hardware
- **Producción** — contadores por máquina (hoy / mes / año), cuatro gráficos (día / mes / año /
  histórico), configuración de líneas con orden de puestos y punto de conteo de producto terminado,
  hora de reinicio por equipo (apto para dos turnos)
- **ERP** — compras (pedido → recepción → devolución) · inventario (saldos / 7 tipos de documento /
  alertas / inventario físico) · ventas (pedido → envío → devolución, con validación estricta de
  existencias antes de enviar) · finanzas (cuentas por cobrar y pagar, cobros y pagos, cuatro tramos
  de antigüedad) — el inventario tiene un único algoritmo, así que los números siempre cuadran
- **Informes** — panorama del negocio / movimiento de inventario (inicial + entradas − salidas =
  final) / estadísticas de entradas y salidas / ejecución de compras y ventas, todo exportable a
  Excel; los documentos llevan **fecha de negocio**, así que el registro tardío de fin de mes no cae
  en el mes equivocado
- **Energía** — medición por circuito, tarifas punta / llano / valle / superpunta, cargo fijo y
  demanda, gráfico de demanda cada 15 minutos con línea límite del contrato
- **IA** — resumen diario a las 07:00 y **asistente para todo el equipo**: preguntas en lenguaje
  natural sobre inventario, producción, equipos, pedidos y finanzas, con alcance por rol; llamadas a
  herramientas en lugar de SQL generado; compatible con DeepSeek / Qwen / Ollama y se puede
  desactivar por completo
- **Roles** — cuatro niveles: administrador / jefe de planta / jefe de equipo / **ventas** (solo
  inventario y cantidad comprometible, sin campos de coste)

## Documentación

| Documento | Contenido |
|---|---|
| [README.md](README.md) | README en inglés (canónico y más detallado) |
| [README.zh-CN.md](README.zh-CN.md) | README en chino |
| [docs/README.en.md](docs/README.en.md) | Índice de documentación en inglés: instalación, conexión de equipos, campos de la tabla de puntos, API |

> Esta página es un **resumen**. Para el detalle, la resolución de problemas y la lista completa de
> API, consulta la versión en inglés o en chino.
> El [HANDBOOK](docs/HANDBOOK.md) (más de 1600 líneas) está por ahora solo en chino; abre un issue si
> necesitas una traducción.

## Licencia

El código es abierto bajo [AGPL-3.0](LICENSE): usarlo en tu propia fábrica, aprender de él o hacer un
fork manteniéndolo abierto es **gratis**. El uso comercial cerrado, SaaS o marca blanca requiere una
[licencia comercial](COMMERCIAL-LICENSE.md).

**Contacto** — integración y retrofit de equipos, despliegue, licencias comerciales: 996491835@qq.com
