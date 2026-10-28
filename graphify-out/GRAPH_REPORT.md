# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 880 nodes · 2928 edges · 38 communities (21 shown, 17 thin omitted)
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
- Community 20
- Community 21
- Community 22
- Community 23
- Community 25
- Community 26
- Community 27
- Community 28
- Community 31
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37

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
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `audienceTab()` --calls--> `btn()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Ui.kt

## Import Cycles
- None detected.

## Communities (38 total, 17 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (16): Analytics, Bucket, ChannelStat, Compare, Stats, Export, Video, Chunks (+8 more)

### Community 1 - "Community 1"
Cohesion: 0.09
Nodes (15): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), load(), newId(), t(), ProjectActivity (+7 more)

### Community 2 - "Community 2"
Cohesion: 0.07
Nodes (22): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+14 more)

### Community 3 - "Community 3"
Cohesion: 0.10
Nodes (5): Project, Seo, SeoChecklist, seoTab(), TopicActivity

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (10): CollectConfig, SearchFilters, Link, LinkActivity, QuickAnalysis, QuickConfig, QuickState, Step (+2 more)

### Community 5 - "Community 5"
Cohesion: 0.09
Nodes (16): chip(), chipRow(), chk(), dp(), edt(), isNight(), Palette, prompt() (+8 more)

### Community 6 - "Community 6"
Cohesion: 0.11
Nodes (9): onCreate(), ChannelData, ChannelExplorer, ChannelFetch, ChannelStats, ChannelStore, Config, PlaylistRef (+1 more)

### Community 7 - "Community 7"
Cohesion: 0.13
Nodes (6): GraphAlgo, Item, Msg, Voc, LogRedactTest, SeoGraphTest

### Community 8 - "Community 8"
Cohesion: 0.20
Nodes (6): filtersEditor(), channelRows(), channelsTab(), SettingsActivity, Col, text()

### Community 9 - "Community 9"
Cohesion: 0.11
Nodes (4): recRow(), recsTab(), state(), rowOf()

### Community 10 - "Community 10"
Cohesion: 0.23
Nodes (3): Ctx, RecEngine, RecNode

### Community 11 - "Community 11"
Cohesion: 0.17
Nodes (5): LumenActivity, NewProjectActivity, WizardActivity, confirm(), LumenBase

### Community 14 - "Community 14"
Cohesion: 0.21
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 15 - "Community 15"
Cohesion: 0.13
Nodes (4): isLocalSource(), Lumen, LumenEnv, Settings

### Community 16 - "Community 16"
Cohesion: 0.16
Nodes (4): MonitorConfig, RecConfig, SeoContext, toJsonArray()

### Community 17 - "Community 17"
Cohesion: 0.14
Nodes (3): DepthStat, RecEdge, RecGraph

### Community 18 - "Community 18"
Cohesion: 0.17
Nodes (4): FmtTest, LlmParseTest, QuickAndChannelsTest, SeoPureTest

### Community 20 - "Community 20"
Cohesion: 0.28
Nodes (4): Fmt, GraphActivity, overviewTab(), openVideo()

### Community 23 - "Community 23"
Cohesion: 0.18
Nodes (4): Comment, RunRecord, strings(), ModelsTest

### Community 28 - "Community 28"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

### Community 31 - "Community 31"
Cohesion: 0.33
Nodes (6): btn(), BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **15 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+10 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 101 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **17 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 1` to `Community 0`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 8`, `Community 9`, `Community 10`, `Community 11`, `Community 13`, `Community 14`, `Community 15`, `Community 18`, `Community 19`, `Community 20`, `Community 22`, `Community 27`?**
  _High betweenness centrality (0.222) - this node is a cross-community bridge._
- **Are the 94 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 94 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _15 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.0625694187338023 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 0` to `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 6`, `Community 7`, `Community 10`, `Community 16`, `Community 20`, `Community 23`?**
  _High betweenness centrality (0.072) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.08626434653831914 - nodes in this community are weakly interconnected._