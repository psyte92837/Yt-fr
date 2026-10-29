# Graph Report - src  (2026-10-29)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1390 nodes · 4431 edges · 50 communities (29 shown, 21 thin omitted)
- Extraction: 88% EXTRACTED · 12% INFERRED · 0% AMBIGUOUS · INFERRED: 545 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0: Llm + Models (Video) — 107 симв.
- Community 1: Recs + Topic (RecNode) — 85 симв.
- Community 2: TtsActivity + TtsShare (TtsActivity) — 70 симв.
- Community 3: Seo + TopicActivity (Seo) — 67 симв.
- Community 4: Channels + Quick (.run) — 66 симв.
- Community 5: TtsService (TtsService) — 65 симв.
- Community 6: Services + Store (Store) — 61 симв.
- Community 7: Ui + TtsActivity (Ui.kt) — 57 симв.
- Community 8: Graph + Charts (Graph.kt) — 57 симв.
- Community 9: Style + StyleTest (.measure) — 54 симв.
- Community 10: YouTube + Watch (YouTube.kt) — 50 симв.
- Community 11: ProjectActivity + Browser (t) — 41 симв.
- Community 12: Ui + BrowserUiTest (Ic) — 37 симв.
- Community 13: Ui + HomeActivities (Col) — 31 симв.
- Community 14: LumenCoreTest + Text (Test) — 29 симв.
- Community 15: Voc + SeoGraphTest (Voc) — 27 симв.
- Community 16: Core + Ui (.render) — 26 симв.
- Community 17: Browser + Backup (Browser.kt) — 25 симв.
- Community 18: VideoActivity + ChannelActivity (ProjectActivity.kt) — 24 симв.
- Community 19: Tts + Log (EdgeTts) — 24 симв.
- Community 20: Export + Text (.markdown) — 23 симв.
- Community 21: Text + TextTest (Text.kt) — 23 симв.
- Community 22: BackupTest + Backup (asserttrue) — 21 симв.
- Community 23: Log (L) — 21 симв.
- Community 24: AiJournal + TopicActivity (AiJournal) — 19 симв.
- Community 25: Tts + EdgeTtsTest (Tts.kt) — 19 симв.
- Community 26: Core + Log (Core.kt) — 18 симв.
- Community 27: EdgeTtsTest + Tts (EdgeTtsTest) — 18 симв.
- Community 28: TtsPoolTest + Tts (Test) — 18 симв.
- Community 29: Browser + Ui (BrowserActivity) — 17 симв.
- Community 30: EdgeGec (EdgeGec) — 17 симв.
- Community 31: Browser (.startRec) — 15 симв.
- Community 32: HomeActivities + Ui (LumenBase) — 14 симв.
- Community 33: LumenCoreTest + Seo (.parse) — 14 симв.
- Community 34: Tts (.fromFile) — 14 симв.
- Community 35: Analysis (Analytics) — 13 симв.
- Community 36: SettingsActivity + Browser (.render) — 13 симв.
- Community 37: Backup + ProjectActivity (AutoBackup) — 12 симв.
- Community 38: HomeActivities + Store (SimpleWatcher) — 10 симв.
- Community 39: TtsRetryTest + Tts (TtsRetry) — 10 симв.
- Community 41: Browser + Core (.ui) — 8 симв.
- Community 40: AudienceTab + Ui (audienceTab) — 8 симв.
- Community 42: Tts + EdgeTtsTest (.onOpen) — 8 симв.
- Community 43: Store (Store.kt) — 7 симв.
- Community 44: Browser (WebViewClient) — 6 симв.
- Community 45: SettingsActivity (SettingsActivity.kt) — 6 симв.
- Community 46: Ui (OnItemSelectedListener) — 4 симв.
- Community 48: Ui (.onCreate) — 4 симв.
- Community 47: Tts + TtsService (.voiceFor) — 4 симв.
- Community 49: Ui (ArrayAdapter) — 3 симв.

## God Nodes (most connected - your core abstractions)
1. `t()` - 147 edges
2. `Video` - 60 edges
3. `Col` - 51 edges
4. `Project` - 50 edges
5. `toast()` - 50 edges
6. `Seo` - 46 edges
7. `text()` - 45 edges
8. `TtsActivity` - 41 edges
9. `TtsService` - 39 edges
10. `overviewTab()` - 35 edges

## Surprising Connections (you probably didn't know these)
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `render()` --calls--> `Col`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/ChannelActivity.kt → app/src/main/java/org/schabi/newpipe/lumen/Ui.kt
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt

## Import Cycles
- None detected.

## Карта файлов (символы по числу связей; строка для Read offset)
- `AiJournal.kt` (16): .render():134, AiJournal:17, .list():76, Entry:22, .statusLabel():103, .full():109
- `Analysis.kt` (31): AiTasks:133, .projectSummary():222, .videoSummary():173, .requireProvider():141, .sentiment():243, .clusters():233
- `AudienceTab.kt` (5): audienceTab():30, insightCard():164, AudienceTab.kt:1, confidenceLabel():23, vocLabel():10
- `Backup.kt` (10): AutoBackup:21, Backup.kt:1, .maybe():49, .restore():139, .runNow():67, .writeToTree():82
- `Browser.kt` (47): Browser.kt:1, BrowserActivity:207, .onCreate():224, .finishRec():490, WebChromeClient:259, .paintRec():431
- `ChannelActivity.kt` (4): render():46, load():31, ChannelActivity.kt:1, onCreate():23
- `Channels.kt` (26): ChannelData:28, Channels.kt:1, .run():270, .fetch():179, .key():152, .forVideos():239
- `Charts.kt` (7): Charts.kt:1, BarChartView:78, .bars():15, .hbars():59, .onDraw():89, ChartDraw:14
- `Core.kt` (18): t():128, .async():43, .dateTime():134, Core.kt:1, sha1():163, .num():145
- `EdgeGec.kt` (15): EdgeGec:11, .init():76, .sha256():93, .cs64():120, .expand():66, .rounds():53
- `Export.kt` (12): .markdown():121, .write():18, Export:17, .pdf():214, Export.kt:1, .vocCsv():64
- `Graph.kt` (14): Graph.kt:1, .build():235, GraphView:24, .showNode():285, .onCreate():225, GraphActivity:215
- `GraphAlgo.kt` (8): .keywords():119, .matches():121, GraphAlgo.kt:1, Relevance:116, .layout():38, .pageRank():17
- `HomeActivities.kt` (23): .render():413, .render():137, HomeActivities.kt:1, filtersEditor():15, .projectMenu():226, .onCreate():103
- `KeyVault.kt` (6): KeyVault.kt:1, KeyVault:21, .has():63, .secretKey():25, .get():51, .put():39
- `Llm.kt` (31): .complete():132, .active():60, Provider:15, .get():294, LlmException:71, Llm.kt:1
- `LlmParse.kt` (7): .openAi():22, LlmParse:11, .anthropic():45, .gemini():60, .errorMessage():12, .models():85
- `Log.kt` (20): .w():69, L:21, .i():68, .report():166, .e():70, Log.kt:1
- `Models.kt` (35): Video:258, Project:192, AiResult:348, Models.kt:1, SearchFilters:19, RunRecord:250
- `OverviewTabs.kt` (4): overviewTab():10, channelsTab():162, channelRows():142, OverviewTabs.kt:1
- `ProjectActivity.kt` (29): .render():122, ProjectActivity:21, .analyticsTab():564, .aiTab():728, .settingsTab():871, .importList():305
- `Quick.kt` (28): .run():164, Quick.kt:1, .summary():126, .start():373, .classify():23, .onCreate():422
- `Recs.kt` (40): RecNode:15, RecEngine:153, RecGraph:69, .run():324, Ctx:156, .load():117
- `RecsTab.kt` (5): recsTab():37, recRow():262, RecsTab.kt:1, startRecs():15, state():35
- `Seo.kt` (53): Seo:135, .aiJson():179, .extractInsights():270, .qualify():506, .ideas():588, .pipeline():156
- `SeoTab.kt` (2): seoTab():10, SeoTab.kt:1
- `Services.kt` (28): .runOnce():312, Services.kt:1, .onStartCommand():89, CollectService:86, .run():162, .apply():292
- `SettingsActivity.kt` (14): .logs():340, .backup():226, .render():30, SettingsActivity:16, .ai():83, .onActivityResult():283
- `Store.kt` (26): Store:21, .project():50, .videos():73, .save():55, .mergeVideos():103, .writeFile():128
- `Style.kt` (38): .measure():338, styleCard():513, StyleMath:24, Style.kt:1, .ai():464, StyleMetrics:220
- `Text.kt` (24): .firstObject():10, .videoIdFromUrl():246, .row():185, Text.kt:1, .mini():265, .top():134
- `Topic.kt` (15): .ensure():88, .judgeChannels():114, TopicFilter:31, .keywordsOf():79, .load():77, TopicProfile:11
- `TopicActivity.kt` (13): .render():65, TopicActivity:14, .onActivityResult():327, .run():37, .answerLink():58, TopicActivity.kt:1
- `Tts.kt` (68): EdgeTts:48, Tts.kt:1, .fromFile():601, TtsText:490, .one():406, .voices():273
- `TtsActivity.kt` (42): TtsActivity:30, .build():109, TtsActivity.kt:1, .render():310, .start():381, .buildBar():175
- `TtsService.kt` (50): TtsService:71, TtsService.kt:1, .start():102, .build():518, .finishJob():188, .playPart():315
- `TtsShare.kt` (8): TtsShareBase:14, .onCreate():17, .startSave():68, .go():32, .onRequestPermissionsResult():60, TtsShare.kt:1
- `Ui.kt` (84): Ui.kt:1, Col:487, toast():146, text():361, .add():494, dp():141
- `VideoActivity.kt` (8): .render():42, VideoActivity:12, .collectNow():158, .summarise():186, .onCreate():19, VideoActivity.kt:1
- `Voc.kt` (12): Voc:11, .extract():84, .similarity():148, .isSpam():59, .normalize():54, .quoteFound():135
- `Watch.kt` (15): .apply():155, .guess():32, WatchFilter:124, LangGuess:14, .split():148, Regions:86
- `YouTube.kt` (17): YouTube.kt:1, YouTube:25, .collect():227, .search():91, .importList():137, .applyStreamInfo():204
- `BackupTest.kt` (3): BackupTest.kt:1, .autoBackupIsDueOnlyWithDataAfterTheInterval():14, BackupTest:11
- `BrowserUiTest.kt` (9): BrowserUiTest:13, BrowserUiTest.kt:1, .filtersSummaryListsOnlyNonDefaults():69, .appBarGlyphsHaveIconsAndLabels():58, .addressKeepsLinksAndCompletesHosts():14, .addressSearchesPlainText():23
- `EdgeTtsTest.kt` (17): EdgeTtsTest:13, .messagesAndTimestamp():100, EdgeTtsTest.kt:1, .ssmlEscapesAndFormatsProsody():25, .binaryFrameAudioExtraction():62, .languageDetectionAndVoicePick():87
- `LumenCoreTest.kt` (49): LumenCoreTest.kt:1, video():16, .modelsRoundTrip():437, RecGraphTest:74, LlmParseTest:182, .graph():75
- `SeoGraphTest.kt` (12): SeoGraphTest:12, SeoGraphTest.kt:1, .quoteVerificationAndTimestamps():72, .relevanceStemsRussianAndEnglish():34, .vocClassification():43, .secretsAreRemoved():100
- `StyleTest.kt` (13): StyleTest:14, StyleTest.kt:1, .frameColoursAndCuts():70, .descriptionCounters():60, .metricsJsonRoundTrip():95, .paletteKeepsDominantColours():85
- `TextTest.kt` (8): TextTest:13, TextTest.kt:1, .youtubeHelpers():49, .vttRemovesTagsAndRollingDuplicates():19, .wordsSkipStopWords():34, .chunksRespectMaxLength():42
- `TopicFilterTest.kt` (6): TopicFilterTest:7, .channelKeywordFallback():35, .parsesTopicAndIndexes():8, .prunesOffTopicNodesAndEdges():20, .linksLoseQueryParameters():42, TopicFilterTest.kt:1
- `TtsPoolTest.kt` (14): TtsPoolTest.kt:1, TtsPoolTest:15, TtsQueueTest:84, .percentAndLabel():68, .estimateAndDuration():93, .cancelledTakesNothingNew():61
- `TtsRetryTest.kt` (6): TtsRetryTest.kt:1, TtsRetryTest:12, .delaysGrowThenGiveUp():13, .readableMessages():19, .slowStreamIsNotAStall():32, TtsStallTest:31
- `WatchTest.kt` (9): WatchTest:12, .filtersAndMonitorKeepNewFields():62, .filterSplitsByNumbersAndFormatsLines():75, WatchTest.kt:1, .guessesLanguageByScriptAndLetters():13, .guessesLatinLanguagesByWordsAndMarks():26

## Communities (50 total, 21 thin omitted)

### Community 0 - "Llm + Models (Video)"
Cohesion: 0.05
Nodes (32): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+24 more)

### Community 1 - "Recs + Topic (RecNode)"
Cohesion: 0.06
Nodes (12): Relevance, RecConfig, Ctx, DepthStat, RecEdge, RecEngine, RecGraph, RecNode (+4 more)

### Community 2 - "TtsActivity + TtsShare (TtsActivity)"
Cohesion: 0.06
Nodes (7): TtsProgress, TtsActivity, TextWatcher, OnSeekBarChangeListener, TtsShareBase, TtsShareListen, TtsShareSave

### Community 3 - "Seo + TopicActivity (Seo)"
Cohesion: 0.09
Nodes (3): Seo, SeoChecklist, TopicActivity

### Community 4 - "Channels + Quick (.run)"
Cohesion: 0.06
Nodes (17): ChannelData, ChannelExplorer, ChannelFetch, ChannelStats, ChannelStore, Config, PlaylistRef, Result (+9 more)

### Community 5 - "TtsService (TtsService)"
Cohesion: 0.08
Nodes (8): TtsMode, LISTEN, PREVIEW, SAVE, TtsService, BroadcastReceiver, Callback, TtsState

### Community 6 - "Services + Store (Store)"
Cohesion: 0.07
Nodes (6): CollectService, CollectState, Monitoring, MonitorJob, Notify, Store

### Community 7 - "Ui + TtsActivity (Ui.kt)"
Cohesion: 0.09
Nodes (22): recRow(), btn(), BtnStyle, FILLED, OUTLINED, TEXT, TONAL, chip() (+14 more)

### Community 8 - "Graph + Charts (Graph.kt)"
Cohesion: 0.05
Nodes (5): BarChartView, ChartDraw, GraphActivity, GraphView, KeyVault

### Community 9 - "Style + StyleTest (.measure)"
Cohesion: 0.08
Nodes (8): Cue, Frame, Speech, StyleMath, StyleMetrics, styleTokenEstimate(), VideoStyle, StyleTest

### Community 10 - "YouTube + Watch (YouTube.kt)"
Cohesion: 0.08
Nodes (6): filtersSummary(), SearchFilters, LangGuess, Regions, YouTube, WatchTest

### Community 11 - "ProjectActivity + Browser (t)"
Cohesion: 0.16
Nodes (6): load(), t(), ProjectActivity, startRecs(), seoTab(), toast()

### Community 12 - "Ui + BrowserUiTest (Ic)"
Cohesion: 0.07
Nodes (19): BrowserText, glyphIcon(), glyphLabel(), Ic, ADD, BACK, BUG, COPY (+11 more)

### Community 13 - "Ui + HomeActivities (Col)"
Cohesion: 0.18
Nodes (7): filtersEditor(), channelRows(), channelsTab(), recsTab(), chk(), Col, confirm()

### Community 14 - "LumenCoreTest + Text (Test)"
Cohesion: 0.13
Nodes (7): JsonSalvage, AnalyticsTest, FmtTest, LlmParseTest, SeoPureTest, TruncationTest, video()

### Community 15 - "Voc + SeoGraphTest (Voc)"
Cohesion: 0.14
Nodes (6): GraphAlgo, Item, Msg, Voc, LogRedactTest, SeoGraphTest

### Community 16 - "Core + Ui (.render)"
Cohesion: 0.17
Nodes (9): render(), Fmt, isLocalSource(), overviewTab(), styleCard(), Runnable, shareText(), showText() (+1 more)

### Community 18 - "VideoActivity + ChannelActivity (ProjectActivity.kt)"
Cohesion: 0.12
Nodes (3): onCreate(), state(), VideoActivity

### Community 19 - "Tts + Log (EdgeTts)"
Cohesion: 0.17
Nodes (5): EdgeTts, Forbidden, TtsException, TtsOptions, TtsVoice

### Community 21 - "Text + TextTest (Text.kt)"
Cohesion: 0.15
Nodes (7): Chunks, JsonText, ThumbUrl, Vtt, Words, YtQuery, TextTest

### Community 24 - "AiJournal + TopicActivity (AiJournal)"
Cohesion: 0.20
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 26 - "Core + Log (Core.kt)"
Cohesion: 0.14
Nodes (4): Lumen, LumenEnv, newId(), Settings

### Community 28 - "TtsPoolTest + Tts (Test)"
Cohesion: 0.18
Nodes (4): TtsPool, TtsQueue, TtsPoolTest, TtsQueueTest

### Community 29 - "Browser + Ui (BrowserActivity)"
Cohesion: 0.16
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 32 - "HomeActivities + Ui (LumenBase)"
Cohesion: 0.22
Nodes (4): LumenActivity, NewProjectActivity, WizardActivity, LumenBase

### Community 33 - "LumenCoreTest + Seo (.parse)"
Cohesion: 0.24
Nodes (4): Comment, ImportParser, Parsed, ImportParserTest

### Community 35 - "Analysis (Analytics)"
Cohesion: 0.26
Nodes (5): Analytics, Bucket, ChannelStat, Compare, Stats

### Community 39 - "TtsRetryTest + Tts (TtsRetry)"
Cohesion: 0.27
Nodes (3): TtsRetry, TtsRetryTest, TtsStallTest

### Community 40 - "AudienceTab + Ui (audienceTab)"
Cohesion: 0.50
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo()

## Knowledge Gaps
- **31 isolated node(s):** `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT`, `ANTHROPIC`, `CUSTOM` (+26 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 155 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **21 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 11` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 12`, `Community 13`, `Community 14`, `Community 16`, `Community 17`, `Community 19`, `Community 20`, `Community 24`, `Community 25`, `Community 26`, `Community 31`, `Community 32`, `Community 34`, `Community 36`, `Community 38`, `Community 39`, `Community 40`, `Community 41`?**
  _High betweenness centrality (0.318) - this node is a cross-community bridge._
- **Are the 146 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 146 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ANTHROPIC`, `GEMINI`, `OPENAI_COMPAT` to the rest of the system?**
  _31 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05113736554399577 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 0` to `Community 1`, `Community 35`, `Community 4`, `Community 3`, `Community 6`, `Community 9`, `Community 10`, `Community 11`, `Community 14`, `Community 15`, `Community 16`, `Community 18`, `Community 20`?**
  _High betweenness centrality (0.064) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.05714285714285714 - nodes in this community are weakly interconnected._