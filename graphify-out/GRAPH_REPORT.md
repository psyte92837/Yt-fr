# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1020 nodes · 3307 edges · 38 communities (24 shown, 14 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 428 edges (avg confidence: 0.85)
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
- Community 32
- Community 34
- Community 35
- Community 36
- Community 37

## God Nodes (most connected - your core abstractions)
1. `t()` - 106 edges
2. `Video` - 56 edges
3. `Project` - 49 edges
4. `Col` - 48 edges
5. `Seo` - 46 edges
6. `text()` - 39 edges
7. `toast()` - 34 edges
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
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `audienceTab()` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt

## Import Cycles
- None detected.

## Communities (38 total, 14 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.07
Nodes (14): load(), newId(), t(), ProjectActivity, startRecs(), seoTab(), Monitoring, SettingsActivity (+6 more)

### Community 1 - "Community 1"
Cohesion: 0.07
Nodes (12): ChannelExplorer, Config, Relevance, Ctx, DepthStat, RecEdge, RecEngine, RecGraph (+4 more)

### Community 2 - "Community 2"
Cohesion: 0.07
Nodes (23): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+15 more)

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (15): onCreate(), ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result, Link (+7 more)

### Community 4 - "Community 4"
Cohesion: 0.10
Nodes (3): Project, Seo, TopicActivity

### Community 5 - "Community 5"
Cohesion: 0.07
Nodes (4): BarChartView, ChartDraw, GraphActivity, GraphView

### Community 6 - "Community 6"
Cohesion: 0.08
Nodes (7): filtersSummary(), KeyVault, MonitorConfig, LangGuess, Regions, WatchFilter, WatchTest

### Community 7 - "Community 7"
Cohesion: 0.07
Nodes (11): BtnStyle, FILLED, OUTLINED, TEXT, TONAL, chipRow(), Palette, rowOf() (+3 more)

### Community 8 - "Community 8"
Cohesion: 0.11
Nodes (4): L, LumenCrashHandler, CollectService, Notify

### Community 9 - "Community 9"
Cohesion: 0.07
Nodes (18): BrowserText, glyphIcon(), glyphLabel(), Ic, ADD, BACK, BUG, COPY (+10 more)

### Community 10 - "Community 10"
Cohesion: 0.21
Nodes (7): filtersEditor(), channelRows(), channelsTab(), overviewTab(), recsTab(), Col, text()

### Community 11 - "Community 11"
Cohesion: 0.17
Nodes (8): Analytics, Bucket, ChannelStat, Compare, Stats, Video, AnalyticsTest, video()

### Community 13 - "Community 13"
Cohesion: 0.13
Nodes (6): GraphAlgo, Item, Msg, Voc, LogRedactTest, SeoGraphTest

### Community 14 - "Community 14"
Cohesion: 0.13
Nodes (5): LumenActivity, NewProjectActivity, SimpleWatcher, WizardActivity, LumenBase

### Community 15 - "Community 15"
Cohesion: 0.14
Nodes (4): Fmt, recRow(), state(), Thumbs

### Community 18 - "Community 18"
Cohesion: 0.28
Nodes (11): btn(), chip(), chk(), dp(), edt(), iconBtn(), prompt(), ripple() (+3 more)

### Community 19 - "Community 19"
Cohesion: 0.14
Nodes (5): CollectConfig, RecConfig, SeoContext, strings(), toJsonArray()

### Community 21 - "Community 21"
Cohesion: 0.16
Nodes (6): Chunks, JsonText, Vtt, Words, YtQuery, TextTest

### Community 22 - "Community 22"
Cohesion: 0.18
Nodes (4): FmtTest, ImportParserTest, RecGraphTest, SeoPureTest

### Community 23 - "Community 23"
Cohesion: 0.14
Nodes (4): isLocalSource(), Lumen, LumenEnv, Settings

### Community 24 - "Community 24"
Cohesion: 0.22
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 25 - "Community 25"
Cohesion: 0.16
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 29 - "Community 29"
Cohesion: 0.42
Nodes (6): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo(), showText()

### Community 30 - "Community 30"
Cohesion: 0.39
Nodes (3): Comment, ImportParser, Parsed

## Knowledge Gaps
- **27 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+22 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 123 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **14 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 8`, `Community 9`, `Community 10`, `Community 11`, `Community 14`, `Community 15`, `Community 16`, `Community 18`, `Community 20`, `Community 22`, `Community 23`, `Community 24`, `Community 26`, `Community 29`, `Community 31`, `Community 34`?**
  _High betweenness centrality (0.233) - this node is a cross-community bridge._
- **Are the 105 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 105 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _27 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.07471264367816093 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 11` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 6`, `Community 12`, `Community 15`, `Community 16`, `Community 19`?**
  _High betweenness centrality (0.078) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.07229832572298325 - nodes in this community are weakly interconnected._