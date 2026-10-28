# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1190 nodes · 3805 edges · 49 communities (32 shown, 17 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 477 edges (avg confidence: 0.85)
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
- Community 38
- Community 39
- Community 40
- Community 41
- Community 43
- Community 44
- Community 45
- Community 46
- Community 47
- Community 48

## God Nodes (most connected - your core abstractions)
1. `t()` - 126 edges
2. `Video` - 56 edges
3. `Col` - 49 edges
4. `Project` - 49 edges
5. `Seo` - 46 edges
6. `toast()` - 41 edges
7. `text()` - 41 edges
8. `TtsActivity` - 37 edges
9. `dp()` - 33 edges
10. `overviewTab()` - 33 edges

## Surprising Connections (you probably didn't know these)
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `audienceTab()` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt

## Import Cycles
- None detected.

## Communities (49 total, 17 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (11): load(), newId(), t(), ProjectActivity, startRecs(), Store, TtsActivity, TextWatcher (+3 more)

### Community 1 - "Community 1"
Cohesion: 0.06
Nodes (9): EdgeTts, WebSocketListener, Forbidden, TtsException, TtsOptions, TtsPrefs, TtsText, TtsVoice (+1 more)

### Community 2 - "Community 2"
Cohesion: 0.09
Nodes (5): Project, Seo, SeoChecklist, seoTab(), TopicActivity

### Community 3 - "Community 3"
Cohesion: 0.08
Nodes (22): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+14 more)

### Community 4 - "Community 4"
Cohesion: 0.07
Nodes (4): BarChartView, ChartDraw, GraphActivity, GraphView

### Community 5 - "Community 5"
Cohesion: 0.08
Nodes (6): filtersSummary(), KeyVault, LangGuess, Regions, WatchFilter, WatchTest

### Community 6 - "Community 6"
Cohesion: 0.10
Nodes (8): AnalyticsTest, FmtTest, LlmParseTest, ModelsTest, QuickAndChannelsTest, RecGraphTest, SeoPureTest, video()

### Community 7 - "Community 7"
Cohesion: 0.09
Nodes (5): CollectService, CollectState, Monitoring, MonitorJob, Notify

### Community 8 - "Community 8"
Cohesion: 0.10
Nodes (6): AiJournal, AiJournalActivity, Entry, L, LumenCrashHandler, LogRedactTest

### Community 9 - "Community 9"
Cohesion: 0.07
Nodes (19): BrowserText, glyphIcon(), glyphLabel(), Ic, ADD, BACK, BUG, COPY (+11 more)

### Community 10 - "Community 10"
Cohesion: 0.09
Nodes (8): chipRow(), Palette, ripple(), rowOf(), scrollPage(), ArrayAdapter, tabBar(), Type

### Community 11 - "Community 11"
Cohesion: 0.14
Nodes (8): LumenActivity, NewProjectActivity, restoreFrom(), WizardActivity, iconBtn(), LumenBase, showText(), text()

### Community 12 - "Community 12"
Cohesion: 0.14
Nodes (6): ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result

### Community 13 - "Community 13"
Cohesion: 0.19
Nodes (3): Ctx, DepthStat, RecEngine

### Community 14 - "Community 14"
Cohesion: 0.14
Nodes (5): GraphAlgo, Item, Msg, Voc, SeoGraphTest

### Community 15 - "Community 15"
Cohesion: 0.11
Nodes (5): SimpleWatcher, recRow(), recsTab(), state(), Thumbs

### Community 16 - "Community 16"
Cohesion: 0.19
Nodes (4): filtersEditor(), SettingsActivity, chk(), Col

### Community 17 - "Community 17"
Cohesion: 0.14
Nodes (3): YtQuery, BackupTest, TextTest

### Community 18 - "Community 18"
Cohesion: 0.12
Nodes (6): CollectConfig, RecConfig, RunRecord, SeoContext, strings(), toJsonArray()

### Community 20 - "Community 20"
Cohesion: 0.23
Nodes (6): Analytics, Bucket, ChannelStat, Compare, Stats, Video

### Community 21 - "Community 21"
Cohesion: 0.13
Nodes (3): RecKeepAlive, TabRecJs, TabWriter

### Community 22 - "Community 22"
Cohesion: 0.19
Nodes (11): btn(), BtnStyle, FILLED, OUTLINED, TEXT, TONAL, dp(), edt() (+3 more)

### Community 23 - "Community 23"
Cohesion: 0.13
Nodes (4): isLocalSource(), Lumen, LumenEnv, Settings

### Community 24 - "Community 24"
Cohesion: 0.24
Nodes (6): Fmt, channelRows(), channelsTab(), overviewTab(), chip(), VideoActivity

### Community 25 - "Community 25"
Cohesion: 0.20
Nodes (3): RecEdge, RecGraph, RecNode

### Community 26 - "Community 26"
Cohesion: 0.16
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 31 - "Community 31"
Cohesion: 0.23
Nodes (4): Chunks, JsonText, Vtt, Words

### Community 34 - "Community 34"
Cohesion: 0.29
Nodes (4): Comment, ImportParser, Parsed, ImportParserTest

### Community 35 - "Community 35"
Cohesion: 0.24
Nodes (3): Link, LinkActivity, YtLink

### Community 36 - "Community 36"
Cohesion: 0.38
Nodes (3): QuickAnalysis, QuickState, Step

### Community 38 - "Community 38"
Cohesion: 0.27
Nodes (3): ChannelExplorer, Config, TopicFilterTest

### Community 40 - "Community 40"
Cohesion: 0.50
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo()

## Knowledge Gaps
- **28 isolated node(s):** `FILLED`, `OUTLINED`, `TEXT`, `TONAL`, `ANTHROPIC` (+23 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 135 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **17 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 11`, `Community 12`, `Community 13`, `Community 15`, `Community 16`, `Community 20`, `Community 21`, `Community 22`, `Community 23`, `Community 24`, `Community 27`, `Community 30`, `Community 33`, `Community 36`, `Community 38`, `Community 39`, `Community 40`?**
  _High betweenness centrality (0.325) - this node is a cross-community bridge._
- **Are the 125 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 125 INFERRED edges - model-reasoned connections that need verification._
- **What connects `FILLED`, `OUTLINED`, `TEXT` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05202135774218154 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 20` to `Community 0`, `Community 2`, `Community 3`, `Community 36`, `Community 5`, `Community 6`, `Community 41`, `Community 12`, `Community 13`, `Community 14`, `Community 18`, `Community 24`, `Community 27`, `Community 28`?**
  _High betweenness centrality (0.064) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.059466848940533154 - nodes in this community are weakly interconnected._