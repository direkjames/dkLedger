# dkLedger

Our server's financial analyst. Private plugin for Purpur 26.3 (Java 25) by direk james.

## Phase 1: config audit (this version)

`/ledger scan` reads every economy plugin's files and reports, from most to least urgent:

| Check | What it finds |
|---|---|
| A1 | Items that sell for as much as (or more than) they cost in the same shop |
| A2 | Items bought in one plugin and sold in another for profit (EconomyShopGUI, Lodestock, Virtual Spawner, HeadDatabase) |
| A3 | Buy → craft/smelt/stonecut/smith → sell loops, using the server's real recipes |
| A4 | Crafting that multiplies value, or makes unsellable ingredients sellable |
| A5 | Items players make forever (generators, farms) sold above a fair price |
| A6 | Spawner payback times, drops that sell higher elsewhere, price mismatches |
| A7 | Every reward that pays money and a daily income estimate for a casual player and a grinder; money commands in any other plugin (so new reward plugins show up on their own) |
| A8 | Reward commands that don't exist and crate keys that don't exist |
| A9 | dkBank interest per tier at its cap, missing caps, alt limit |
| A10 | OneBlock: money per block per phase, valuable fixed blocks with resets, valuable chest loot |
| A11 | Every money sink, one-time or repeating |
| A12 | Price changes since the last scan |

Each scan writes a Markdown report to `plugins/dkLedger/reports/` (`latest.md` is always the newest).
A scan also runs 30 seconds after startup; admins online get the summary if it finds something critical.

### Commands (permission `dkledger.admin`, ops by default)

| Command | What it does |
|---|---|
| `/ledger scan` | Run the audit now (off the main thread) |
| `/ledger findings [all\|critical\|warning\|info\|a1..a12] [page]` | List findings; hover a line for details and the fix |
| `/ledger budget` | Daily reward income by source |
| `/ledger report` | Where the full report is saved |
| `/ledger reload` | Reload config.yml |

`dkledger.alerts` (ops by default): get the startup scan's summary when it finds critical problems.

### Config

`config.yml` has the plugin folder names, the casual and grinder profiles and every threshold, all commented.

## Build

```
./gradlew build          # jar in build/libs/dkLedger-<version>.jar, runs the tests
./gradlew runPurpur      # local Purpur 26.3 test server with Vault, EssentialsX and dkLedger
```

## Code layout

- `audit/` — the checks. Plain Java, no Bukkit, so they run off the main thread and in unit tests.
  - `read/` reads each plugin's files into a price book, spawner data and a scan of all plugin folders.
  - `check/` the twelve checks. `report/` the Markdown report.
- `platform/` — the Paper side: reading YAML with SnakeYAML, collecting recipes and commands.
- `scan/`, `command/`, `config/` — running scans, `/ledger`, config.yml.

## Roadmap

1. ✅ Config audit
2. Ledger (every transaction, by plugin), money supply snapshots, Discord webhook alerts
3. Live detectors, weekly report with charts, GUI, "what if" simulator
4. Discord bot with `/ledger` slash commands and admin actions
