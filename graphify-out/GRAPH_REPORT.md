# Graph Report - src  (2026-10-25)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 854 nodes · 2839 edges · 28 communities (20 shown, 8 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 379 edges (avg confidence: 0.85)
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
- Community 25
- Community 26

## God Nodes (most connected - your core abstractions)
1. `t()` - 94 edges
2. `Video` - 52 edges
3. `Project` - 46 edges
4. `Seo` - 46 edges
5. `Col` - 45 edges
6. `text()` - 35 edges
7. `overviewTab()` - 33 edges
8. `toast()` - 31 edges
9. `ProjectActivity` - 30 edges
10. `audienceTab()` - 30 edges

## Surprising Connections (you probably didn't know these)
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt

## Import Cycles
- None detected.

## Communities (28 total, 8 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (15): Analytics, Bucket, ChannelStat, Compare, Stats, SearchFilters, Video, Chunks (+7 more)

### Community 1 - "Community 1"
Cohesion: 0.08
Nodes (15): AiTasks, sha1(), LlmException, Prompts, AiResult, CollectConfig, MonitorConfig, Project (+7 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (7): L, LumenCrashHandler, CollectService, CollectState, Monitoring, MonitorJob, Notify

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (9): isLocalSource(), Export, GraphAlgo, Csv, Item, Msg, Voc, LogRedactTest (+1 more)

### Community 4 - "Community 4"
Cohesion: 0.11
Nodes (10): load(), newId(), t(), LumenActivity, ProjectActivity, startRecs(), seoTab(), Store (+2 more)

### Community 5 - "Community 5"
Cohesion: 0.07
Nodes (14): onCreate(), ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result, Link (+6 more)

### Community 6 - "Community 6"
Cohesion: 0.06
Nodes (5): BarChartView, ChartDraw, GraphActivity, GraphView, KeyVault

### Community 7 - "Community 7"
Cohesion: 0.11
Nodes (9): ChannelExplorer, Config, Relevance, Ctx, DepthStat, RecEdge, RecEngine, RecGraph (+1 more)

### Community 9 - "Community 9"
Cohesion: 0.10
Nodes (14): chip(), chipRow(), chk(), dp(), edt(), prompt(), ripple(), roundBg() (+6 more)

### Community 10 - "Community 10"
Cohesion: 0.25
Nodes (6): channelRows(), channelsTab(), overviewTab(), recsTab(), Col, text()

### Community 11 - "Community 11"
Cohesion: 0.11
Nodes (3): Lumen, LumenEnv, Settings

### Community 12 - "Community 12"
Cohesion: 0.13
Nodes (5): Fmt, recRow(), state(), Thumbs, VideoActivity

### Community 13 - "Community 13"
Cohesion: 0.16
Nodes (13): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), btn(), BtnStyle, FILLED, OUTLINED (+5 more)

### Community 14 - "Community 14"
Cohesion: 0.13
Nodes (5): filtersEditor(), NewProjectActivity, SimpleWatcher, WizardActivity, LumenBase

### Community 15 - "Community 15"
Cohesion: 0.20
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 17 - "Community 17"
Cohesion: 0.18
Nodes (10): Llm, Provider, ANTHROPIC, CUSTOM, DEEPSEEK, GEMINI, GROQ, OLLAMA (+2 more)

### Community 18 - "Community 18"
Cohesion: 0.18
Nodes (4): LlmReply, LlmParse, LlmParseTest, TruncationTest

### Community 19 - "Community 19"
Cohesion: 0.20
Nodes (5): AnalyticsTest, FmtTest, QuickAndChannelsTest, SeoPureTest, video()

### Community 20 - "Community 20"
Cohesion: 0.15
Nodes (5): ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, SeoChecklist

### Community 21 - "Community 21"
Cohesion: 0.24
Nodes (4): Comment, ImportParser, Parsed, ImportParserTest

## Knowledge Gaps
- **15 isolated node(s):** `FILLED`, `OUTLINED`, `TEXT`, `TONAL`, `ANTHROPIC` (+10 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 100 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **8 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 4` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 5`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 11`, `Community 12`, `Community 13`, `Community 14`, `Community 15`, `Community 16`, `Community 19`, `Community 22`?**
  _High betweenness centrality (0.223) - this node is a cross-community bridge._
- **Are the 93 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 93 INFERRED edges - model-reasoned connections that need verification._
- **What connects `FILLED`, `OUTLINED`, `TEXT` to the rest of the system?**
  _15 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05578947368421053 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 0` to `Community 1`, `Community 3`, `Community 4`, `Community 5`, `Community 7`, `Community 8`, `Community 12`, `Community 19`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.07971014492753623 - nodes in this community are weakly interconnected._