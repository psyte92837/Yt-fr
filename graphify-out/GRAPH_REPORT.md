# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1172 nodes · 3759 edges · 35 communities (28 shown, 7 thin omitted)
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
- Community 28
- Community 29
- Community 30
- Community 31
- Community 33
- Community 34

## God Nodes (most connected - your core abstractions)
1. `t()` - 125 edges
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
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `audienceTab()` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt

## Import Cycles
- None detected.

## Communities (35 total, 7 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (14): RecBridge, load(), t(), ProjectActivity, startRecs(), seoTab(), SettingsActivity, TtsActivity (+6 more)

### Community 1 - "Community 1"
Cohesion: 0.06
Nodes (9): EdgeTts, WebSocketListener, Forbidden, TtsException, TtsOptions, TtsPrefs, TtsText, TtsVoice (+1 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (16): Analytics, Bucket, ChannelStat, Compare, Stats, SearchFilters, Video, Chunks (+8 more)

### Community 3 - "Community 3"
Cohesion: 0.07
Nodes (12): ChannelExplorer, Config, Relevance, Ctx, DepthStat, RecEdge, RecEngine, RecGraph (+4 more)

### Community 4 - "Community 4"
Cohesion: 0.07
Nodes (11): newId(), Comment, RunRecord, strings(), QuickState, Step, CollectService, Monitoring (+3 more)

### Community 5 - "Community 5"
Cohesion: 0.10
Nodes (3): Seo, SeoChecklist, TopicActivity

### Community 6 - "Community 6"
Cohesion: 0.06
Nodes (11): ImportParser, Parsed, JsonSalvage, BackupTest, FmtTest, ImportParserTest, LlmParseTest, QuickAndChannelsTest (+3 more)

### Community 7 - "Community 7"
Cohesion: 0.07
Nodes (12): onCreate(), ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result, Link (+4 more)

### Community 8 - "Community 8"
Cohesion: 0.05
Nodes (3): GraphView, KeyVault, VideoActivity

### Community 9 - "Community 9"
Cohesion: 0.09
Nodes (8): LumenActivity, NewProjectActivity, restoreFrom(), SimpleWatcher, WizardActivity, L, LumenCrashHandler, LumenBase

### Community 10 - "Community 10"
Cohesion: 0.07
Nodes (5): IconDrawable, Palette, scrollPage(), ArrayAdapter, Type

### Community 11 - "Community 11"
Cohesion: 0.10
Nodes (17): ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException, LlmReply, Provider (+9 more)

### Community 12 - "Community 12"
Cohesion: 0.11
Nodes (4): BarChartView, ChartDraw, Export, Csv

### Community 13 - "Community 13"
Cohesion: 0.09
Nodes (18): BrowserText, glyphIcon(), glyphLabel(), Ic, ADD, BACK, BUG, COPY (+10 more)

### Community 14 - "Community 14"
Cohesion: 0.21
Nodes (6): filtersEditor(), channelRows(), channelsTab(), chk(), Col, text()

### Community 15 - "Community 15"
Cohesion: 0.13
Nodes (6): GraphAlgo, Item, Msg, Voc, LogRedactTest, SeoGraphTest

### Community 16 - "Community 16"
Cohesion: 0.25
Nodes (4): sha1(), Prompts, AiResult, ChannelAi

### Community 17 - "Community 17"
Cohesion: 0.10
Nodes (4): isLocalSource(), Lumen, LumenEnv, Settings

### Community 18 - "Community 18"
Cohesion: 0.15
Nodes (5): filtersSummary(), LangGuess, Regions, WatchFilter, WatchTest

### Community 19 - "Community 19"
Cohesion: 0.12
Nodes (5): CollectConfig, MonitorConfig, RecConfig, SeoContext, toJsonArray()

### Community 21 - "Community 21"
Cohesion: 0.22
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 23 - "Community 23"
Cohesion: 0.29
Nodes (10): btn(), chip(), dp(), edt(), iconBtn(), prompt(), ripple(), roundBg() (+2 more)

### Community 25 - "Community 25"
Cohesion: 0.17
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 26 - "Community 26"
Cohesion: 0.18
Nodes (4): GraphActivity, chipRow(), rowOf(), OnItemSelectedListener

### Community 28 - "Community 28"
Cohesion: 0.31
Nodes (3): Fmt, overviewTab(), openVideo()

### Community 30 - "Community 30"
Cohesion: 0.33
Nodes (4): recRow(), recsTab(), state(), Thumbs

### Community 31 - "Community 31"
Cohesion: 0.46
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), showText()

### Community 34 - "Community 34"
Cohesion: 0.40
Nodes (5): BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **28 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+23 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 133 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **7 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 12`, `Community 13`, `Community 14`, `Community 16`, `Community 17`, `Community 18`, `Community 20`, `Community 21`, `Community 22`, `Community 23`, `Community 26`, `Community 27`, `Community 28`, `Community 30`, `Community 31`?**
  _High betweenness centrality (0.349) - this node is a cross-community bridge._
- **Are the 124 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 124 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05125858123569794 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 2` to `Community 0`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 8`, `Community 15`, `Community 16`, `Community 18`, `Community 19`, `Community 28`, `Community 29`?**
  _High betweenness centrality (0.064) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.059125085440874914 - nodes in this community are weakly interconnected._