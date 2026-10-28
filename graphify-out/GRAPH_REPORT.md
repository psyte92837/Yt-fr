# Graph Report - src  (2026-10-28)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1259 nodes · 4057 edges · 53 communities (28 shown, 25 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 509 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0: Ui + ProjectActivity (t) — 177 симв.
- Community 1: Seo + Analysis (Video) — 120 симв.
- Community 2: Services + Log (.runOnce) — 80 симв.
- Community 3: Tts + EdgeTtsTest (EdgeTts) — 79 симв.
- Community 4: Text + Export (.markdown) — 60 симв.
- Community 5: TtsActivity + Tts (TtsActivity) — 50 симв.
- Community 6: Style + StyleTest (.measure) — 48 симв.
- Community 7: Voc + SeoGraphTest (Voc) — 48 симв.
- Community 8: Channels (ChannelData) — 30 симв.
- Community 9: Quick (.run) — 28 симв.
- Community 10: Recs + Text (RecEngine) — 25 симв.
- Community 11: Graph + GraphAlgo (Graph.kt) — 23 симв.
- Community 12: Ui (Ui.kt) — 23 симв.
- Community 13: Models + Recs (JSONObject) — 22 симв.
- Community 14: LumenCoreTest (Test) — 22 симв.
- Community 15: YouTube + Models (YouTube) — 21 симв.
- Community 16: Recs + LumenCoreTest (RecNode) — 19 симв.
- Community 18: Topic + Channels (.w) — 18 симв.
- Community 17: ChannelActivity + RecsTab (ProjectActivity.kt) — 18 симв.
- Community 19: Browser + Ui (BrowserActivity) — 17 симв.
- Community 20: LumenCoreTest + LlmParse (.completeRaw) — 17 симв.
- Community 21: Core + Log (Core.kt) — 17 симв.
- Community 22: EdgeGec (EdgeGec) — 17 симв.
- Community 23: Browser (.startRec) — 16 симв.
- Community 24: Watch + WatchTest (WatchTest) — 16 симв.
- Community 25: Browser + Backup (Browser.kt) — 15 симв.
- Community 26: Llm + Seo (Llm.kt) — 15 симв.
- Community 27: Llm (Provider) — 15 симв.
- Community 28: KeyVault (KeyVault.kt) — 14 симв.
- Community 29: Ui (Ic) — 14 симв.
- Community 30: AiJournal (AiJournal) — 13 симв.
- Community 31: BrowserUiTest + Browser (BrowserUiTest) — 13 симв.
- Community 32: TopicActivity + SettingsActivity (alertdialog) — 11 симв.
- Community 33: TopicFilterTest + Topic (.matches) — 10 симв.
- Community 34: Seo + LumenCoreTest (.parse) — 10 симв.
- Community 35: HomeActivities (HomeActivities.kt) — 9 симв.
- Community 36: Models + HomeActivities (SearchFilters) — 9 симв.
- Community 37: HomeActivities (restoreFrom) — 9 симв.
- Community 38: Ui (IconDrawable) — 9 симв.
- Community 39: YouTube (YouTube.kt) — 9 симв.
- Community 40: Browser (WebViewClient) — 8 симв.
- Community 41: Browser (RecKeepAlive) — 7 симв.
- Community 43: Watch + WatchTest (.apply) — 7 симв.
- Community 42: Store (Store.kt) — 7 симв.
- Community 44: Browser (.finishRec) — 6 симв.
- Community 45: Models + LumenCoreTest (Comment) — 6 симв.
- Community 46: LumenCoreTest (RecGraphTest) — 6 симв.
- Community 47: LumenCoreTest + Text (.array) — 5 симв.
- Community 48: Ui (BtnStyle) — 5 симв.
- Community 49: VideoActivity (VideoActivity) — 5 симв.
- Community 50: Ui (OnItemSelectedListener) — 4 симв.
- Community 51: Ui (.onCreate) — 4 симв.
- Community 52: Ui (ArrayAdapter) — 3 симв.

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
- `ProjectActivity.kt` (29): .render():122, ProjectActivity:21, .analyticsTab():564, .aiTab():728, .settingsTab():871, .importList():305
- `Quick.kt` (28): .run():164, Quick.kt:1, .summary():126, .start():373, .classify():23, .onCreate():422
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
- `TopicActivity.kt` (13): .render():65, TopicActivity:14, .onActivityResult():327, .run():37, .answerLink():58, TopicActivity.kt:1
- `Tts.kt` (48): EdgeTts:46, Tts.kt:1, .fromFile():525, TtsText:414, .one():343, .synthesize():316
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
- `StyleTest.kt` (13): StyleTest:14, StyleTest.kt:1, .frameColoursAndCuts():70, .descriptionCounters():60, .metricsJsonRoundTrip():95, .paletteKeepsDominantColours():85
- `TextTest.kt` (8): TextTest:13, TextTest.kt:1, .youtubeHelpers():49, .vttRemovesTagsAndRollingDuplicates():19, .wordsSkipStopWords():34, .chunksRespectMaxLength():42
- `TopicFilterTest.kt` (6): TopicFilterTest:7, .channelKeywordFallback():35, .parsesTopicAndIndexes():8, .prunesOffTopicNodesAndEdges():20, .linksLoseQueryParameters():42, TopicFilterTest.kt:1
- `WatchTest.kt` (9): WatchTest:12, .filtersAndMonitorKeepNewFields():62, .filterSplitsByNumbersAndFormatsLines():75, WatchTest.kt:1, .guessesLanguageByScriptAndLetters():13, .guessesLatinLanguagesByWordsAndMarks():26

## Communities (53 total, 25 thin omitted)

### Community 0 - "Ui + ProjectActivity (t)"
Cohesion: 0.05
Nodes (47): audienceTab(), confidenceLabel(), insightCard(), vocLabel(), load(), render(), Fmt, isLocalSource() (+39 more)

### Community 1 - "Seo + Analysis (Video)"
Cohesion: 0.06
Nodes (17): AiTasks, Analytics, Bucket, ChannelStat, Compare, Stats, newId(), sha1() (+9 more)

### Community 2 - "Services + Log (.runOnce)"
Cohesion: 0.05
Nodes (8): AutoBackup, L, LumenCrashHandler, CollectService, CollectState, Monitoring, MonitorJob, Notify

### Community 3 - "Tts + EdgeTtsTest (EdgeTts)"
Cohesion: 0.06
Nodes (9): EdgeTts, WebSocketListener, Forbidden, TtsException, TtsOptions, TtsPrefs, TtsText, TtsVoice (+1 more)

### Community 4 - "Text + Export (.markdown)"
Cohesion: 0.06
Nodes (11): BarChartView, ChartDraw, Export, Chunks, Csv, JsonText, ThumbUrl, Vtt (+3 more)

### Community 5 - "TtsActivity + Tts (TtsActivity)"
Cohesion: 0.09
Nodes (3): TtsActivity, TextWatcher, OnSeekBarChangeListener

### Community 6 - "Style + StyleTest (.measure)"
Cohesion: 0.09
Nodes (7): Cue, Frame, Speech, StyleMath, StyleMetrics, styleTokenEstimate(), StyleTest

### Community 7 - "Voc + SeoGraphTest (Voc)"
Cohesion: 0.08
Nodes (8): GraphAlgo, Relevance, Item, Msg, Voc, BackupTest, LogRedactTest, SeoGraphTest

### Community 8 - "Channels (ChannelData)"
Cohesion: 0.13
Nodes (6): ChannelData, ChannelFetch, ChannelStats, ChannelStore, PlaylistRef, Result

### Community 9 - "Quick (.run)"
Cohesion: 0.15
Nodes (8): chooseLinkAction(), Link, LinkActivity, QuickAnalysis, QuickConfig, QuickState, Step, YtLink

### Community 10 - "Recs + Text (RecEngine)"
Cohesion: 0.22
Nodes (3): Ctx, DepthStat, RecEngine

### Community 12 - "Ui (Ui.kt)"
Cohesion: 0.09
Nodes (3): scrollPage(), Thumbs, Type

### Community 13 - "Models + Recs (JSONObject)"
Cohesion: 0.13
Nodes (5): RecConfig, RunRecord, SeoContext, strings(), toJsonArray()

### Community 14 - "LumenCoreTest (Test)"
Cohesion: 0.18
Nodes (5): AnalyticsTest, FmtTest, QuickAndChannelsTest, SeoPureTest, video()

### Community 16 - "Recs + LumenCoreTest (RecNode)"
Cohesion: 0.16
Nodes (3): RecEdge, RecGraph, RecNode

### Community 18 - "Topic + Channels (.w)"
Cohesion: 0.23
Nodes (4): ChannelExplorer, Config, TopicFilter, TopicProfile

### Community 19 - "Browser + Ui (BrowserActivity)"
Cohesion: 0.16
Nodes (3): BrowserActivity, WebChromeClient, isNight()

### Community 20 - "LumenCoreTest + LlmParse (.completeRaw)"
Cohesion: 0.21
Nodes (3): LlmReply, LlmParse, LlmParseTest

### Community 21 - "Core + Log (Core.kt)"
Cohesion: 0.15
Nodes (3): Lumen, LumenEnv, Settings

### Community 24 - "Watch + WatchTest (WatchTest)"
Cohesion: 0.22
Nodes (3): LangGuess, Regions, WatchTest

### Community 26 - "Llm + Seo (Llm.kt)"
Cohesion: 0.15
Nodes (5): ApiKind, ANTHROPIC, GEMINI, OPENAI_COMPAT, SeoChecklist

### Community 27 - "Llm (Provider)"
Cohesion: 0.17
Nodes (10): Llm, Provider, ANTHROPIC, CUSTOM, DEEPSEEK, GEMINI, GROQ, OLLAMA (+2 more)

### Community 29 - "Ui (Ic)"
Cohesion: 0.14
Nodes (14): Ic, ADD, BACK, BUG, COPY, DELETE, FIT, JOURNAL (+6 more)

### Community 30 - "AiJournal (AiJournal)"
Cohesion: 0.27
Nodes (3): AiJournal, AiJournalActivity, Entry

### Community 31 - "BrowserUiTest + Browser (BrowserUiTest)"
Cohesion: 0.26
Nodes (4): BrowserText, glyphIcon(), glyphLabel(), BrowserUiTest

### Community 34 - "Seo + LumenCoreTest (.parse)"
Cohesion: 0.36
Nodes (3): ImportParser, Parsed, ImportParserTest

### Community 36 - "Models + HomeActivities (SearchFilters)"
Cohesion: 0.33
Nodes (3): filtersSummary(), MonitorConfig, SearchFilters

### Community 48 - "Ui (BtnStyle)"
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