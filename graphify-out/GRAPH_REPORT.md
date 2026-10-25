# Graph Report - src  (2026-10-25)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 879 nodes · 2925 edges · 20 communities (18 shown, 2 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 386 edges (avg confidence: 0.85)
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

## God Nodes (most connected - your core abstractions)
1. `t()` - 95 edges
2. `Video` - 52 edges
3. `Project` - 48 edges
4. `Seo` - 46 edges
5. `Col` - 45 edges
6. `text()` - 35 edges
7. `overviewTab()` - 33 edges
8. `toast()` - 31 edges
9. `ProjectActivity` - 30 edges
10. `audienceTab()` - 30 edges

## Surprising Connections (you probably didn't know these)
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt

## Import Cycles
- None detected.

## Communities (20 total, 2 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (26): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+18 more)

### Community 1 - "Community 1"
Cohesion: 0.07
Nodes (11): ChannelExplorer, Config, Ctx, DepthStat, RecEdge, RecEngine, RecGraph, RecNode (+3 more)

### Community 2 - "Community 2"
Cohesion: 0.05
Nodes (12): newId(), CollectConfig, Comment, MonitorConfig, RecConfig, RunRecord, SeoContext, strings() (+4 more)

### Community 3 - "Community 3"
Cohesion: 0.05
Nodes (12): AiJournal, AiJournalActivity, Entry, LumenActivity, NewProjectActivity, SimpleWatcher, WizardActivity, L (+4 more)

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (9): isLocalSource(), Lumen, LumenEnv, Settings, CollectService, CollectState, Monitoring, MonitorJob (+1 more)

### Community 5 - "Community 5"
Cohesion: 0.07
Nodes (9): SearchFilters, Link, LinkActivity, QuickAnalysis, QuickConfig, QuickState, Step, YtLink (+1 more)

### Community 6 - "Community 6"
Cohesion: 0.05
Nodes (5): BarChartView, ChartDraw, GraphActivity, GraphView, KeyVault

### Community 7 - "Community 7"
Cohesion: 0.07
Nodes (14): Analytics, Bucket, ChannelStat, Compare, Stats, Export, Chunks, Csv (+6 more)

### Community 8 - "Community 8"
Cohesion: 0.07
Nodes (11): ImportParser, Parsed, AnalyticsTest, FmtTest, ImportParserTest, LlmParseTest, QuickAndChannelsTest, RecGraphTest (+3 more)

### Community 9 - "Community 9"
Cohesion: 0.10
Nodes (17): btn(), chip(), chipRow(), chk(), dp(), edt(), prompt(), ripple() (+9 more)

### Community 10 - "Community 10"
Cohesion: 0.17
Nodes (5): load(), ProjectActivity, seoTab(), confirm(), toast()

### Community 11 - "Community 11"
Cohesion: 0.11
Nodes (7): onCreate(), ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result

### Community 12 - "Community 12"
Cohesion: 0.11
Nodes (7): GraphAlgo, Relevance, Item, Msg, Voc, LogRedactTest, SeoGraphTest

### Community 13 - "Community 13"
Cohesion: 0.21
Nodes (5): filtersEditor(), channelRows(), channelsTab(), Col, text()

### Community 14 - "Community 14"
Cohesion: 0.18
Nodes (7): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), t(), SettingsActivity, openUrl()

### Community 16 - "Community 16"
Cohesion: 0.15
Nodes (4): recRow(), recsTab(), state(), Thumbs

### Community 17 - "Community 17"
Cohesion: 0.31
Nodes (4): Fmt, overviewTab(), openVideo(), VideoActivity

### Community 18 - "Community 18"
Cohesion: 0.40
Nodes (5): BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **15 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+10 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 101 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 14` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 11`, `Community 13`, `Community 15`, `Community 16`, `Community 17`?**
  _High betweenness centrality (0.222) - this node is a cross-community bridge._
- **Are the 94 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 94 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _15 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.052556615866685655 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 0` to `Community 1`, `Community 2`, `Community 5`, `Community 7`, `Community 8`, `Community 10`, `Community 11`, `Community 12`, `Community 17`?**
  _High betweenness centrality (0.072) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.07120500782472614 - nodes in this community are weakly interconnected._