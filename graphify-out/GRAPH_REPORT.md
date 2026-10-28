# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 995 nodes · 3233 edges · 43 communities (28 shown, 15 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 423 edges (avg confidence: 0.85)
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
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 39
- Community 40
- Community 42

## God Nodes (most connected - your core abstractions)
1. `t()` - 106 edges
2. `Video` - 52 edges
3. `Project` - 48 edges
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
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt

## Import Cycles
- None detected.

## Communities (43 total, 15 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.08
Nodes (14): load(), newId(), t(), ProjectActivity, startRecs(), seoTab(), Monitoring, SettingsActivity (+6 more)

### Community 1 - "Community 1"
Cohesion: 0.07
Nodes (12): ChannelExplorer, Config, Relevance, Ctx, DepthStat, RecEdge, RecEngine, RecGraph (+4 more)

### Community 2 - "Community 2"
Cohesion: 0.09
Nodes (4): Project, Seo, SeoChecklist, TopicActivity

### Community 3 - "Community 3"
Cohesion: 0.08
Nodes (23): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+15 more)

### Community 4 - "Community 4"
Cohesion: 0.05
Nodes (5): BarChartView, ChartDraw, GraphActivity, GraphView, KeyVault

### Community 5 - "Community 5"
Cohesion: 0.07
Nodes (6): L, LumenCrashHandler, CollectService, CollectState, MonitorJob, Notify

### Community 6 - "Community 6"
Cohesion: 0.07
Nodes (13): Analytics, Bucket, ChannelStat, Compare, Stats, Export, Chunks, Csv (+5 more)

### Community 7 - "Community 7"
Cohesion: 0.07
Nodes (18): BrowserText, glyphIcon(), glyphLabel(), Ic, ADD, BACK, BUG, COPY (+10 more)

### Community 8 - "Community 8"
Cohesion: 0.08
Nodes (4): Palette, scrollPage(), ArrayAdapter, Type

### Community 9 - "Community 9"
Cohesion: 0.22
Nodes (6): filtersEditor(), channelRows(), channelsTab(), overviewTab(), Col, text()

### Community 10 - "Community 10"
Cohesion: 0.13
Nodes (6): GraphAlgo, Item, Msg, Voc, LogRedactTest, SeoGraphTest

### Community 11 - "Community 11"
Cohesion: 0.12
Nodes (6): filtersSummary(), LumenActivity, NewProjectActivity, SimpleWatcher, WizardActivity, LumenBase

### Community 12 - "Community 12"
Cohesion: 0.23
Nodes (12): chip(), chipRow(), chk(), dp(), edt(), iconBtn(), prompt(), ripple() (+4 more)

### Community 13 - "Community 13"
Cohesion: 0.15
Nodes (3): Fmt, Thumbs, VideoActivity

### Community 14 - "Community 14"
Cohesion: 0.13
Nodes (5): MonitorConfig, RecConfig, RunRecord, SeoContext, toJsonArray()

### Community 16 - "Community 16"
Cohesion: 0.22
Nodes (4): ChannelData, ChannelFetch, ChannelStore, PlaylistRef

### Community 17 - "Community 17"
Cohesion: 0.20
Nodes (3): CollectConfig, SearchFilters, YouTube

### Community 18 - "Community 18"
Cohesion: 0.22
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 20 - "Community 20"
Cohesion: 0.14
Nodes (4): isLocalSource(), Lumen, LumenEnv, Settings

### Community 21 - "Community 21"
Cohesion: 0.17
Nodes (3): FmtTest, LlmParseTest, SeoPureTest

### Community 22 - "Community 22"
Cohesion: 0.21
Nodes (4): LinkActivity, QuickAnalysis, QuickState, Step

### Community 23 - "Community 23"
Cohesion: 0.20
Nodes (3): AnalyticsTest, QuickAndChannelsTest, video()

### Community 24 - "Community 24"
Cohesion: 0.19
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 27 - "Community 27"
Cohesion: 0.24
Nodes (3): Comment, strings(), ModelsTest

### Community 28 - "Community 28"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

### Community 29 - "Community 29"
Cohesion: 0.50
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo()

### Community 31 - "Community 31"
Cohesion: 0.36
Nodes (3): recRow(), recsTab(), state()

### Community 33 - "Community 33"
Cohesion: 0.33
Nodes (6): btn(), BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **27 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+22 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 123 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **15 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 9`, `Community 11`, `Community 12`, `Community 13`, `Community 16`, `Community 18`, `Community 20`, `Community 21`, `Community 22`, `Community 25`, `Community 26`, `Community 29`, `Community 30`, `Community 31`?**
  _High betweenness centrality (0.248) - this node is a cross-community bridge._
- **Are the 105 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 105 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _27 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.07660738714090287 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 3` to `Community 0`, `Community 1`, `Community 2`, `Community 37`, `Community 6`, `Community 13`, `Community 14`, `Community 16`, `Community 17`, `Community 21`, `Community 22`, `Community 23`, `Community 27`?**
  _High betweenness centrality (0.059) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.07099099099099099 - nodes in this community are weakly interconnected._