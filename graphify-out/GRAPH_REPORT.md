# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 995 nodes · 3223 edges · 34 communities (25 shown, 9 thin omitted)
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
- Community 29
- Community 31

## God Nodes (most connected - your core abstractions)
1. `t()` - 109 edges
2. `Video` - 52 edges
3. `Project` - 48 edges
4. `Col` - 48 edges
5. `Seo` - 46 edges
6. `text()` - 39 edges
7. `toast()` - 37 edges
8. `overviewTab()` - 33 edges
9. `dp()` - 32 edges
10. `ProjectActivity` - 31 edges

## Surprising Connections (you probably didn't know these)
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `audienceTab()` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt

## Import Cycles
- None detected.

## Communities (34 total, 9 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (16): AiJournal, AiJournalActivity, Entry, WebViewClient, TabAudioService, Callback, load(), t() (+8 more)

### Community 1 - "Community 1"
Cohesion: 0.09
Nodes (5): Project, Seo, SeoChecklist, seoTab(), TopicActivity

### Community 2 - "Community 2"
Cohesion: 0.07
Nodes (12): ChannelExplorer, Config, Relevance, Ctx, DepthStat, RecEdge, RecEngine, RecGraph (+4 more)

### Community 3 - "Community 3"
Cohesion: 0.08
Nodes (23): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+15 more)

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (14): onCreate(), ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result, Link (+6 more)

### Community 5 - "Community 5"
Cohesion: 0.07
Nodes (4): Export, Monitoring, Store, Csv

### Community 6 - "Community 6"
Cohesion: 0.07
Nodes (6): L, LumenCrashHandler, CollectService, CollectState, MonitorJob, Notify

### Community 7 - "Community 7"
Cohesion: 0.07
Nodes (8): SearchFilters, Chunks, JsonText, Vtt, Words, YtQuery, YouTube, TextTest

### Community 8 - "Community 8"
Cohesion: 0.07
Nodes (7): chipRow(), Palette, rowOf(), scrollPage(), ArrayAdapter, OnItemSelectedListener, Type

### Community 9 - "Community 9"
Cohesion: 0.07
Nodes (19): BrowserText, filtersSummary(), glyphIcon(), glyphLabel(), Ic, ADD, BACK, BUG (+11 more)

### Community 10 - "Community 10"
Cohesion: 0.13
Nodes (6): GraphAlgo, Item, Msg, Voc, LogRedactTest, SeoGraphTest

### Community 11 - "Community 11"
Cohesion: 0.13
Nodes (6): AnalyticsTest, FmtTest, LlmParseTest, QuickAndChannelsTest, SeoPureTest, video()

### Community 13 - "Community 13"
Cohesion: 0.12
Nodes (3): BarChartView, ChartDraw, KeyVault

### Community 14 - "Community 14"
Cohesion: 0.12
Nodes (6): CollectConfig, MonitorConfig, RecConfig, RunRecord, SeoContext, toJsonArray()

### Community 15 - "Community 15"
Cohesion: 0.23
Nodes (10): chip(), dp(), edt(), iconBtn(), prompt(), ripple(), roundBg(), spin() (+2 more)

### Community 16 - "Community 16"
Cohesion: 0.18
Nodes (6): Fmt, channelRows(), channelsTab(), overviewTab(), Thumbs, VideoActivity

### Community 18 - "Community 18"
Cohesion: 0.16
Nodes (9): filtersEditor(), btn(), BtnStyle, FILLED, OUTLINED, TEXT, TONAL, chk() (+1 more)

### Community 19 - "Community 19"
Cohesion: 0.25
Nodes (5): Analytics, Bucket, ChannelStat, Compare, Stats

### Community 20 - "Community 20"
Cohesion: 0.13
Nodes (5): isLocalSource(), Lumen, LumenEnv, newId(), Settings

### Community 21 - "Community 21"
Cohesion: 0.18
Nodes (4): LumenActivity, NewProjectActivity, WizardActivity, LumenBase

### Community 22 - "Community 22"
Cohesion: 0.17
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 23 - "Community 23"
Cohesion: 0.21
Nodes (4): SimpleWatcher, recRow(), recsTab(), state()

### Community 24 - "Community 24"
Cohesion: 0.24
Nodes (3): Comment, strings(), ModelsTest

### Community 25 - "Community 25"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

### Community 26 - "Community 26"
Cohesion: 0.50
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo()

## Knowledge Gaps
- **27 isolated node(s):** `FILLED`, `OUTLINED`, `TEXT`, `TONAL`, `ANTHROPIC` (+22 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 130 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **9 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 9`, `Community 11`, `Community 12`, `Community 15`, `Community 16`, `Community 18`, `Community 19`, `Community 20`, `Community 21`, `Community 23`, `Community 26`?**
  _High betweenness centrality (0.254) - this node is a cross-community bridge._
- **Are the 108 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 108 INFERRED edges - model-reasoned connections that need verification._
- **What connects `FILLED`, `OUTLINED`, `TEXT` to the rest of the system?**
  _27 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.051433251433251434 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 3` to `Community 0`, `Community 1`, `Community 2`, `Community 4`, `Community 5`, `Community 7`, `Community 10`, `Community 11`, `Community 14`, `Community 16`, `Community 19`, `Community 24`?**
  _High betweenness centrality (0.059) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.0852359208523592 - nodes in this community are weakly interconnected._