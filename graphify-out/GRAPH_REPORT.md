# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1145 nodes · 3665 edges · 43 communities (28 shown, 15 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 465 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23
- Community 24
- Community 26
- Community 27
- Community 28
- Community 29
- Community 31
- Community 32
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 39
- Community 40
- Community 41
- Community 42

## God Nodes (most connected - your core abstractions)
1. `t()` - 121 edges
2. `Video` - 56 edges
3. `Project` - 49 edges
4. `Col` - 49 edges
5. `Seo` - 46 edges
6. `text()` - 41 edges
7. `toast()` - 40 edges
8. `dp()` - 33 edges
9. `overviewTab()` - 33 edges
10. `ProjectActivity` - 31 edges

## Surprising Connections (you probably didn't know these)
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `glyphLabel()` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/Ui.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt
- `seoTab()` --calls--> `chipRow()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Ui.kt

## Import Cycles
- None detected.

## Communities (43 total, 15 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (25): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+17 more)

### Community 1 - "Community 1"
Cohesion: 0.06
Nodes (13): ChannelExplorer, Config, Relevance, Ctx, DepthStat, RecEdge, RecEngine, RecGraph (+5 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (16): Analytics, Bucket, ChannelStat, Compare, Stats, Export, GraphAlgo, Comment (+8 more)

### Community 3 - "Community 3"
Cohesion: 0.07
Nodes (8): EdgeTts, WebSocketListener, Forbidden, TtsException, TtsOptions, TtsPrefs, TtsVoice, EdgeTtsTest

### Community 4 - "Community 4"
Cohesion: 0.05
Nodes (5): BarChartView, ChartDraw, GraphActivity, GraphView, KeyVault

### Community 5 - "Community 5"
Cohesion: 0.06
Nodes (12): filtersSummary(), CollectConfig, MonitorConfig, RecConfig, RunRecord, SeoContext, strings(), toJsonArray() (+4 more)

### Community 6 - "Community 6"
Cohesion: 0.12
Nodes (4): load(), ProjectActivity, Store, VideoActivity

### Community 7 - "Community 7"
Cohesion: 0.08
Nodes (8): AiJournal, AiJournalActivity, Entry, L, LumenCrashHandler, LumenBase, Palette, LogRedactTest

### Community 8 - "Community 8"
Cohesion: 0.09
Nodes (5): CollectService, CollectState, Monitoring, MonitorJob, Notify

### Community 9 - "Community 9"
Cohesion: 0.10
Nodes (3): TtsActivity, TextWatcher, OnSeekBarChangeListener

### Community 10 - "Community 10"
Cohesion: 0.07
Nodes (19): BrowserText, glyphIcon(), glyphLabel(), Ic, ADD, BACK, BUG, COPY (+11 more)

### Community 11 - "Community 11"
Cohesion: 0.08
Nodes (6): chipRow(), rowOf(), scrollPage(), ArrayAdapter, OnItemSelectedListener, Type

### Community 12 - "Community 12"
Cohesion: 0.19
Nodes (8): t(), startRecs(), seoTab(), SettingsActivity, confirm(), openUrl(), prompt(), toast()

### Community 13 - "Community 13"
Cohesion: 0.20
Nodes (10): filtersEditor(), chk(), Col, dp(), edt(), iconBtn(), ripple(), roundBg() (+2 more)

### Community 15 - "Community 15"
Cohesion: 0.13
Nodes (4): BrowserActivity, WebChromeClient, TabRecJs, isNight()

### Community 16 - "Community 16"
Cohesion: 0.17
Nodes (5): onCreate(), ChannelData, ChannelFetch, ChannelStore, PlaylistRef

### Community 17 - "Community 17"
Cohesion: 0.12
Nodes (5): isLocalSource(), Lumen, LumenEnv, newId(), Settings

### Community 18 - "Community 18"
Cohesion: 0.16
Nodes (6): Chunks, JsonText, Vtt, Words, YtQuery, TextTest

### Community 20 - "Community 20"
Cohesion: 0.18
Nodes (3): AutoBackup, restoreFrom(), WizardActivity

### Community 21 - "Community 21"
Cohesion: 0.22
Nodes (5): Fmt, channelRows(), channelsTab(), overviewTab(), chip()

### Community 22 - "Community 22"
Cohesion: 0.15
Nodes (4): FmtTest, LlmParseTest, QuickAndChannelsTest, SeoPureTest

### Community 23 - "Community 23"
Cohesion: 0.20
Nodes (4): LumenActivity, shareText(), showText(), text()

### Community 26 - "Community 26"
Cohesion: 0.21
Nodes (5): SimpleWatcher, recRow(), recsTab(), state(), Thumbs

### Community 27 - "Community 27"
Cohesion: 0.30
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo()

### Community 28 - "Community 28"
Cohesion: 0.24
Nodes (3): Link, LinkActivity, YtLink

### Community 31 - "Community 31"
Cohesion: 0.38
Nodes (3): QuickAnalysis, QuickState, Step

### Community 33 - "Community 33"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

### Community 35 - "Community 35"
Cohesion: 0.33
Nodes (6): btn(), BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **28 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+23 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 132 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **15 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 12` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 13`, `Community 15`, `Community 16`, `Community 17`, `Community 20`, `Community 21`, `Community 22`, `Community 23`, `Community 26`, `Community 27`, `Community 29`, `Community 31`, `Community 32`, `Community 34`?**
  _High betweenness centrality (0.269) - this node is a cross-community bridge._
- **Are the 120 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 120 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05319776009431182 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 2` to `Community 0`, `Community 1`, `Community 5`, `Community 6`, `Community 42`, `Community 16`, `Community 21`, `Community 24`, `Community 31`?**
  _High betweenness centrality (0.075) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.06376726417866588 - nodes in this community are weakly interconnected._