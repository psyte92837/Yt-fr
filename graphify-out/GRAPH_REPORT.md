# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1270 nodes · 4082 edges · 56 communities (33 shown, 23 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 511 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0: Seo + Models (Project) — 110 симв.
- Community 1: Tts + EdgeTtsTest (EdgeTts) — 88 симв.
- Community 2: Services + Log (Services.kt) — 58 симв.
- Community 3: Ui + ProjectActivity (Ui.kt) — 53 симв.
- Community 4: AiJournal + Core (Core.kt) — 47 симв.
- Community 5: ProjectActivity + Store (.render) — 47 симв.
- Community 6: Voc + SeoGraphTest (Voc) — 44 симв.
- Community 7: HomeActivities + Backup (LumenBase) — 37 симв.
- Community 8: Charts + KeyVault (KeyVault.kt) — 33 симв.
- Community 9: TtsActivity + Core (t) — 32 симв.
- Community 10: Channels + Quick (ChannelData) — 30 симв.
- Community 11: Watch + WatchTest (jsonarray) — 28 симв.
- Community 12: Quick + LumenCoreTest (.run) — 28 симв.
- Community 13: Browser + Backup (Browser.kt) — 27 симв.
- Community 14: Graph + GraphAlgo (Graph.kt) — 25 симв.
- Community 15: Analysis + Text (.analyticsTab) — 24 симв.
- Community 16: SettingsActivity + Ui (.logs) — 24 симв.
- Community 17: Ui (Ic) — 23 симв.
- Community 18: Core + OverviewTabs (.render) — 22 симв.
- Community 19: Recs + Text (RecEngine) — 22 симв.
- Community 20: TtsActivity + Ui (TtsActivity.kt) — 22 симв.
- Community 21: LumenCoreTest + Channels (Test) — 21 симв.
- Community 22: YouTube + Models (Video) — 21 симв.
- Community 23: Ui + HomeActivities (Col) — 20 симв.
- Community 24: Export + Text (.videos) — 20 симв.
- Community 25: Topic + Log (.w) — 19 симв.
- Community 26: Recs + Store (RecNode) — 19 симв.
- Community 27: LumenCoreTest + LlmParse (LlmException) — 18 симв.
- Community 28: Text + TextTest (Text.kt) — 18 симв.
- Community 30: EdgeGec (EdgeGec) — 17 симв.
- Community 29: Ui + ChannelActivity (toast) — 17 симв.
- Community 31: TopicActivity + Ui (.render) — 16 симв.
- Community 32: BrowserUiTest + Browser (.paintRec) — 16 симв.
- Community 33: TopicFilterTest + GraphAlgo (.keywords) — 15 симв.
- Community 34: Llm (Provider) — 15 симв.
- Community 35: Style + StyleTest (.measure) — 15 симв.
- Community 37: Llm + Seo (Llm.kt) — 14 симв.
- Community 36: AiJournal + AudienceTab (audienceTab) — 14 симв.
- Community 38: YouTube (YouTube.kt) — 14 симв.
- Community 39: Browser (BrowserActivity) — 13 симв.
- Community 40: Browser (.startRec) — 13 симв.
- Community 41: Style (Style.kt) — 12 симв.
- Community 42: Browser + Ui (WebChromeClient) — 10 симв.
- Community 43: Seo + LumenCoreTest (.parse) — 10 симв.
- Community 44: Style + StyleTest (StyleMetrics) — 10 симв.
- Community 45: StyleTest + Style (StyleTest) — 10 симв.
- Community 46: Browser (.finishRec) — 9 симв.
- Community 47: Browser (WebViewClient) — 8 симв.
- Community 48: Models + Seo (.comments) — 7 симв.
- Community 49: Style + StyleTest (.frameColoursAndCuts) — 7 симв.
- Community 50: LumenCoreTest (RecGraphTest) — 6 симв.
- Community 52: LumenCoreTest + Text (.array) — 5 симв.
- Community 53: Ui (BtnStyle) — 5 симв.
- Community 51: Browser (.onNewIntent) — 5 симв.
- Community 54: Ui (OnItemSelectedListener) — 4 симв.
- Community 55: Channels (ChannelStats) — 3 симв.

## God Nodes (most connected - your core abstractions)
1. `t()` - 135 edges
2. `Video` - 60 edges
3. `Project` - 50 edges
4. `Col` - 50 edges
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
- `Channels.kt` (26): ChannelData:28, Channels.kt:1, .run():270, .fetch():179, .key():152, .get():143
- `Charts.kt` (7): Charts.kt:1, BarChartView:78, .bars():15, .hbars():59, .onDraw():89, ChartDraw:14
- `Core.kt` (18): t():128, .async():43, .dateTime():134, Core.kt:1, sha1():163, .num():145
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
- `Quick.kt` (28): .run():164, Quick.kt:1, .summary():126, .start():373, .classify():23, .onCreate():422
- `Recs.kt` (40): RecNode:15, RecEngine:153, RecGraph:69, Ctx:156, .run():324, .load():117
- `RecsTab.kt` (5): recsTab():37, recRow():262, RecsTab.kt:1, startRecs():15, state():35
- `Seo.kt` (53): Seo:135, .aiJson():179, .extractInsights():270, .qualify():506, .ideas():588, .pipeline():156
- `SeoTab.kt` (2): seoTab():10, SeoTab.kt:1
- `Services.kt` (28): .runOnce():312, Services.kt:1, .onStartCommand():89, CollectService:86, .run():162, .apply():292
- `SettingsActivity.kt` (14): .logs():340, .backup():226, .render():30, SettingsActivity:16, .ai():83, .onActivityResult():283
- `Store.kt` (26): Store:21, .project():50, .videos():73, .save():55, .mergeVideos():103, .writeFile():128
- `Style.kt` (38): .measure():338, styleCard():513, StyleMath:24, Style.kt:1, .ai():464, StyleMetrics:220
- `Text.kt` (24): .firstObject():10, .videoIdFromUrl():246, .row():185, Text.kt:1, .top():134, .mini():265
- `Topic.kt` (15): .ensure():88, .judgeChannels():114, TopicFilter:31, .keywordsOf():79, .load():77, TopicProfile:11
- `TopicActivity.kt` (13): .render():65, TopicActivity:14, .onActivityResult():327, .run():37, .answerLink():58, TopicActivity.kt:1
- `Tts.kt` (54): EdgeTts:46, Tts.kt:1, .fromFile():581, .synthesize():320, TtsText:470, .one():399
- `TtsActivity.kt` (38): TtsActivity:23, .build():78, TtsActivity.kt:1, .play():295, .loadFile():377, .renderVoices():250
- `Ui.kt` (84): Ui.kt:1, Col:487, toast():146, text():361, .add():494, dp():141
- `VideoActivity.kt` (8): .render():42, VideoActivity:12, .collectNow():158, .summarise():186, .onCreate():19, VideoActivity.kt:1
- `Voc.kt` (12): Voc:11, .extract():84, .similarity():148, .isSpam():59, .normalize():54, .quoteFound():135
- `Watch.kt` (15): .apply():155, .guess():32, WatchFilter:124, LangGuess:14, .split():148, Regions:86
- `YouTube.kt` (17): YouTube.kt:1, YouTube:25, .collect():227, .search():91, .importList():137, .applyStreamInfo():204
- `BackupTest.kt` (3): BackupTest.kt:1, .autoBackupIsDueOnlyWithDataAfterTheInterval():14, BackupTest:11
- `BrowserUiTest.kt` (9): BrowserUiTest:13, BrowserUiTest.kt:1, .appBarGlyphsHaveIconsAndLabels():58, .filtersSummaryListsOnlyNonDefaults():69, .addressKeepsLinksAndCompletesHosts():14, .addressSearchesPlainText():23
- `EdgeTtsTest.kt` (17): EdgeTtsTest:13, .messagesAndTimestamp():100, EdgeTtsTest.kt:1, .ssmlEscapesAndFormatsProsody():25, .binaryFrameAudioExtraction():62, .languageDetectionAndVoicePick():87
- `LumenCoreTest.kt` (49): LumenCoreTest.kt:1, .modelsRoundTrip():437, video():16, LlmParseTest:182, RecGraphTest:74, .graph():75
- `SeoGraphTest.kt` (12): SeoGraphTest:12, SeoGraphTest.kt:1, .quoteVerificationAndTimestamps():72, .relevanceStemsRussianAndEnglish():34, .vocClassification():43, .secretsAreRemoved():100
- `StyleTest.kt` (13): StyleTest:14, StyleTest.kt:1, .frameColoursAndCuts():70, .descriptionCounters():60, .paletteKeepsDominantColours():85, .metricsJsonRoundTrip():95
- `TextTest.kt` (8): TextTest:13, TextTest.kt:1, .youtubeHelpers():49, .wordsSkipStopWords():34, .vttRemovesTagsAndRollingDuplicates():19, .chunksRespectMaxLength():42
- `TopicFilterTest.kt` (6): TopicFilterTest:7, .parsesTopicAndIndexes():8, .channelKeywordFallback():35, .prunesOffTopicNodesAndEdges():20, .linksLoseQueryParameters():42, TopicFilterTest.kt:1
- `TtsRetryTest.kt` (4): TtsRetryTest.kt:1, TtsRetryTest:12, .delaysGrowThenGiveUp():13, .readableMessages():19
- `WatchTest.kt` (9): WatchTest:12, .filtersAndMonitorKeepNewFields():62, .filterSplitsByNumbersAndFormatsLines():75, WatchTest.kt:1, .guessesLanguageByScriptAndLetters():13, .guessesLatinLanguagesByWordsAndMarks():26

## Communities (56 total, 23 thin omitted)

### Community 0 - "Seo + Models (Project)"
Cohesion: 0.06
Nodes (13): AiTasks, sha1(), Prompts, AiResult, CollectConfig, Project, RecConfig, RunRecord (+5 more)

### Community 1 - "Tts + EdgeTtsTest (EdgeTts)"
Cohesion: 0.05
Nodes (11): EdgeTts, WebSocketListener, Forbidden, TtsException, TtsOptions, TtsPrefs, TtsRetry, TtsText (+3 more)

### Community 2 - "Services + Log (Services.kt)"
Cohesion: 0.07
Nodes (6): L, LumenCrashHandler, CollectService, CollectState, MonitorJob, Notify

### Community 3 - "Ui + ProjectActivity (Ui.kt)"
Cohesion: 0.10
Nodes (18): recRow(), recsTab(), btn(), chip(), chipRow(), chk(), dp(), iconBtn() (+10 more)

### Community 4 - "AiJournal + Core (Core.kt)"
Cohesion: 0.06
Nodes (10): AiJournal, AiJournalActivity, Entry, onCreate(), Lumen, LumenEnv, newId(), Settings (+2 more)

### Community 5 - "ProjectActivity + Store (.render)"
Cohesion: 0.12
Nodes (3): ProjectActivity, Monitoring, Store

### Community 6 - "Voc + SeoGraphTest (Voc)"
Cohesion: 0.09
Nodes (7): GraphAlgo, Item, Msg, Voc, BackupTest, LogRedactTest, SeoGraphTest

### Community 7 - "HomeActivities + Backup (LumenBase)"
Cohesion: 0.09
Nodes (8): AutoBackup, LumenActivity, NewProjectActivity, restoreFrom(), SimpleWatcher, WizardActivity, LumenBase, Palette

### Community 8 - "Charts + KeyVault (KeyVault.kt)"
Cohesion: 0.08
Nodes (3): BarChartView, ChartDraw, KeyVault

### Community 10 - "Channels + Quick (ChannelData)"
Cohesion: 0.14
Nodes (7): ChannelData, ChannelExplorer, ChannelFetch, ChannelStore, Config, PlaylistRef, ChannelAi

### Community 11 - "Watch + WatchTest (jsonarray)"
Cohesion: 0.12
Nodes (6): filtersSummary(), MonitorConfig, LangGuess, Regions, WatchFilter, WatchTest

### Community 12 - "Quick + LumenCoreTest (.run)"
Cohesion: 0.15
Nodes (8): chooseLinkAction(), Link, LinkActivity, QuickAnalysis, QuickConfig, QuickState, Step, YtLink

### Community 15 - "Analysis + Text (.analyticsTab)"
Cohesion: 0.20
Nodes (6): Analytics, Bucket, ChannelStat, Compare, Stats, Words

### Community 17 - "Ui (Ic)"
Cohesion: 0.09
Nodes (15): Ic, ADD, BACK, BUG, COPY, DELETE, FIT, JOURNAL (+7 more)

### Community 18 - "Core + OverviewTabs (.render)"
Cohesion: 0.21
Nodes (10): Fmt, isLocalSource(), channelRows(), channelsTab(), overviewTab(), styleCard(), Runnable, openVideo() (+2 more)

### Community 19 - "Recs + Text (RecEngine)"
Cohesion: 0.26
Nodes (3): Ctx, DepthStat, RecEngine

### Community 20 - "TtsActivity + Ui (TtsActivity.kt)"
Cohesion: 0.10
Nodes (3): TextWatcher, OnSeekBarChangeListener, ArrayAdapter

### Community 21 - "LumenCoreTest + Channels (Test)"
Cohesion: 0.18
Nodes (5): AnalyticsTest, FmtTest, QuickAndChannelsTest, SeoPureTest, video()

### Community 22 - "YouTube + Models (Video)"
Cohesion: 0.22
Nodes (3): SearchFilters, Video, YouTube

### Community 24 - "Export + Text (.videos)"
Cohesion: 0.20
Nodes (3): Export, Csv, VideoActivity

### Community 26 - "Recs + Store (RecNode)"
Cohesion: 0.18
Nodes (3): RecEdge, RecGraph, RecNode

### Community 27 - "LumenCoreTest + LlmParse (LlmException)"
Cohesion: 0.21
Nodes (4): LlmException, LlmReply, LlmParse, LlmParseTest

### Community 28 - "Text + TextTest (Text.kt)"
Cohesion: 0.17
Nodes (6): Chunks, JsonText, ThumbUrl, Vtt, YtQuery, TextTest

### Community 29 - "Ui + ChannelActivity (toast)"
Cohesion: 0.32
Nodes (5): load(), render(), seoTab(), confirm(), toast()

### Community 31 - "TopicActivity + Ui (.render)"
Cohesion: 0.22
Nodes (3): TopicActivity, edt(), prompt()

### Community 32 - "BrowserUiTest + Browser (.paintRec)"
Cohesion: 0.21
Nodes (4): BrowserText, glyphIcon(), glyphLabel(), BrowserUiTest

### Community 34 - "Llm (Provider)"
Cohesion: 0.17
Nodes (10): Llm, Provider, ANTHROPIC, CUSTOM, DEEPSEEK, GEMINI, GROQ, OLLAMA (+2 more)

### Community 35 - "Style + StyleTest (.measure)"
Cohesion: 0.25
Nodes (3): Cue, Speech, StyleMath

### Community 36 - "AiJournal + AudienceTab (audienceTab)"
Cohesion: 0.25
Nodes (4): audienceTab(), confidenceLabel(), insightCard(), vocLabel()

### Community 37 - "Llm + Seo (Llm.kt)"
Cohesion: 0.15
Nodes (5): ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, SeoChecklist

### Community 40 - "Browser (.startRec)"
Cohesion: 0.22
Nodes (3): RecKeepAlive, TabRecJs, TabWriter

### Community 43 - "Seo + LumenCoreTest (.parse)"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

### Community 53 - "Ui (BtnStyle)"
Cohesion: 0.40
Nodes (5): BtnStyle, FILLED, OUTLINED, TEXT, TONAL

## Knowledge Gaps
- **28 isolated node(s):** `ADD`, `BACK`, `BUG`, `COPY`, `DELETE` (+23 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 138 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **23 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `t()` connect `Community 9` to `Community 0`, `Community 1`, `Community 2`, `Community 3`, `Community 4`, `Community 5`, `Community 7`, `Community 10`, `Community 11`, `Community 12`, `Community 14`, `Community 15`, `Community 16`, `Community 18`, `Community 19`, `Community 21`, `Community 23`, `Community 24`, `Community 25`, `Community 29`, `Community 31`, `Community 32`, `Community 35`, `Community 36`, `Community 39`, `Community 40`, `Community 41`, `Community 44`, `Community 46`, `Community 47`, `Community 51`?**
  _High betweenness centrality (0.348) - this node is a cross-community bridge._
- **Are the 134 inferred relationships involving `t()` (e.g. with `.full()` and `.statusLabel()`) actually correct?**
  _`t()` has 134 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ADD`, `BACK`, `BUG` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05721434528773978 - nodes in this community are weakly interconnected._
- **Why does `Video` connect `Community 22` to `Community 0`, `Community 33`, `Community 3`, `Community 35`, `Community 5`, `Community 6`, `Community 10`, `Community 11`, `Community 12`, `Community 15`, `Community 48`, `Community 18`, `Community 19`, `Community 21`, `Community 55`, `Community 24`, `Community 25`?**
  _High betweenness centrality (0.066) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `Video` (e.g. with `.sourceUrls()` and `video()`) actually correct?**
  _`Video` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.05041797283176593 - nodes in this community are weakly interconnected._