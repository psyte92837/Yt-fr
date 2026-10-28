# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1041 nodes · 3382 edges · 37 communities (26 shown, 11 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 435 edges (avg confidence: 0.85)
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
- Community 28
- Community 29
- Community 30
- Community 31
- Community 32
- Community 33
- Community 34
- Community 35
- Community 36

## God Nodes (most connected - your core abstractions)
1. `t()` - 107 edges
2. `Video` - 56 edges
3. `Project` - 49 edges
4. `Col` - 48 edges
5. `Seo` - 46 edges
6. `text()` - 39 edges
7. `toast()` - 36 edges
8. `overviewTab()` - 33 edges
9. `dp()` - 32 edges
10. `ProjectActivity` - 31 edges

## Surprising Connections (you probably didn't know these)
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt

## Import Cycles
- None detected.

## Communities (37 total, 11 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (14): ChannelExplorer, Config, Relevance, RecConfig, Ctx, DepthStat, RecEdge, RecEngine (+6 more)

### Community 1 - "Community 1"
Cohesion: 0.07
Nodes (9): load(), newId(), RunRecord, ProjectActivity, CollectService, Monitoring, Notify, Store (+1 more)

### Community 2 - "Community 2"
Cohesion: 0.07
Nodes (14): onCreate(), ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result, Link (+6 more)

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (13): filtersSummary(), CollectConfig, Comment, MonitorConfig, SearchFilters, SeoContext, strings(), toJsonArray() (+5 more)

### Community 4 - "Community 4"
Cohesion: 0.11
Nodes (11): AiTasks, Analytics, Bucket, ChannelStat, Compare, Stats, sha1(), Prompts (+3 more)

### Community 6 - "Community 6"
Cohesion: 0.07
Nodes (7): Chunks, JsonText, Vtt, Words, YtQuery, YouTube, TextTest

### Community 7 - "Community 7"
Cohesion: 0.09
Nodes (4): BarChartView, ChartDraw, Export, Csv

### Community 8 - "Community 8"
Cohesion: 0.07
Nodes (3): GraphActivity, GraphView, KeyVault

### Community 9 - "Community 9"
Cohesion: 0.06
Nodes (11): BtnStyle, FILLED, OUTLINED, TEXT, TONAL, chipRow(), LumenBase, Palette (+3 more)

### Community 10 - "Community 10"
Cohesion: 0.10
Nodes (5): AiJournal, AiJournalActivity, Entry, L, LumenCrashHandler

### Community 11 - "Community 11"
Cohesion: 0.15
Nodes (8): t(), startRecs(), seoTab(), SettingsActivity, confirm(), openUrl(), shareText(), toast()

### Community 12 - "Community 12"
Cohesion: 0.13
Nodes (6): GraphAlgo, Item, Msg, Voc, LogRedactTest, SeoGraphTest

### Community 13 - "Community 13"
Cohesion: 0.17
Nodes (5): Llm, LlmException, LlmReply, LlmParse, LlmParseTest

### Community 14 - "Community 14"
Cohesion: 0.15
Nodes (7): JsonSalvage, AnalyticsTest, FmtTest, QuickAndChannelsTest, SeoPureTest, TruncationTest, video()

### Community 15 - "Community 15"
Cohesion: 0.20
Nodes (5): filtersEditor(), channelRows(), channelsTab(), chk(), Col

### Community 16 - "Community 16"
Cohesion: 0.15
Nodes (5): LumenActivity, NewProjectActivity, restoreFrom(), SimpleWatcher, WizardActivity

### Community 17 - "Community 17"
Cohesion: 0.32
Nodes (12): btn(), chip(), dp(), edt(), iconBtn(), prompt(), ripple(), roundBg() (+4 more)

### Community 18 - "Community 18"
Cohesion: 0.09
Nodes (14): Ic, ADD, BACK, BUG, COPY, DELETE, FIT, JOURNAL (+6 more)

### Community 20 - "Community 20"
Cohesion: 0.15
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 21 - "Community 21"
Cohesion: 0.13
Nodes (4): isLocalSource(), Lumen, LumenEnv, Settings

### Community 24 - "Community 24"
Cohesion: 0.23
Nodes (4): audienceTab(), confidenceLabel(), insightCard(), vocLabel()

### Community 25 - "Community 25"
Cohesion: 0.20
Nodes (5): recRow(), recsTab(), state(), rowOf(), Thumbs

### Community 26 - "Community 26"
Cohesion: 0.21
Nodes (5): BrowserText, TabRecJs, glyphIcon(), glyphLabel(), BrowserUiTest

### Community 27 - "Community 27"
Cohesion: 0.14
Nodes (13): ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Provider, ANTHROPIC, CUSTOM, DEEPSEEK (+5 more)

### Community 31 - "Community 31"
Cohesion: 0.36
Nodes (3): Fmt, overviewTab(), openVideo()

### Community 33 - "Community 33"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

## Knowledge Gaps
- **27 isolated node(s):** `ADD`, `BACK`, `BUG`, `COPY`, `DELETE` (+22 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 119 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 11` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 7`, `Community 8`, `Community 14`, `Community 15`, `Community 16`, `Community 17`, `Community 21`, `Community 22`, `Community 23`, `Community 24`, `Community 25`, `Community 26`, `Community 29`, `Community 31`, `Community 34`, `Community 35`?**
  _High betweenness centrality (0.260) - this node is a cross-community bridge._
- **Are the 106 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 106 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ADD`, `BACK`, `BUG` to the rest of the system?**
  _27 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.060814687320711415 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 4` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 5`, `Community 6`, `Community 7`, `Community 12`, `Community 14`, `Community 31`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.07043167802661474 - nodes in this community are weakly interconnected._