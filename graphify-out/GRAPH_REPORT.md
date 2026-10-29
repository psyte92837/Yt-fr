# Graph Report - src  (2026-10-29)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1318 nodes · 4217 edges · 50 communities (30 shown, 20 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 528 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0: Recs + Topic (.w) — 74 симв.
- Community 1: Services + Log (Services.kt) — 73 симв.
- Community 2: Llm + Analysis (Project) — 72 симв.
- Community 3: Text + Analysis (.markdown) — 69 симв.
- Community 4: Seo + TopicActivity (Seo) — 68 симв.
- Community 5: ProjectActivity + Services (t) — 65 симв.
- Community 6: Style + StyleTest (.measure) — 63 симв.
- Community 7: SeoGraphTest + Voc (Voc) — 59 симв.
- Community 8: HomeActivities + VideoActivity (LumenBase) — 54 симв.
- Community 9: TtsActivity + Tts (TtsActivity) — 50 симв.
- Community 10: Ui + Core (text) — 42 симв.
- Community 11: Watch + WatchTest (.apply) — 42 симв.
- Community 12: Ui + BrowserUiTest (Ui.kt) — 40 симв.
- Community 13: LumenCoreTest + LlmParse (Test) — 29 симв.
- Community 14: Ui + ProjectActivity (dp) — 26 симв.
- Community 15: Tts + TtsActivity (EdgeTts) — 24 симв.
- Community 16: Browser + Core (BrowserActivity) — 23 симв.
- Community 17: Graph + GraphAlgo (Graph.kt) — 23 симв.
- Community 18: Ui + SettingsActivity (Col) — 23 симв.
- Community 19: Tts + EdgeTtsTest (Tts.kt) — 23 симв.
- Community 20: Ui (Ic) — 23 симв.
- Community 21: Browser (Browser.kt) — 22 симв.
- Community 22: YouTube + Text (YouTube) — 22 симв.
- Community 23: Models + Recs (JSONObject) — 22 симв.
- Community 24: Channels + LumenCoreTest (ChannelData) — 20 симв.
- Community 25: Core + Log (Core.kt) — 18 симв.
- Community 27: EdgeGec (EdgeGec) — 17 симв.
- Community 28: EdgeTtsTest + Tts (EdgeTtsTest) — 17 симв.
- Community 26: Channels + YouTube (Channels.kt) — 17 симв.
- Community 29: Backup + ProjectActivity (AutoBackup) — 16 симв.
- Community 30: TtsService + Tts (.finish) — 13 симв.
- Community 31: TtsPoolTest + Tts (TtsPoolTest) — 13 симв.
- Community 32: TtsService (TtsService.kt) — 13 симв.
- Community 33: BrowserUiTest + Browser (BrowserUiTest) — 12 симв.
- Community 34: Quick (Quick.kt) — 12 симв.
- Community 35: Tts (.fromFile) — 12 симв.
- Community 37: Browser + Ui (WebChromeClient) — 10 симв.
- Community 38: Quick (.run) — 10 симв.
- Community 39: Seo + LumenCoreTest (.parse) — 10 симв.
- Community 36: AudienceTab + AiJournal (audienceTab) — 10 симв.
- Community 40: Browser (.close) — 9 симв.
- Community 41: Channels + YouTube (Video) — 9 симв.
- Community 42: Tts + EdgeTtsTest (.onOpen) — 8 симв.
- Community 43: Store (Store.kt) — 7 симв.
- Community 44: Browser (WebViewClient) — 6 симв.
- Community 45: Models + Seo (.comments) — 6 симв.
- Community 46: Quick (QuickConfig) — 6 симв.
- Community 47: LumenCoreTest (RecGraphTest) — 6 симв.
- Community 48: LumenCoreTest + Models (.from) — 5 симв.
- Community 49: LumenCoreTest + Text (.array) — 5 симв.

## God Nodes (most connected - your core abstractions)
1. `t()` - 139 edges
2. `Video` - 60 edges
3. `Col` - 50 edges
4. `Project` - 50 edges
5. `Seo` - 46 edges
6. `toast()` - 44 edges
7. `text()` - 43 edges
8. `TtsActivity` - 40 edges
9. `overviewTab()` - 35 edges
10. `render()` - 34 edges

## Surprising Connections (you probably didn't know these)
- `render()` --calls--> `Col`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/ChannelActivity.kt → app/src/main/java/org/schabi/newpipe/lumen/Ui.kt
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt

## Import Cycles
- None detected.

## Карта файлов (символы по числу связей; строка для Read offset)
- `AiJournal.kt` (16): .render():134, AiJournal:17, .list():76, Entry:22, .statusLabel():103, .full():109
- `Analysis.kt` (31): AiTasks:133, .projectSummary():222, .videoSummary():173, .requireProvider():141, .sentiment():243, .clusters():233
- `AudienceTab.kt` (5): audienceTab():30, insightCard():164, AudienceTab.kt:1, confidenceLabel():23, vocLabel():10
- `Backup.kt` (10): AutoBackup:21, Backup.kt:1, .maybe():49, .restore():139, .runNow():67, .writeToTree():82
- `Browser.kt` (47): Browser.kt:1, BrowserActivity:207, .onCreate():224, .finishRec():490, WebChromeClient:259, .paintRec():431
- `ChannelActivity.kt` (4): render():46, load():31, ChannelActivity.kt:1, onCreate():23
- `Channels.kt` (26): ChannelData:28, Channels.kt:1, .run():270, .fetch():179, .key():152, .get():143
- `Charts.kt` (7): Charts.kt:1, BarChartView:78, .bars():15, .hbars():59, .onDraw():89, ChartDraw:14
- `Core.kt` (18): t():128, .async():43, .dateTime():134, Core.kt:1, .num():145, sha1():163
- `EdgeGec.kt` (15): EdgeGec:11, .init():76, .sha256():93, .cs64():120, .expand():66, .rounds():53
- `Export.kt` (12): .markdown():121, .write():18, Export:17, .pdf():214, Export.kt:1, .vocCsv():64
- `Graph.kt` (14): Graph.kt:1, .build():235, GraphView:24, .showNode():285, .onCreate():225, GraphActivity:215
- `GraphAlgo.kt` (8): .keywords():119, .matches():121, GraphAlgo.kt:1, Relevance:116, .layout():38, .pageRank():17
- `HomeActivities.kt` (23): .render():413, .render():137, HomeActivities.kt:1, filtersEditor():15, .projectMenu():226, .onCreate():103
- `KeyVault.kt` (6): KeyVault.kt:1, KeyVault:21, .secretKey():25, .has():63, .get():51, .put():39
- `Llm.kt` (31): .complete():132, .active():60, Provider:15, .get():294, LlmException:71, Llm.kt:1
- `LlmParse.kt` (7): .openAi():22, LlmParse:11, .anthropic():45, .gemini():60, .errorMessage():12, .models():85
- `Log.kt` (20): .w():69, L:21, .i():68, .report():166, .e():70, Log.kt:1
- `Models.kt` (35): Video:258, Project:192, AiResult:348, Models.kt:1, SearchFilters:19, RunRecord:250
- `OverviewTabs.kt` (4): overviewTab():10, channelsTab():162, channelRows():142, OverviewTabs.kt:1
- `ProjectActivity.kt` (29): .render():122, ProjectActivity:21, .analyticsTab():564, .aiTab():728, .settingsTab():871, .videoRow():435
- `Quick.kt` (28): .run():164, Quick.kt:1, .summary():126, .onCreate():422, .start():373, .classify():23
- `Recs.kt` (40): RecNode:15, RecEngine:153, RecGraph:69, Ctx:156, .run():324, .load():117
- `RecsTab.kt` (5): recsTab():37, recRow():262, RecsTab.kt:1, startRecs():15, state():35
- `Seo.kt` (53): Seo:135, .aiJson():179, .extractInsights():270, .qualify():506, .ideas():588, .pipeline():156
- `SeoTab.kt` (2): seoTab():10, SeoTab.kt:1
- `Services.kt` (28): .runOnce():312, Services.kt:1, .onStartCommand():89, CollectService:86, .run():162, .apply():292
- `SettingsActivity.kt` (14): .logs():340, .backup():226, .render():30, SettingsActivity:16, .ai():83, .onActivityResult():283
- `Store.kt` (26): Store:21, .project():50, .videos():73, .save():55, .mergeVideos():103, .comments():139
- `Style.kt` (38): .measure():338, styleCard():513, StyleMath:24, Style.kt:1, .ai():464, StyleMetrics:220
- `Text.kt` (24): .firstObject():10, .videoIdFromUrl():246, .row():185, Text.kt:1, .mini():265, .top():134
- `Topic.kt` (15): .ensure():88, .judgeChannels():114, TopicFilter:31, .keywordsOf():79, .load():77, TopicProfile:11
- `TopicActivity.kt` (13): .render():65, TopicActivity:14, .onActivityResult():327, .run():37, .answerLink():58, .onCreate():19
- `Tts.kt` (61): EdgeTts:48, Tts.kt:1, .fromFile():600, .synthesize():325, .one():409, TtsText:489
- `TtsActivity.kt` (41): TtsActivity:24, .build():80, TtsActivity.kt:1, .loadFile():407, .renderVoices():257, .play():302
- `TtsService.kt` (14): .finish():152, TtsService.kt:1, .run():74, TtsService:40, .show():133, .onStartCommand():43
- `Ui.kt` (84): Ui.kt:1, Col:487, toast():146, text():361, .add():494, dp():141
- `VideoActivity.kt` (8): .render():42, VideoActivity:12, .collectNow():158, .summarise():186, .onCreate():19, VideoActivity.kt:1
- `Voc.kt` (12): Voc:11, .extract():84, .similarity():148, .isSpam():59, .normalize():54, .quoteFound():135
- `Watch.kt` (15): .apply():155, .guess():32, WatchFilter:124, LangGuess:14, .split():148, Regions:86
- `YouTube.kt` (17): YouTube.kt:1, YouTube:25, .collect():227, .search():91, .importList():137, .comments():278
- `BackupTest.kt` (3): BackupTest.kt:1, .autoBackupIsDueOnlyWithDataAfterTheInterval():14, BackupTest:11
- `BrowserUiTest.kt` (9): BrowserUiTest:13, BrowserUiTest.kt:1, .appBarGlyphsHaveIconsAndLabels():58, .filtersSummaryListsOnlyNonDefaults():69, .addressKeepsLinksAndCompletesHosts():14, .addressSearchesPlainText():23
- `EdgeTtsTest.kt` (17): EdgeTtsTest:13, .messagesAndTimestamp():100, EdgeTtsTest.kt:1, .ssmlEscapesAndFormatsProsody():25, .binaryFrameAudioExtraction():62, .languageDetectionAndVoicePick():87
- `LumenCoreTest.kt` (49): LumenCoreTest.kt:1, video():16, .modelsRoundTrip():437, LlmParseTest:182, RecGraphTest:74, .graph():75
- `SeoGraphTest.kt` (12): SeoGraphTest:12, SeoGraphTest.kt:1, .quoteVerificationAndTimestamps():72, .relevanceStemsRussianAndEnglish():34, .vocClassification():43, .secretsAreRemoved():100
- `StyleTest.kt` (13): StyleTest:14, StyleTest.kt:1, .frameColoursAndCuts():70, .descriptionCounters():60, .metricsJsonRoundTrip():95, .paletteKeepsDominantColours():85
- `TextTest.kt` (8): TextTest:13, TextTest.kt:1, .youtubeHelpers():49, .vttRemovesTagsAndRollingDuplicates():19, .wordsSkipStopWords():34, .chunksRespectMaxLength():42
- `TopicFilterTest.kt` (6): TopicFilterTest:7, .channelKeywordFallback():35, .parsesTopicAndIndexes():8, .prunesOffTopicNodesAndEdges():20, .linksLoseQueryParameters():42, TopicFilterTest.kt:1
- `TtsPoolTest.kt` (9): TtsPoolTest:15, TtsPoolTest.kt:1, .firstFailureIsRethrownAndStopsTheRest():44, .percentAndLabel():68, .cancelledTakesNothingNew():61, .everyPartIsDoneOnceAndLandsAtItsIndex():16
- `TtsRetryTest.kt` (6): TtsRetryTest.kt:1, TtsRetryTest:12, .delaysGrowThenGiveUp():13, .readableMessages():19, .slowStreamIsNotAStall():32, TtsStallTest:31
- `WatchTest.kt` (9): WatchTest:12, .filtersAndMonitorKeepNewFields():62, .filterSplitsByNumbersAndFormatsLines():75, WatchTest.kt:1, .guessesLanguageByScriptAndLetters():13, .guessesLatinLanguagesByWordsAndMarks():26

## Communities (50 total, 20 thin omitted)

### Community 0 - "Recs + Topic (.w)"
Cohesion: 0.07
Nodes (12): ChannelExplorer, Config, Relevance, Ctx, DepthStat, RecEdge, RecEngine, RecGraph (+4 more)

### Community 1 - "Services + Log (Services.kt)"
Cohesion: 0.05
Nodes (9): AiJournal, AiJournalActivity, Entry, L, LumenCrashHandler, CollectService, CollectState, MonitorJob (+1 more)

### Community 2 - "Llm + Analysis (Project)"
Cohesion: 0.08
Nodes (23): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+15 more)

### Community 3 - "Text + Analysis (.markdown)"
Cohesion: 0.06
Nodes (15): Analytics, Bucket, ChannelStat, Compare, Stats, BarChartView, ChartDraw, Export (+7 more)

### Community 4 - "Seo + TopicActivity (Seo)"
Cohesion: 0.08
Nodes (3): Seo, SeoChecklist, TopicActivity

### Community 5 - "ProjectActivity + Services (t)"
Cohesion: 0.10
Nodes (10): load(), newId(), t(), RunRecord, ProjectActivity, startRecs(), seoTab(), Monitoring (+2 more)

### Community 6 - "Style + StyleTest (.measure)"
Cohesion: 0.07
Nodes (9): Store, Cue, Frame, Speech, StyleMath, StyleMetrics, styleTokenEstimate(), VideoStyle (+1 more)

### Community 7 - "SeoGraphTest + Voc (Voc)"
Cohesion: 0.06
Nodes (10): GraphAlgo, TtsRetry, Item, Msg, Voc, BackupTest, LogRedactTest, SeoGraphTest (+2 more)

### Community 8 - "HomeActivities + VideoActivity (LumenBase)"
Cohesion: 0.06
Nodes (9): onCreate(), LumenActivity, NewProjectActivity, restoreFrom(), SimpleWatcher, WizardActivity, state(), LumenBase (+1 more)

### Community 9 - "TtsActivity + Tts (TtsActivity)"
Cohesion: 0.09
Nodes (3): TtsActivity, TextWatcher, OnSeekBarChangeListener

### Community 10 - "Ui + Core (text)"
Cohesion: 0.15
Nodes (14): render(), Fmt, isLocalSource(), GraphActivity, channelRows(), channelsTab(), overviewTab(), recsTab() (+6 more)

### Community 11 - "Watch + WatchTest (.apply)"
Cohesion: 0.08
Nodes (7): filtersSummary(), KeyVault, MonitorConfig, LangGuess, Regions, WatchFilter, WatchTest

### Community 12 - "Ui + BrowserUiTest (Ui.kt)"
Cohesion: 0.06
Nodes (13): BtnStyle, FILLED, OUTLINED, TEXT, TONAL, glyphIcon(), glyphLabel(), Palette (+5 more)

### Community 13 - "LumenCoreTest + LlmParse (Test)"
Cohesion: 0.13
Nodes (6): AnalyticsTest, FmtTest, LlmParseTest, QuickAndChannelsTest, SeoPureTest, video()

### Community 14 - "Ui + ProjectActivity (dp)"
Cohesion: 0.20
Nodes (13): recRow(), btn(), chipRow(), dp(), edt(), iconBtn(), prompt(), ripple() (+5 more)

### Community 15 - "Tts + TtsActivity (EdgeTts)"
Cohesion: 0.16
Nodes (4): EdgeTts, Forbidden, TtsException, TtsVoice

### Community 16 - "Browser + Core (BrowserActivity)"
Cohesion: 0.18
Nodes (3): BrowserActivity, RecBridge, openUrl()

### Community 18 - "Ui + SettingsActivity (Col)"
Cohesion: 0.21
Nodes (4): filtersEditor(), SettingsActivity, chk(), Col

### Community 19 - "Tts + EdgeTtsTest (Tts.kt)"
Cohesion: 0.12
Nodes (3): WebSocketListener, TtsPool, TtsPrefs

### Community 20 - "Ui (Ic)"
Cohesion: 0.09
Nodes (15): Ic, ADD, BACK, BUG, COPY, DELETE, FIT, JOURNAL (+7 more)

### Community 21 - "Browser (Browser.kt)"
Cohesion: 0.11
Nodes (3): RecKeepAlive, TabRec, TabRecJs

### Community 22 - "YouTube + Text (YouTube)"
Cohesion: 0.19
Nodes (3): SearchFilters, YtQuery, YouTube

### Community 23 - "Models + Recs (JSONObject)"
Cohesion: 0.13
Nodes (5): CollectConfig, RecConfig, SeoContext, strings(), toJsonArray()

### Community 24 - "Channels + LumenCoreTest (ChannelData)"
Cohesion: 0.22
Nodes (4): ChannelData, ChannelFetch, ChannelStore, PlaylistRef

### Community 25 - "Core + Log (Core.kt)"
Cohesion: 0.14
Nodes (3): Lumen, LumenEnv, Settings

### Community 34 - "Quick (Quick.kt)"
Cohesion: 0.30
Nodes (4): chooseLinkAction(), Link, LinkActivity, YtLink

### Community 36 - "AudienceTab + AiJournal (audienceTab)"
Cohesion: 0.36
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo()

### Community 38 - "Quick (.run)"
Cohesion: 0.42
Nodes (3): QuickAnalysis, QuickState, Step

### Community 39 - "Seo + LumenCoreTest (.parse)"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

### Community 41 - "Channels + YouTube (Video)"
Cohesion: 0.36
Nodes (3): ChannelStats, Result, Video

## Knowledge Gaps
- **28 isolated node(s):** `FILLED`, `OUTLINED`, `TEXT`, `TONAL`, `ANTHROPIC` (+23 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 142 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **20 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 5` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 11`, `Community 12`, `Community 13`, `Community 14`, `Community 15`, `Community 16`, `Community 17`, `Community 18`, `Community 19`, `Community 21`, `Community 24`, `Community 25`, `Community 30`, `Community 31`, `Community 34`, `Community 35`, `Community 36`, `Community 38`?**
  _High betweenness centrality (0.313) - this node is a cross-community bridge._
- **Are the 138 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 138 INFERRED edges - model-reasoned connections that need verification._
- **What connects `FILLED`, `OUTLINED`, `TEXT` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.06923361717882266 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 41` to `Community 0`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 38`, `Community 7`, `Community 6`, `Community 8`, `Community 10`, `Community 11`, `Community 45`, `Community 13`, `Community 22`, `Community 23`, `Community 24`?**
  _High betweenness centrality (0.058) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.05289193302891933 - nodes in this community are weakly interconnected._