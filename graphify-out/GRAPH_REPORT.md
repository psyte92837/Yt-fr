# Graph Report - src  (2026-10-29)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1544 nodes · 4953 edges · 67 communities (32 shown, 35 thin omitted)
- Extraction: 88% EXTRACTED · 12% INFERRED · 0% AMBIGUOUS · INFERRED: 579 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0: Dossier + Llm (Video) — 113 симв.
- Community 1: Services + ProjectActivity (.async) — 99 симв.
- Community 2: TtsActivity + Tts (t) — 91 симв.
- Community 3: Seo + Voc (Project) — 78 симв.
- Community 4: Text + Analysis (.videos) — 57 симв.
- Community 5: TtsService (TtsService) — 49 симв.
- Community 6: Watch + WatchTest (.apply) — 42 симв.
- Community 7: Ui (Ui.kt) — 41 симв.
- Community 8: Ui + HomeActivities (text) — 33 симв.
- Community 9: Ui + SettingsActivity (Col) — 31 симв.
- Community 10: Ui + BrowserUiTest (Ic) — 29 симв.
- Community 11: YouTube + Models (YouTube.kt) — 29 симв.
- Community 12: Tts + EdgeTtsTest (EdgeTts) — 29 симв.
- Community 13: Channels (ChannelData) — 28 симв.
- Community 14: ReportKit + DossierReport (ReportKit) — 28 симв.
- Community 15: Graph + GraphAlgo (Graph.kt) — 27 симв.
- Community 16: Quick (.run) — 27 симв.
- Community 17: LumenCoreTest (Test) — 27 симв.
- Community 18: Browser + Ui (BrowserActivity) — 25 симв.
- Community 19: GraphAlgo + GraphReport (.blocks) — 25 симв.
- Community 20: Core + OverviewTabs (.render) — 24 симв.
- Community 21: Browser + Backup (Browser.kt) — 24 симв.
- Community 22: Tts + Log (.w) — 24 симв.
- Community 23: Recs + Text (RecEngine) — 24 симв.
- Community 25: Models + Recs (JSONObject) — 22 симв.
- Community 24: ReportKit + GraphReport (.card) — 22 симв.
- Community 26: Log + AiJournal (L) — 21 симв.
- Community 27: Export + ReportKit (ReportKit.kt) — 21 симв.
- Community 28: TtsPoolTest + Tts (Test) — 20 симв.
- Community 29: AiJournal + TopicActivity (.render) — 19 симв.
- Community 30: BackupTest + SeoGraphTest (asserttrue) — 19 симв.
- Community 31: Recs + LumenCoreTest (RecNode) — 19 симв.
- Community 32: Style + StyleTest (.measure) — 19 симв.
- Community 33: Browser + Core (.ui) — 18 симв.
- Community 34: EdgeTtsTest + Tts (EdgeTtsTest) — 18 симв.
- Community 35: EdgeGec (EdgeGec) — 17 симв.
- Community 36: TtsService (TtsService.kt) — 17 симв.
- Community 37: Style + Seo (Style.kt) — 16 симв.
- Community 38: DossierReport + GraphReport (DossierReport.kt) — 16 симв.
- Community 39: Topic + TopicFilterTest (.ensure) — 15 симв.
- Community 40: Core (Core.kt) — 14 симв.
- Community 41: StyleTest + Style (StyleTest) — 14 симв.
- Community 42: Backup + HomeActivities (AutoBackup) — 13 симв.
- Community 43: HomeActivities + Ui (LumenBase) — 13 симв.
- Community 44: TopicActivity (TopicActivity) — 13 симв.
- Community 46: Tts (.fromFile) — 12 симв.
- Community 45: AudienceTab + Ui (.render) — 12 симв.
- Community 47: Charts (RectF) — 11 симв.
- Community 48: Tts + TtsRetryTest (.withRetry) — 11 симв.
- Community 49: TopicFilterTest + Channels (.run) — 10 симв.
- Community 50: Graph + ReportKit (GraphActivity) — 10 симв.
- Community 52: Seo + LumenCoreTest (.parse) — 10 симв.
- Community 53: Style + StyleTest (StyleMetrics) — 10 симв.
- Community 51: RecsTab + ProjectActivity (ProjectActivity.kt) — 10 симв.
- Community 54: HomeActivities (SimpleWatcher) — 9 симв.
- Community 55: Browser (RecKeepAlive) — 8 симв.
- Community 56: GraphAlgo + Recs (.keywords) — 7 симв.
- Community 57: VideoActivity (VideoActivity) — 7 симв.
- Community 59: Browser (WebViewClient) — 6 симв.
- Community 61: Models + DossierReport (.comments) — 6 симв.
- Community 62: LumenCoreTest + Models (.from) — 6 симв.
- Community 63: LumenCoreTest (RecGraphTest) — 6 симв.
- Community 60: ChannelActivity + SeoTab (load) — 6 симв.
- Community 58: SettingsActivity (alertdialog) — 6 симв.
- Community 64: LumenCoreTest + Text (.array) — 5 симв.
- Community 65: Ui (OnItemSelectedListener) — 4 симв.
- Community 66: Ui (.onCreate) — 2 симв.

## God Nodes (most connected - your core abstractions)
1. `t()` - 162 edges
2. `Video` - 64 edges
3. `Project` - 53 edges
4. `toast()` - 53 edges
5. `Col` - 52 edges
6. `Seo` - 46 edges
7. `text()` - 46 edges
8. `TtsActivity` - 41 edges
9. `TtsService` - 39 edges
10. `ProjectActivity` - 35 edges

## Surprising Connections (you probably didn't know these)
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `VideoBlock` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/DossierReport.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt
- `Picture` --calls--> `t()`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/GraphReport.kt → app/src/main/java/org/schabi/newpipe/lumen/Core.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt

## Import Cycles
- None detected.

## Карта файлов (символы по числу связей; строка для Read offset)
- `AiJournal.kt` (16): .render():134, AiJournal:17, .list():76, Entry:22, .statusLabel():103, .record():53
- `Analysis.kt` (31): AiTasks:133, .projectSummary():222, .requireProvider():141, .videoSummary():173, .sentiment():243, .clusters():233
- `AudienceTab.kt` (5): audienceTab():30, insightCard():164, AudienceTab.kt:1, confidenceLabel():23, vocLabel():10
- `Backup.kt` (10): AutoBackup:21, Backup.kt:1, .maybe():49, .restore():139, .runNow():67, .writeToTree():82
- `Browser.kt` (47): Browser.kt:1, BrowserActivity:207, .onCreate():224, .finishRec():490, WebChromeClient:259, .paintRec():431
- `ChannelActivity.kt` (4): render():46, load():31, ChannelActivity.kt:1, onCreate():23
- `Channels.kt` (26): ChannelData:28, Channels.kt:1, .fetch():179, .run():270, .key():152, .get():143
- `Charts.kt` (7): Charts.kt:1, BarChartView:78, .bars():15, .hbars():59, .onDraw():89, ChartDraw:14
- `Core.kt` (18): t():128, .async():43, .num():145, .dateTime():134, sha1():163, Core.kt:1
- `Dossier.kt` (29): Dossier:36, .runChannel():222, .runOverview():242, .run():262, .channelData():163, .group():144
- `DossierReport.kt` (10): DossierReport.kt:1, .blocks():39, VideoBlock:162, DossierReport:27, ChannelHeader:147, .write():28
- `EdgeGec.kt` (15): EdgeGec:11, .init():76, .sha256():93, .cs64():120, .expand():66, .rounds():53
- `Export.kt` (12): .markdown():124, .write():18, Export:17, .pdf():217, Export.kt:1, .vocCsv():67
- `Graph.kt` (19): Graph.kt:1, .build():246, GraphView:65, GraphActivity:224, .showNode():342, .exportMenu():299
- `GraphAlgo.kt` (17): .story():249, .keywords():119, GraphStats:147, .matches():121, GraphAlgo.kt:1, .of():183
- `GraphReport.kt` (14): GraphReport.kt:1, .blocks():182, GraphReport:24, Picture:69, Input:25, .input():57
- `HomeActivities.kt` (23): .render():413, .render():137, HomeActivities.kt:1, .projectMenu():226, filtersEditor():15, .onCreate():103
- `KeyVault.kt` (6): KeyVault.kt:1, KeyVault:21, .has():63, .secretKey():25, .get():51, .put():39
- `Llm.kt` (31): .complete():132, Provider:15, .active():60, .get():295, LlmException:71, .fill():305
- `LlmParse.kt` (7): .openAi():22, LlmParse:11, .anthropic():45, .gemini():60, .errorMessage():12, .models():85
- `Log.kt` (20): .w():69, L:21, .i():68, .report():166, .e():70, Log.kt:1
- `Models.kt` (35): Video:258, Project:192, AiResult:348, Models.kt:1, SearchFilters:19, RunRecord:250
- `OverviewTabs.kt` (4): overviewTab():10, channelsTab():162, channelRows():142, OverviewTabs.kt:1
- `ProjectActivity.kt` (33): ProjectActivity:21, .render():122, .analyticsTab():564, .aiTab():728, .settingsTab():976, .importList():305
- `Quick.kt` (28): .run():164, Quick.kt:1, .summary():126, .start():373, .classify():23, .onCreate():422
- `Recs.kt` (40): RecNode:15, RecEngine:153, RecGraph:69, .run():324, .load():117, Ctx:156
- `RecsTab.kt` (5): recsTab():37, recRow():262, RecsTab.kt:1, startRecs():15, state():35
- `ReportKit.kt` (38): ReportKit:34, ReportKit.kt:1, .paint():62, Block:57, .card():89, .layout():71
- `Seo.kt` (53): Seo:135, .aiJson():179, .extractInsights():270, .qualify():506, .ideas():588, .pipeline():156
- `SeoTab.kt` (2): seoTab():10, SeoTab.kt:1
- `Services.kt` (28): .runOnce():319, Services.kt:1, .onStartCommand():90, CollectService:87, .run():168, .apply():299
- `SettingsActivity.kt` (14): .logs():342, .backup():228, .render():30, SettingsActivity:16, .ai():83, .onActivityResult():285
- `Store.kt` (26): .project():50, .videos():73, Store:21, .save():55, .mergeVideos():103, .comments():139
- `Style.kt` (38): .measure():338, styleCard():513, StyleMath:24, Style.kt:1, .ai():464, StyleMetrics:220
- `Text.kt` (24): .firstObject():10, .videoIdFromUrl():246, .row():185, Text.kt:1, .top():134, .mini():265
- `Topic.kt` (15): .ensure():88, .judgeChannels():114, TopicFilter:31, .keywordsOf():79, .load():77, TopicProfile:11
- `TopicActivity.kt` (13): .render():65, TopicActivity:14, .onActivityResult():327, .run():37, .answerLink():58, TopicActivity.kt:1
- `Tts.kt` (68): EdgeTts:48, Tts.kt:1, .fromFile():601, TtsText:490, .one():406, .voices():273
- `TtsActivity.kt` (42): TtsActivity:30, .build():109, TtsActivity.kt:1, .render():310, .buildBar():175, .start():381
- `TtsService.kt` (50): TtsService:71, TtsService.kt:1, .start():102, .build():518, .finishJob():188, .playPart():315
- `TtsShare.kt` (8): TtsShareBase:14, .onCreate():17, .startSave():68, .go():32, .onRequestPermissionsResult():60, TtsShare.kt:1
- `Ui.kt` (84): Ui.kt:1, toast():146, Col:487, text():361, .add():494, dp():141
- `VideoActivity.kt` (8): .render():42, VideoActivity:12, .collectNow():158, .summarise():186, .onCreate():19, VideoActivity.kt:1
- `Voc.kt` (12): Voc:11, .extract():84, .similarity():148, .isSpam():59, .normalize():54, .quoteFound():135
- `Watch.kt` (15): .apply():155, .guess():32, WatchFilter:124, LangGuess:14, .split():148, Regions:86
- `YouTube.kt` (17): YouTube.kt:1, YouTube:25, .collect():227, .search():91, .importList():137, .applyStreamInfo():204
- `BackupTest.kt` (3): BackupTest.kt:1, .autoBackupIsDueOnlyWithDataAfterTheInterval():14, BackupTest:11
- `BrowserUiTest.kt` (9): BrowserUiTest:13, BrowserUiTest.kt:1, .appBarGlyphsHaveIconsAndLabels():58, .filtersSummaryListsOnlyNonDefaults():69, .addressKeepsLinksAndCompletesHosts():14, .addressSearchesPlainText():23
- `EdgeTtsTest.kt` (17): EdgeTtsTest:13, .messagesAndTimestamp():100, EdgeTtsTest.kt:1, .ssmlEscapesAndFormatsProsody():25, .binaryFrameAudioExtraction():62, .languageDetectionAndVoicePick():87
- `LumenCoreTest.kt` (49): LumenCoreTest.kt:1, video():16, .modelsRoundTrip():437, LlmParseTest:182, RecGraphTest:74, .graph():75
- `ReportsTest.kt` (11): ReportsTest.kt:1, DossierTest:77, GraphStatsTest:13, .storyMentionsKeyFacts():52, .garbageIsNull():104, .groupsByChannelStrongestFirst():123
- `SeoGraphTest.kt` (12): SeoGraphTest:12, SeoGraphTest.kt:1, .quoteVerificationAndTimestamps():72, .relevanceStemsRussianAndEnglish():34, .vocClassification():43, .csvParserHandlesQuotes():83
- `StyleTest.kt` (13): StyleTest:14, StyleTest.kt:1, .frameColoursAndCuts():70, .descriptionCounters():60, .paletteKeepsDominantColours():85, .speechPaceAndHook():37
- `TextTest.kt` (8): TextTest:13, TextTest.kt:1, .youtubeHelpers():49, .vttRemovesTagsAndRollingDuplicates():19, .wordsSkipStopWords():34, .chunksRespectMaxLength():42
- `TopicFilterTest.kt` (6): TopicFilterTest:7, .parsesTopicAndIndexes():8, .channelKeywordFallback():35, .prunesOffTopicNodesAndEdges():20, .linksLoseQueryParameters():42, TopicFilterTest.kt:1
- `TtsPoolTest.kt` (14): TtsPoolTest.kt:1, TtsPoolTest:15, TtsQueueTest:84, .percentAndLabel():68, .estimateAndDuration():93, .cancelledTakesNothingNew():61
- `TtsRetryTest.kt` (6): TtsRetryTest.kt:1, TtsRetryTest:12, .delaysGrowThenGiveUp():13, .readableMessages():19, .slowStreamIsNotAStall():32, TtsStallTest:31
- `WatchTest.kt` (9): WatchTest:12, .filtersAndMonitorKeepNewFields():62, .filterSplitsByNumbersAndFormatsLines():75, WatchTest.kt:1, .guessesLanguageByScriptAndLetters():13, .guessesLatinLanguagesByWordsAndMarks():26

## Communities (67 total, 35 thin omitted)

### Community 0 - "Dossier + Llm (Video)"
Cohesion: 0.05
Nodes (31): AiTasks, sha1(), ChannelCard, Config, Dossier, Group, Overview, VideoCard (+23 more)

### Community 1 - "Services + ProjectActivity (.async)"
Cohesion: 0.05
Nodes (8): RunRecord, ProjectActivity, CollectService, CollectState, Monitoring, MonitorJob, Notify, Store

### Community 2 - "TtsActivity + Tts (t)"
Cohesion: 0.05
Nodes (11): t(), startRecs(), Runnable, TtsProgress, TtsActivity, TextWatcher, OnSeekBarChangeListener, TtsShareBase (+3 more)

### Community 3 - "Seo + Voc (Project)"
Cohesion: 0.08
Nodes (8): GraphAlgo, Project, Seo, seoTab(), Item, Msg, Voc, SeoGraphTest

### Community 4 - "Text + Analysis (.videos)"
Cohesion: 0.07
Nodes (14): Analytics, Bucket, ChannelStat, Compare, Stats, Export, Chunks, Csv (+6 more)

### Community 5 - "TtsService (TtsService)"
Cohesion: 0.11
Nodes (8): TtsMode, LISTEN, PREVIEW, SAVE, TtsService, BroadcastReceiver, Callback, TtsState

### Community 6 - "Watch + WatchTest (.apply)"
Cohesion: 0.08
Nodes (7): filtersSummary(), KeyVault, MonitorConfig, LangGuess, Regions, WatchFilter, WatchTest

### Community 7 - "Ui (Ui.kt)"
Cohesion: 0.06
Nodes (11): BtnStyle, FILLED, OUTLINED, TEXT, TONAL, IconDrawable, Palette, scrollPage() (+3 more)

### Community 8 - "Ui + HomeActivities (text)"
Cohesion: 0.19
Nodes (14): recRow(), btn(), chipRow(), chk(), dp(), edt(), iconBtn(), prompt() (+6 more)

### Community 9 - "Ui + SettingsActivity (Col)"
Cohesion: 0.16
Nodes (5): filtersEditor(), SettingsActivity, Col, openUrl(), rowOf()

### Community 10 - "Ui + BrowserUiTest (Ic)"
Cohesion: 0.09
Nodes (18): BrowserText, glyphIcon(), glyphLabel(), Ic, ADD, BACK, BUG, COPY (+10 more)

### Community 12 - "Tts + EdgeTtsTest (EdgeTts)"
Cohesion: 0.13
Nodes (5): EdgeTts, Forbidden, TtsException, TtsOptions, TtsVoice

### Community 13 - "Channels (ChannelData)"
Cohesion: 0.14
Nodes (6): ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result

### Community 14 - "ReportKit + DossierReport (ReportKit)"
Cohesion: 0.19
Nodes (18): ChannelHeader, DossierReport, VideoBlock, LinkRow, NodeCard, Bars, Block, Footer (+10 more)

### Community 16 - "Quick (.run)"
Cohesion: 0.15
Nodes (8): chooseLinkAction(), Link, LinkActivity, QuickAnalysis, QuickConfig, QuickState, Step, YtLink

### Community 17 - "LumenCoreTest (Test)"
Cohesion: 0.15
Nodes (6): AnalyticsTest, FmtTest, LlmParseTest, QuickAndChannelsTest, SeoPureTest, video()

### Community 18 - "Browser + Ui (BrowserActivity)"
Cohesion: 0.14
Nodes (4): BrowserActivity, WebChromeClient, RecBridge, isNight()

### Community 19 - "GraphAlgo + GraphReport (.blocks)"
Cohesion: 0.16
Nodes (7): GraphStats, Plural, Summary, GraphReport, Input, Picture, GraphStatsTest

### Community 20 - "Core + OverviewTabs (.render)"
Cohesion: 0.25
Nodes (10): render(), Fmt, isLocalSource(), channelRows(), channelsTab(), overviewTab(), recsTab(), styleCard() (+2 more)

### Community 22 - "Tts + Log (.w)"
Cohesion: 0.12
Nodes (3): WebSocketListener, TtsPool, TtsPrefs

### Community 23 - "Recs + Text (RecEngine)"
Cohesion: 0.23
Nodes (3): Ctx, DepthStat, RecEngine

### Community 25 - "Models + Recs (JSONObject)"
Cohesion: 0.13
Nodes (5): CollectConfig, RecConfig, SeoContext, strings(), toJsonArray()

### Community 28 - "TtsPoolTest + Tts (Test)"
Cohesion: 0.17
Nodes (3): TtsQueue, TtsPoolTest, TtsQueueTest

### Community 29 - "AiJournal + TopicActivity (.render)"
Cohesion: 0.22
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 31 - "Recs + LumenCoreTest (RecNode)"
Cohesion: 0.16
Nodes (3): RecEdge, RecGraph, RecNode

### Community 32 - "Style + StyleTest (.measure)"
Cohesion: 0.22
Nodes (4): Cue, Frame, Speech, StyleMath

### Community 40 - "Core (Core.kt)"
Cohesion: 0.18
Nodes (4): Lumen, LumenEnv, newId(), Settings

### Community 43 - "HomeActivities + Ui (LumenBase)"
Cohesion: 0.24
Nodes (4): LumenActivity, NewProjectActivity, WizardActivity, LumenBase

### Community 45 - "AudienceTab + Ui (.render)"
Cohesion: 0.30
Nodes (7): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo(), shareText(), showText()

### Community 48 - "Tts + TtsRetryTest (.withRetry)"
Cohesion: 0.25
Nodes (3): TtsRetry, TtsRetryTest, TtsStallTest

### Community 49 - "TopicFilterTest + Channels (.run)"
Cohesion: 0.27
Nodes (3): ChannelExplorer, Config, TopicFilterTest

### Community 52 - "Seo + LumenCoreTest (.parse)"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

## Knowledge Gaps
- **31 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+26 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 171 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **35 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 2` to `Community 0`, `Community 1`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 8`, `Community 9`, `Community 10`, `Community 12`, `Community 13`, `Community 14`, `Community 15`, `Community 16`, `Community 17`, `Community 18`, `Community 19`, `Community 20`, `Community 22`, `Community 23`, `Community 29`, `Community 32`, `Community 33`, `Community 39`, `Community 40`, `Community 43`, `Community 44`, `Community 45`, `Community 46`, `Community 48`, `Community 49`, `Community 50`, `Community 53`, `Community 54`, `Community 55`, `Community 60`?**
  _High betweenness centrality (0.352) - this node is a cross-community bridge._
- **Are the 161 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 161 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _31 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05056890012642225 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 0` to `Community 32`, `Community 1`, `Community 3`, `Community 4`, `Community 6`, `Community 8`, `Community 11`, `Community 13`, `Community 16`, `Community 17`, `Community 20`, `Community 23`, `Community 56`, `Community 25`, `Community 57`, `Community 61`, `Community 31`?**
  _High betweenness centrality (0.060) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 4 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.05483405483405483 - nodes in this community are weakly interconnected._