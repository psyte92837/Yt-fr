# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1189 nodes · 3799 edges · 38 communities (26 shown, 12 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 476 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0: Recs + Channels (RecNode) — 91 симв.
- Community 1: Text + Analysis (Video) — 86 симв.
- Community 2: Llm + Analysis (.aiTab) — 84 симв.
- Community 3: Tts + EdgeTtsTest (EdgeTts) — 78 симв.
- Community 4: Seo + TopicActivity (Project) — 72 симв.
- Community 5: ProjectActivity + Store (t) — 71 симв.
- Community 6: Services + Log (.runOnce) — 68 симв.
- Community 7: LumenCoreTest + Seo (Test) — 56 симв.
- Community 8: Watch + BrowserUiTest (BrowserUiTest) — 54 симв.
- Community 9: TtsActivity + Tts (TtsActivity) — 46 симв.
- Community 10: Graph + KeyVault (Graph.kt) — 40 симв.
- Community 11: Ui (Ui.kt) — 36 симв.
- Community 12: Ui + HomeActivities (Col) — 31 симв.
- Community 13: HomeActivities + Backup (HomeActivities.kt) — 29 симв.
- Community 14: VideoActivity + Core (.render) — 28 симв.
- Community 15: Voc + SeoGraphTest (Voc) — 27 симв.
- Community 16: Models + Recs (JSONObject) — 27 симв.
- Community 17: Ui (Ic) — 23 симв.
- Community 18: Browser + Backup (Browser.kt) — 21 симв.
- Community 20: Core + TtsActivity (Core.kt) — 20 симв.
- Community 19: Ui + Browser (dp) — 20 симв.
- Community 21: AiJournal (.render) — 19 симв.
- Community 22: Browser + Ui (BrowserActivity) — 18 симв.
- Community 23: YouTube + Models (.collect) — 18 симв.
- Community 24: EdgeGec (EdgeGec) — 17 симв.
- Community 25: SettingsActivity + Ui (.render) — 16 симв.
- Community 26: Quick (Quick.kt) — 12 симв.
- Community 27: Browser (.startRec) — 11 симв.
- Community 28: YouTube (YouTube.kt) — 11 симв.
- Community 29: Quick (.run) — 10 симв.
- Community 30: AudienceTab + Ui (audienceTab) — 8 симв.
- Community 31: Browser (.onNewIntent) — 7 симв.
- Community 32: Store (Store.kt) — 7 симв.
- Community 33: Browser (WebViewClient) — 6 симв.
- Community 35: Quick (QuickConfig) — 6 симв.
- Community 34: ProjectActivity (ProjectActivity.kt) — 6 симв.
- Community 36: Ui (BtnStyle) — 5 симв.
- Community 37: Ui (OnItemSelectedListener) — 4 симв.

## God Nodes (most connected - your core abstractions)
1. `t()` - 125 edges
2. `Video` - 56 edges
3. `Col` - 49 edges
4. `Project` - 49 edges
5. `Seo` - 46 edges
6. `text()` - 41 edges
7. `toast()` - 41 edges
8. `TtsActivity` - 37 edges
9. `overviewTab()` - 33 edges
10. `dp()` - 33 edges

## Surprising Connections (you probably didn't know these)
- `recsTab()` --calls--> `BarChartView`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Charts.kt
- `video()` --calls--> `Video`  [INFERRED]
  app/src/test/java/org/schabi/newpipe/lumen/LumenCoreTest.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt
- `recsTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/RecsTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `seoTab()` --calls--> `SimpleWatcher`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/SeoTab.kt → app/src/main/java/org/schabi/newpipe/lumen/HomeActivities.kt
- `audienceTab()` --calls--> `AiResult`  [INFERRED]
  app/src/main/java/org/schabi/newpipe/lumen/AudienceTab.kt → app/src/main/java/org/schabi/newpipe/lumen/Models.kt

## Import Cycles
- None detected.

## Карта файлов (символы по числу связей; строка для Read offset)
- `AiJournal.kt` (16): .render():134, AiJournal:17, .list():76, Entry:22, .statusLabel():103, .full():109
- `Analysis.kt` (31): AiTasks:133, .projectSummary():222, .videoSummary():173, .sentiment():243, .requireProvider():141, .clusters():233
- `AudienceTab.kt` (5): audienceTab():30, insightCard():164, AudienceTab.kt:1, confidenceLabel():23, vocLabel():10
- `Backup.kt` (10): AutoBackup:21, Backup.kt:1, .maybe():49, .restore():139, .runNow():67, .writeToTree():82
- `Browser.kt` (47): Browser.kt:1, BrowserActivity:207, .onCreate():224, .finishRec():490, WebChromeClient:259, .paintRec():431
- `ChannelActivity.kt` (3): ChannelActivity.kt:1, load():31, onCreate():23
- `Channels.kt` (26): ChannelData:28, Channels.kt:1, .run():270, .fetch():179, .key():152, .get():143
- `Charts.kt` (7): Charts.kt:1, BarChartView:78, .bars():15, .hbars():59, .onDraw():89, ChartDraw:14
- `Core.kt` (18): t():128, .async():43, Core.kt:1, .dateTime():134, sha1():163, .isoDay():137
- `EdgeGec.kt` (15): EdgeGec:11, .init():76, .sha256():93, .cs64():120, .expand():66, .rounds():53
- `Export.kt` (12): .markdown():121, .write():18, Export:17, .pdf():214, Export.kt:1, .vocCsv():64
- `Graph.kt` (14): Graph.kt:1, .build():235, GraphView:24, .showNode():285, .onCreate():225, GraphActivity:215
- `GraphAlgo.kt` (8): .keywords():119, .matches():121, GraphAlgo.kt:1, Relevance:116, .layout():38, .pageRank():17
- `HomeActivities.kt` (23): .render():413, .render():137, HomeActivities.kt:1, filtersEditor():15, .projectMenu():226, .onCreate():103
- `KeyVault.kt` (6): KeyVault.kt:1, KeyVault:21, .secretKey():25, .has():63, .get():51, .put():39
- `Llm.kt` (31): .complete():132, Provider:15, .active():60, .get():277, LlmException:71, Llm.kt:1
- `LlmParse.kt` (7): .openAi():22, LlmParse:11, .anthropic():45, .gemini():60, .errorMessage():12, .models():85
- `Log.kt` (20): L:21, .w():69, .i():68, .report():166, .e():70, Log.kt:1
- `Models.kt` (35): Video:256, Project:192, AiResult:346, Models.kt:1, SearchFilters:19, RunRecord:248
- `OverviewTabs.kt` (4): overviewTab():10, channelsTab():154, channelRows():134, OverviewTabs.kt:1
- `ProjectActivity.kt` (29): .render():122, ProjectActivity:21, .analyticsTab():564, .aiTab():729, .settingsTab():872, .importList():305
- `Quick.kt` (27): .run():160, Quick.kt:1, .summary():123, .start():350, .onCreate():368, .classify():22
- `Recs.kt` (40): RecNode:15, RecEngine:153, RecGraph:69, Ctx:156, .run():324, .load():117
- `RecsTab.kt` (5): recsTab():37, recRow():262, RecsTab.kt:1, startRecs():15, state():35
- `Seo.kt` (53): Seo:135, .aiJson():179, .extractInsights():270, .qualify():506, .ideas():588, .pipeline():156
- `SeoTab.kt` (2): seoTab():10, SeoTab.kt:1
- `Services.kt` (27): .runOnce():298, Services.kt:1, .onStartCommand():88, .run():156, .apply():278, CollectService:85
- `SettingsActivity.kt` (14): .logs():340, .backup():226, .render():30, SettingsActivity:16, .ai():83, .onActivityResult():283
- `Store.kt` (26): Store:21, .project():50, .videos():73, .save():55, .mergeVideos():103, .comments():139
- `Text.kt` (22): .firstObject():10, .videoIdFromUrl():246, .row():185, .top():134, Text.kt:1, Words:111
- `Topic.kt` (15): .ensure():88, .judgeChannels():114, TopicFilter:31, .keywordsOf():79, .load():77, TopicProfile:11
- `TopicActivity.kt` (13): .render():65, TopicActivity:14, .onActivityResult():327, .run():37, .answerLink():58, TopicActivity.kt:1
- `Tts.kt` (47): EdgeTts:46, Tts.kt:1, .fromFile():505, TtsText:394, .one():334, .voices():262
- `TtsActivity.kt` (38): TtsActivity:23, .build():78, TtsActivity.kt:1, .play():295, .loadFile():377, .renderVoices():250
- `Ui.kt` (83): Ui.kt:1, Col:487, text():361, toast():146, .add():494, dp():141
- `VideoActivity.kt` (7): .render():36, .collectNow():150, .summarise():178, VideoActivity:12, .onCreate():19, VideoActivity.kt:1
- `Voc.kt` (12): Voc:11, .extract():84, .similarity():148, .isSpam():59, .normalize():54, .quoteFound():135
- `Watch.kt` (15): .apply():155, .guess():32, WatchFilter:124, LangGuess:14, .split():148, Regions:86
- `YouTube.kt` (15): YouTube.kt:1, .collect():223, YouTube:25, .search():91, .importList():137, .comments():274
- `BackupTest.kt` (3): BackupTest.kt:1, .autoBackupIsDueOnlyWithDataAfterTheInterval():14, BackupTest:11
- `BrowserUiTest.kt` (9): BrowserUiTest:13, BrowserUiTest.kt:1, .appBarGlyphsHaveIconsAndLabels():58, .filtersSummaryListsOnlyNonDefaults():69, .addressKeepsLinksAndCompletesHosts():14, .addressSearchesPlainText():23
- `EdgeTtsTest.kt` (17): EdgeTtsTest:13, .messagesAndTimestamp():98, EdgeTtsTest.kt:1, .ssmlEscapesAndFormatsProsody():25, .binaryFrameAudioExtraction():60, .languageDetectionAndVoicePick():85
- `LumenCoreTest.kt` (49): LumenCoreTest.kt:1, .modelsRoundTrip():437, video():16, LlmParseTest:182, RecGraphTest:74, .graph():75
- `SeoGraphTest.kt` (12): SeoGraphTest:12, SeoGraphTest.kt:1, .quoteVerificationAndTimestamps():72, .relevanceStemsRussianAndEnglish():34, .vocClassification():43, .csvParserHandlesQuotes():83
- `TextTest.kt` (8): TextTest:13, TextTest.kt:1, .youtubeHelpers():49, .vttRemovesTagsAndRollingDuplicates():19, .wordsSkipStopWords():34, .chunksRespectMaxLength():42
- `TopicFilterTest.kt` (6): TopicFilterTest:7, .channelKeywordFallback():35, .prunesOffTopicNodesAndEdges():20, .parsesTopicAndIndexes():8, .linksLoseQueryParameters():42, TopicFilterTest.kt:1
- `WatchTest.kt` (9): WatchTest:12, .filtersAndMonitorKeepNewFields():62, .filterSplitsByNumbersAndFormatsLines():75, WatchTest.kt:1, .guessesLanguageByScriptAndLetters():13, .guessesLatinLanguagesByWordsAndMarks():26

## Communities (38 total, 12 thin omitted)

### Community 0 - "Recs + Channels (RecNode)"
Cohesion: 0.05
Nodes (17): onCreate(), ChannelData, ChannelExplorer, ChannelFetch, ChannelStats, ChannelStore, Config, PlaylistRef (+9 more)

### Community 1 - "Text + Analysis (Video)"
Cohesion: 0.05
Nodes (17): Analytics, Bucket, ChannelStat, Compare, Stats, BarChartView, ChartDraw, Export (+9 more)

### Community 2 - "Llm + Analysis (.aiTab)"
Cohesion: 0.06
Nodes (24): AiTasks, sha1(), ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, Llm, LlmException (+16 more)

### Community 3 - "Tts + EdgeTtsTest (EdgeTts)"
Cohesion: 0.06
Nodes (9): EdgeTts, WebSocketListener, Forbidden, TtsException, TtsOptions, TtsPrefs, TtsText, TtsVoice (+1 more)

### Community 4 - "Seo + TopicActivity (Project)"
Cohesion: 0.08
Nodes (4): Project, Seo, SeoChecklist, TopicActivity

### Community 5 - "ProjectActivity + Store (t)"
Cohesion: 0.09
Nodes (13): RecBridge, load(), newId(), t(), LumenActivity, ProjectActivity, startRecs(), seoTab() (+5 more)

### Community 6 - "Services + Log (.runOnce)"
Cohesion: 0.06
Nodes (8): L, LumenCrashHandler, CollectService, CollectState, Monitoring, MonitorJob, Notify, LogRedactTest

### Community 7 - "LumenCoreTest + Seo (Test)"
Cohesion: 0.07
Nodes (14): strings(), ImportParser, Parsed, JsonSalvage, AnalyticsTest, FmtTest, ImportParserTest, LlmParseTest (+6 more)

### Community 8 - "Watch + BrowserUiTest (BrowserUiTest)"
Cohesion: 0.07
Nodes (10): BrowserText, filtersSummary(), glyphIcon(), glyphLabel(), LangGuess, Regions, WatchFilter, BackupTest (+2 more)

### Community 9 - "TtsActivity + Tts (TtsActivity)"
Cohesion: 0.10
Nodes (3): TtsActivity, TextWatcher, OnSeekBarChangeListener

### Community 10 - "Graph + KeyVault (Graph.kt)"
Cohesion: 0.07
Nodes (3): GraphActivity, GraphView, KeyVault

### Community 11 - "Ui (Ui.kt)"
Cohesion: 0.08
Nodes (7): chipRow(), LumenBase, Palette, rowOf(), scrollPage(), ArrayAdapter, Type

### Community 12 - "Ui + HomeActivities (Col)"
Cohesion: 0.21
Nodes (9): filtersEditor(), channelRows(), channelsTab(), overviewTab(), recsTab(), btn(), chk(), Col (+1 more)

### Community 13 - "HomeActivities + Backup (HomeActivities.kt)"
Cohesion: 0.12
Nodes (5): AutoBackup, NewProjectActivity, restoreFrom(), SimpleWatcher, WizardActivity

### Community 14 - "VideoActivity + Core (.render)"
Cohesion: 0.13
Nodes (5): Fmt, recRow(), state(), Thumbs, VideoActivity

### Community 15 - "Voc + SeoGraphTest (Voc)"
Cohesion: 0.14
Nodes (5): GraphAlgo, Item, Msg, Voc, SeoGraphTest

### Community 16 - "Models + Recs (JSONObject)"
Cohesion: 0.11
Nodes (6): CollectConfig, MonitorConfig, RecConfig, RunRecord, SeoContext, toJsonArray()

### Community 17 - "Ui (Ic)"
Cohesion: 0.09
Nodes (15): Ic, ADD, BACK, BUG, COPY, DELETE, FIT, JOURNAL (+7 more)

### Community 19 - "Ui + Browser (dp)"
Cohesion: 0.27
Nodes (9): chip(), dp(), edt(), iconBtn(), prompt(), ripple(), roundBg(), spin() (+1 more)

### Community 20 - "Core + TtsActivity (Core.kt)"
Cohesion: 0.13
Nodes (4): isLocalSource(), Lumen, LumenEnv, Settings

### Community 21 - "AiJournal (.render)"
Cohesion: 0.23
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 22 - "Browser + Ui (BrowserActivity)"
Cohesion: 0.15
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 26 - "Quick (Quick.kt)"
Cohesion: 0.27
Nodes (3): Link, LinkActivity, YtLink

### Community 29 - "Quick (.run)"
Cohesion: 0.42
Nodes (3): QuickAnalysis, QuickState, Step

### Community 30 - "AudienceTab + Ui (audienceTab)"
Cohesion: 0.50
Nodes (5): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), openVideo()

### Community 36 - "Ui (BtnStyle)"
Cohesion: 0.40
Nodes (5): BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **28 isolated node(s):** `ADD`, `BACK`, `BUG`, `COPY`, `DELETE` (+23 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 135 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **12 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 5` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 6`, `Community 7`, `Community 8`, `Community 9`, `Community 10`, `Community 12`, `Community 13`, `Community 14`, `Community 19`, `Community 20`, `Community 21`, `Community 25`, `Community 26`, `Community 27`, `Community 29`, `Community 30`, `Community 31`?**
  _High betweenness centrality (0.358) - this node is a cross-community bridge._
- **Are the 124 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 124 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ADD`, `BACK`, `BUG` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05396825396825397 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 1` to `Community 0`, `Community 2`, `Community 4`, `Community 5`, `Community 7`, `Community 8`, `Community 14`, `Community 15`, `Community 16`, `Community 23`, `Community 29`?**
  _High betweenness centrality (0.051) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.0506155950752394 - nodes in this community are weakly interconnected._