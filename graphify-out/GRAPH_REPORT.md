# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1259 nodes · 4057 edges · 53 communities (28 shown, 25 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 509 edges (avg confidence: 0.85)
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
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 40
- Community 41
- Community 43
- Community 44
- Community 45
- Community 46
- Community 47
- Community 48
- Community 49
- Community 50
- Community 51
- Community 52

## God Nodes (most connected - your core abstractions)
1. `t()` - 134 edges
2. `Video` - 60 edges
3. `Col` - 50 edges
4. `Project` - 50 edges
5. `Seo` - 46 edges
6. `toast()` - 44 edges
7. `text()` - 43 edges
8. `TtsActivity` - 37 edges
9. `overviewTab()` - 35 edges
10. `render()` - 34 edges

## Surprising Connections (you probably didn't know these)
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `filtersSummary()` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt
- `restoreFrom()` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt

## Import Cycles
- None detected.

## Communities (53 total, 25 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (47): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), load(), render(), Fmt, isLocalSource() (+39 more)

### Community 1 - "Community 1"
Cohesion: 0.06
Nodes (17): AiTasks, Analytics, Bucket, ChannelStat, Compare, Stats, newId(), sha1() (+9 more)

### Community 2 - "Community 2"
Cohesion: 0.05
Nodes (8): AutoBackup, L, LumenCrashHandler, CollectService, CollectState, Monitoring, MonitorJob, Notify

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (9): EdgeTts, WebSocketListener, Forbidden, TtsException, TtsOptions, TtsPrefs, TtsText, TtsVoice (+1 more)

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (11): BarChartView, ChartDraw, Export, Chunks, Csv, JsonText, ThumbUrl, Vtt (+3 more)

### Community 5 - "Community 5"
Cohesion: 0.09
Nodes (3): TtsActivity, TextWatcher, OnSeekBarChangeListener

### Community 6 - "Community 6"
Cohesion: 0.09
Nodes (7): Cue, Frame, Speech, StyleMath, StyleMetrics, styleTokenEstimate(), StyleTest

### Community 7 - "Community 7"
Cohesion: 0.08
Nodes (8): GraphAlgo, Relevance, Item, Msg, Voc, BackupTest, LogRedactTest, SeoGraphTest

### Community 8 - "Community 8"
Cohesion: 0.13
Nodes (6): ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result

### Community 9 - "Community 9"
Cohesion: 0.15
Nodes (8): chooseLinkAction(), Link, LinkActivity, QuickAnalysis, QuickConfig, QuickState, Step, YtLink

### Community 10 - "Community 10"
Cohesion: 0.22
Nodes (3): Ctx, DepthStat, RecEngine

### Community 12 - "Community 12"
Cohesion: 0.09
Nodes (3): scrollPage(), Thumbs, Type

### Community 13 - "Community 13"
Cohesion: 0.13
Nodes (5): RecConfig, RunRecord, SeoContext, strings(), toJsonArray()

### Community 14 - "Community 14"
Cohesion: 0.18
Nodes (5): AnalyticsTest, FmtTest, QuickAndChannelsTest, SeoPureTest, video()

### Community 16 - "Community 16"
Cohesion: 0.16
Nodes (3): RecEdge, RecGraph, RecNode

### Community 18 - "Community 18"
Cohesion: 0.23
Nodes (4): ChannelExplorer, Config, TopicFilter, TopicProfile

### Community 19 - "Community 19"
Cohesion: 0.16
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 20 - "Community 20"
Cohesion: 0.21
Nodes (3): LlmReply, LlmParse, LlmParseTest

### Community 21 - "Community 21"
Cohesion: 0.15
Nodes (3): Lumen, LumenEnv, Settings

### Community 24 - "Community 24"
Cohesion: 0.22
Nodes (3): LangGuess, Regions, WatchTest

### Community 26 - "Community 26"
Cohesion: 0.15
Nodes (5): ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, SeoChecklist

### Community 27 - "Community 27"
Cohesion: 0.17
Nodes (10): Llm, Provider, ANTHROPIC, CUSTOM, DEEPSEEK, GEMINI, GROQ, OLLAMA (+2 more)

### Community 29 - "Community 29"
Cohesion: 0.14
Nodes (14): Ic, ADD, BACK, BUG, COPY, DELETE, FIT, JOURNAL (+6 more)

### Community 30 - "Community 30"
Cohesion: 0.27
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 31 - "Community 31"
Cohesion: 0.26
Nodes (4): BrowserText, glyphIcon(), glyphLabel(), BrowserUiTest

### Community 34 - "Community 34"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

### Community 36 - "Community 36"
Cohesion: 0.33
Nodes (3): filtersSummary(), MonitorConfig, SearchFilters

### Community 48 - "Community 48"
Cohesion: 0.40
Nodes (5): BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **28 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+23 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 136 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **25 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 8`, `Community 9`, `Community 10`, `Community 11`, `Community 14`, `Community 18`, `Community 21`, `Community 23`, `Community 31`, `Community 35`, `Community 36`, `Community 37`, `Community 40`, `Community 41`, `Community 44`?**
  _High betweenness centrality (0.335) - this node is a cross-community bridge._
- **Are the 133 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 133 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05322290703646636 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 1` to `Community 0`, `Community 4`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 43`, `Community 13`, `Community 14`, `Community 15`, `Community 16`, `Community 49`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.05560224089635854 - nodes in this community are weakly interconnected._