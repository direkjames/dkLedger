# dkLedger

The dk suite's financial analyst. Author: **direk james**.
Server: **Purpur 26.3**, Java **25**. Package: `com.direk.dkledger`. Built on **dkCore**.

> **All Rights Reserved.** See [LICENSE](LICENSE).

## What it does

| Part | What it does |
|---|---|
| **Ledger** | Records every money change, wallet and bank, with the plugin that made it. New plugins are found automatically (call stack), nothing to hook by hand. |
| **Money supply** | Every hour: total money (wallets + dkBank), money per active player, how much the richest 10% hold. |
| **Alerts** (B1–B10) | Supply growth, one source making most new money, too few sinks, players far above the median, buy/sell loops, new money sources, big staff changes, wealth concentration, Lodestock price swings, alt transfers, big transfers. |
| **Admin actions** | Freeze a player's outgoing money, staff notes, resolve alerts, undo one ledger entry (owners). |
| **Config audit** (A1–A12) | `/ledger scan`: money loops, bad prices, crafting loops, spawner payback, reward income per day, broken commands, bank interest, OneBlock phases, sinks, price changes. |
| **What if** | `/ledger whatif ...`: runs the audit with one change (price, spawner drop, spawner price, bank rate, reward size) and shows what it fixes, what it breaks and the 30-day money supply. Never edits a file. |
| **Weekly report** | An HTML page with charts (and a Markdown copy) every week in `plugins/dkLedger/reports/`. |
| **Menu** | `/ledger` in game: supply, alerts, sources, top earners, audit, frozen players, weekly report. |
| **API** | `LedgerApi` and `LedgerAlertEvent` for the dk suite's Discord bot (see below). |

### How money is recorded

- **Vault**: dkLedger sits in front of the real economy (EssentialsX) and passes every call straight through. Successful deposits and withdrawals are recorded with the plugin that made them. It's registered in `onLoad` at the highest priority, so plugins get it when they look up the economy.
- **EssentialsX**: `/eco` (from staff, the console, or other plugins' reward commands), `/pay`, `/sell` and direct API use. Changes already recorded through Vault aren't counted twice.
- **dkBank**: every bank change. Deposits and withdrawals move money between a player's own wallet and bank (they only count as their fee); interest creates money; upgrades and fees remove it.

Sources are plugin names, plus `staff:<name>`, `console`, `Essentials /pay`, `Essentials /sell`, `dkBank`, `dkBank transfers`,
`dkBank interest`, `dkBank upgrades`. `ledger.transfer-sources` and `ledger.internal-sources` in config.yml say which
ones move money between players or between a player's wallet and bank.

## Commands

`/ledger` (alias `/dkledger`). Permission `dkledger.admin` (ops), except `reverse`, which needs `dkledger.owner` (not given to ops).

| Command | What it does |
|---|---|
| `/ledger` | The menu (players) or help (console) |
| `/ledger supply` | Money supply now, 24h and 7-day change |
| `/ledger sources [day\|week\|month]` | Money in and out by source |
| `/ledger top [day\|week\|month]` | Top earners |
| `/ledger player <name>` | A player's earnings, sources, newest entries (with ids) and staff notes |
| `/ledger alerts [open\|all] [page]` / `resolve <id> [note]` | Alerts and resolving them |
| `/ledger freeze <player> [reason]` / `unfreeze <player>` / `frozen` | Stop a player sending money to others |
| `/ledger note <player> <text>` | A staff note |
| `/ledger reverse <entry id>` | Undo one entry (asks to confirm) |
| `/ledger scan` / `findings` / `budget` / `report` | The config audit |
| `/ledger whatif <change>` | The simulator (type it alone for the options) |
| `/ledger weekly` | Write the weekly report now |
| `/ledger reload` | Reload config.yml and messages.yml |

`dkledger.alerts` (ops): get alerts in chat.

## Build

0. dkLedger compiles against three APIs from your local Maven repository. Run once (and again when they change):
   - dkCore: `./gradlew publishToMavenLocal`
   - dkBank: `./gradlew :api:publishToMavenLocal`
   - Lodestock: `./gradlew :api:publishToMavenLocal`
1. Open this folder in IntelliJ IDEA. Set Project SDK and Gradle JVM to **Java 25**.
2. `./gradlew build` → `build/libs/dkLedger-<version>.jar` goes in `plugins/` next to dkCore. Runs the tests too.
3. `./gradlew publishToMavenLocal` → lets the Discord bot compile against dkLedger's API.

## For the Discord bot

```kotlin
compileOnly("com.direk:dkledger:0.2.0")
```
```yaml
softdepend: [dkLedger]
```
```java
LedgerApi.find().ifPresent(ledger -> ledger.supply().thenAccept(s -> ...));

@EventHandler
public void onAlert(LedgerAlertEvent e) { /* post e.getAlert() to a channel */ }
```
`LedgerApi` has everything `/ledger` has: supply, sources, player summaries, alerts and resolving them, freeze,
unfreeze, notes, reverse, scan and the simulator. Futures complete on the main thread.

## Database

dkCore's shared database, tables starting with `dkledger_`. Single entries are kept `ledger.keep-entries-days`
(30); daily totals, snapshots and alerts are kept. IP addresses are never stored, only a salted hash
(for the alt-transfer check), with the salt in `plugins/dkLedger/data/salt.txt`.

## Code layout

- `audit/` — the config audit (plain Java, no Bukkit, tested). `whatif/` — the simulator on top of it.
- `ledger/` — recording: the Vault proxy and EssentialsX/dkBank hooks in `hook/`, source attribution, storage.
- `supply/`, `detect/` — snapshots, the live and hourly checks, alerts.
- `admin/` — freeze, logins, and `LedgerActions` (shared by commands, menus and the API).
- `report/`, `gui/`, `command/`, `config/`, `api/`.
