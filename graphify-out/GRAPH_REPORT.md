# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1189 nodes · 3799 edges · 38 communities (26 shown, 12 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 476 edges (avg confidence: 0.85)
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
- Community 25
- Community 26
- Community 27
- Community 29
- Community 30
- Community 31
- Community 33
- Community 35
- Community 36
- Community 37

## God Nodes (most connected - your core abstractions)
1. `t()` - 125 edges
2. `Video` - 56 edges
3. `Col` - 49 edges
4. `Project` - 49 edges
5. `Seo` - 46 edges
6. `text()` - 41 edges
7. `toast()` - 41 edges
8. `TtsActivity` - 37 edges
9. `overviewTab()` - 33 edges
10. `dp()` - 33 edges

## Surprising Connections (you probably didn't know these)
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt

## Import Cycles
- None detected.

## Communities (38 total, 12 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (17): onCreate(), ChannelData, ChannelExplorer, ChannelFetch, ChannelStats, ChannelStore, Config, PlaylistRef (+9 more)

### Community 1 - "Community 1"
Cohesion: 0.05
Nodes (17): Analytics, Bucket, ChannelStat, Compare, Stats, BarChartView, ChartDraw, Export (+9 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (24): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+16 more)

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (9): EdgeTts, WebSocketListener, Forbidden, TtsException, TtsOptions, TtsPrefs, TtsText, TtsVoice (+1 more)

### Community 4 - "Community 4"
Cohesion: 0.08
Nodes (4): Project, Seo, SeoChecklist, TopicActivity

### Community 5 - "Community 5"
Cohesion: 0.09
Nodes (13): RecBridge, load(), newId(), t(), LumenActivity, ProjectActivity, startRecs(), seoTab() (+5 more)

### Community 6 - "Community 6"
Cohesion: 0.06
Nodes (8): L, LumenCrashHandler, CollectService, CollectState, Monitoring, MonitorJob, Notify, LogRedactTest

### Community 7 - "Community 7"
Cohesion: 0.07
Nodes (14): strings(), ImportParser, Parsed, JsonSalvage, AnalyticsTest, FmtTest, ImportParserTest, LlmParseTest (+6 more)

### Community 8 - "Community 8"
Cohesion: 0.07
Nodes (10): BrowserText, filtersSummary(), glyphIcon(), glyphLabel(), LangGuess, Regions, WatchFilter, BackupTest (+2 more)

### Community 9 - "Community 9"
Cohesion: 0.10
Nodes (3): TtsActivity, TextWatcher, OnSeekBarChangeListener

### Community 10 - "Community 10"
Cohesion: 0.07
Nodes (3): GraphActivity, GraphView, KeyVault

### Community 11 - "Community 11"
Cohesion: 0.08
Nodes (7): chipRow(), LumenBase, Palette, rowOf(), scrollPage(), ArrayAdapter, Type

### Community 12 - "Community 12"
Cohesion: 0.21
Nodes (9): filtersEditor(), channelRows(), channelsTab(), overviewTab(), recsTab(), btn(), chk(), Col (+1 more)

### Community 13 - "Community 13"
Cohesion: 0.12
Nodes (5): AutoBackup, NewProjectActivity, restoreFrom(), SimpleWatcher, WizardActivity

### Community 14 - "Community 14"
Cohesion: 0.13
Nodes (5): Fmt, recRow(), state(), Thumbs, VideoActivity

### Community 15 - "Community 15"
Cohesion: 0.14
Nodes (5): GraphAlgo, Item, Msg, Voc, SeoGraphTest

### Community 16 - "Community 16"
Cohesion: 0.11
Nodes (6): CollectConfig, MonitorConfig, RecConfig, RunRecord, SeoContext, toJsonArray()

### Community 17 - "Community 17"
Cohesion: 0.09
Nodes (15): Ic, ADD, BACK, BUG, COPY, DELETE, FIT, JOURNAL (+7 more)

### Community 19 - "Community 19"
Cohesion: 0.27
Nodes (9): chip(), dp(), edt(), iconBtn(), prompt(), ripple(), roundBg(), spin() (+1 more)

### Community 20 - "Community 20"
Cohesion: 0.13
Nodes (4): isLocalSource(), Lumen, LumenEnv, Settings

### Community 21 - "Community 21"
Cohesion: 0.23
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 22 - "Community 22"
Cohesion: 0.15
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 26 - "Community 26"
Cohesion: 0.27
Nodes (3): Link, LinkActivity, YtLink

### Community 29 - "Community 29"
Cohesion: 0.42
Nodes (3): QuickAnalysis, QuickState, Step

### Community 30 - "Community 30"
Cohesion: 0.50
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo()

### Community 36 - "Community 36"
Cohesion: 0.40
Nodes (5): BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **28 isolated node(s):** `ADD`, `BACK`, `BUG`, `COPY`, `DELETE` (+23 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 135 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **12 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 5` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 12`, `Community 13`, `Community 14`, `Community 19`, `Community 20`, `Community 21`, `Community 25`, `Community 26`, `Community 27`, `Community 29`, `Community 30`, `Community 31`?**
  _High betweenness centrality (0.358) - this node is a cross-community bridge._
- **Are the 124 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 124 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ADD`, `BACK`, `BUG` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05396825396825397 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 1` to `Community 0`, `Community 2`, `Community 4`, `Community 5`, `Community 7`, `Community 8`, `Community 14`, `Community 15`, `Community 16`, `Community 23`, `Community 29`?**
  _High betweenness centrality (0.051) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.0506155950752394 - nodes in this community are weakly interconnected._