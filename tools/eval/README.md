# Search evaluation corpus

Developer tooling for the debug-only **Search eval** screen (Settings → Debug → Search eval).
Nothing here ships to users; release builds contain no eval surface at all.

The corpus is nine **public, stable-URL PDFs** chosen to mimic a personal document library
(immigration instructions, tax forms, technical papers, a standard, a whitepaper) — so the
committed query set stays honest without ever touching anyone's private files.
`eval-set.json` holds 45 hand-verified queries: every `expectedPage` was checked against the
actual PDF text, and every `paraphrase` entry was verified for reduced-or-zero lexical overlap
with its target page.

## Setup (once per device)

```powershell
# 1. Download the corpus on the dev machine
.\Fetch-Corpus.ps1

# 2. Push corpus + query set to the phone
adb shell mkdir -p /sdcard/LumenEval
adb push .\corpus\. /sdcard/LumenEval/
adb push .\eval-set.json /sdcard/LumenEval/

# 3. In Lumen (debug build): Settings → Indexed Folders → Add folder → pick LumenEval
#    Wait for "9 documents · 9 indexed".

# 4. Settings → Debug → Search eval → "Pick eval set" → LumenEval/eval-set.json → Run
```

## Reading the scorecard

- Rows are (variant × tag); `overall` first. `n` = resolved entries, `@10`/`@20` = exact hit
  counts, `MRR` = mean reciprocal rank, `med ms` = median query latency.
- **Compare variants within one run only** — both ran against the identical index, so their
  delta is meaningful. Absolute numbers shift with whatever else is indexed on the device
  (IDF is corpus-wide); the corpus stamp tells you when two runs are comparable.
- Expected baselines by tag:
  - `identifier` / `phrase` / `filename` — should be high already; **regressions here are bugs**.
  - `paraphrase` — the semantic gap. Several entries (`money without banks`, `crypto mining`,
    `student visa`) are verified to have little or no lexical overlap with their target page:
    they are **expected to miss** under CURRENT and BM25. This tag is the gate for the semantic
    lane (variant FUSED) — if FUSED doesn't lift it, the model doesn't ship.
  - `typo` — expected 0 today; the baseline for the "Did you mean" feature.
  - `common` — all-common-term queries; watches the IDF-clamp tie-break (order should stay
    sensible, not alphabetical).

## Entry format

```json
{"query": "student visa", "expectedFile": "i765-instructions.pdf", "expectedPage": 4, "tag": "paraphrase"}
```

- `expectedPage` is **1-indexed — the page number you see on screen** ("p. 4" → 4). Omit it
  when any hit in the file should count (also the only way filename-lane rows can hit).
- `tag` is free-form; the scorecard groups by whatever tags appear.

## Adding private entries

Real queries against your own library are the most honest signal. Add lines to the JSON copy
**on the device** (or keep a second file and pick it instead) — private corpora and their query
sets must never enter this repo. When a real search disappoints or delights you, capture it as
one line while it's fresh.
