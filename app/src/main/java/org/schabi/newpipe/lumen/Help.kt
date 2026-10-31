/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Which help topic belongs to which screen (pure, unit-tested: every screen must have a topic). */
object HelpMap {
    /** Topic ids in the order of the help screen. */
    val ids = listOf(
        "about", "home", "wizard", "new_project", "project", "kmap", "video", "channel", "quick", "graph", "audit",
        "seo", "diagram", "journal", "send_review", "browser", "tts", "studio", "bots", "settings"
    )

    private val screens = mapOf(
        "LumenActivity" to "home",
        "WizardActivity" to "wizard",
        "NewProjectActivity" to "new_project",
        "ProjectActivity" to "project",
        "KnowledgeMapActivity" to "kmap",
        "VideoActivity" to "video",
        "ChannelActivity" to "channel",
        "LinkActivity" to "quick",
        "HarvestActivity" to "quick",
        "GraphActivity" to "graph",
        "AuditActivity" to "audit",
        "TopicActivity" to "seo",
        "DiagramActivity" to "diagram",
        "AiJournalActivity" to "journal",
        "SendReviewActivity" to "send_review",
        "BrowserActivity" to "browser",
        "TtsActivity" to "tts",
        "TtsShareListen" to "tts",
        "TtsShareSave" to "tts",
        "StudioActivity" to "studio",
        "BotsActivity" to "bots",
        "BotNewActivity" to "bots",
        "BotActivity" to "bots",
        "SettingsActivity" to "settings",
        "HelpActivity" to "about"
    )

    /** All screens that have their own entry (used by the test). */
    val knownScreens: Set<String> get() = screens.keys

    fun topicFor(screen: String): String = screens[screen] ?: "about"
}

/** One section of the help: what the screen is for, how to use it, tips. */
class HelpTopic(val id: String, val title: String, val what: String, val steps: List<String>, val tips: List<String> = emptyList())

object Help {
    fun topics(): List<HelpTopic> = listOf(
        HelpTopic("about", t("О проекте Lumen", "About Lumen"),
            t("Lumen — анализатор тем YouTube на телефоне. Это форк NewPipe (GPL-3.0): видео, субтитры и комментарии читаются без аккаунта и ключей YouTube. " +
                "Все проекты, ключи и результаты хранятся только на устройстве. AI подключается по вашему ключу (Gemini, Claude, OpenAI, OpenRouter, DeepSeek, Groq, Ollama). " +
                "Lumen Studio делает из сценария и голоса короткие вертикальные ролики.",
                "Lumen is a YouTube topic analyser for your phone. It is a NewPipe fork (GPL-3.0): videos, subtitles and comments are read without a YouTube account or keys. " +
                    "Projects, keys and results stay on the device. AI works with your own key (Gemini, Claude, OpenAI, OpenRouter, DeepSeek, Groq, Ollama). " +
                    "Lumen Studio turns a script and a voice into short vertical videos."),
            listOf(
                t("Создайте проект: тема, фраза или ссылка на видео, канал, плейлист.", "Create a project: a topic, a phrase or a link to a video, channel or playlist."),
                t("Соберите видео: поиск, метаданные, субтитры, комментарии.", "Collect videos: search, metadata, subtitles, comments."),
                t("Смотрите аналитику, рекомендации, аудиторию и SEO.", "Look at analytics, recommendations, audience and SEO."),
                t("Подключите AI в Настройках, чтобы получать сводки и отчёты.", "Connect AI in Settings to get summaries and reports."),
                t("Делитесь результатами: экспорт CSV, JSON, Markdown, PDF, GEXF.", "Share results: CSV, JSON, Markdown, PDF, GEXF export.")
            ),
            listOf(
                t("Значок «?» в шапке любого экрана открывает справку по нему.", "The \"?\" in the top bar of any screen opens help for it."),
                t("🐞 в меню — журнал и отчёт об ошибке для разработчика (без ключей и имён).", "🐞 in the menu is the log and a bug report for the developer (no keys or names)."),
                t("Каждый ответ AI сохраняется в журнале «Ответы AI».", "Every AI answer is kept in the \"AI answers\" journal.")
            )),
        HelpTopic("home", t("Главный экран", "Home"),
            t("Список проектов-тем и переход к основным разделам через нижнюю панель: Проекты, Браузер, Озвучка, Студия, Настройки.",
                "The list of topic projects and the main sections in the bottom bar: Projects, Browser, Read aloud, Studio, Settings."),
            listOf(
                t("«＋ Новый проект» — создать тему.", "\"＋ New project\" creates a topic."),
                t("Нажмите на проект, чтобы открыть его; долгое нажатие или ⋮ — дублировать, архив, удалить.", "Tap a project to open it; long press or ⋮ to duplicate, archive or delete."),
                t("⋮ в шапке: домен для наблюдения, архив, восстановление из копии, журнал ошибок.", "⋮ in the top bar: a domain to watch, archive, restore from a backup, error log.")
            ),
            listOf(t("Копия данных сама сохраняется в «Загрузки/Lumen».", "A backup is saved to Downloads/Lumen automatically."))),
        HelpTopic("wizard", t("Первый запуск", "First run"),
            t("Мастер начальной настройки: язык, градиентная тема, режим, AI. Любой шаг можно пропустить и поменять позже в Настройках.",
                "First-run wizard: language, gradient theme, mode, AI. Every step can be skipped and changed later in Settings."),
            listOf(t("Пройдите шаги кнопкой «Далее».", "Go through the steps with \"Next\"."), t("Ключ AI можно ввести сразу или потом.", "Enter an AI key now or later."))),
        HelpTopic("new_project", t("Новый проект", "New project"),
            t("Тема для исследования: слово, фраза или ссылка. AI предложит значения фразы (домены) и поисковые запросы.",
                "A topic to research: a word, a phrase or a link. AI suggests meanings of the phrase (domains) and search queries."),
            listOf(
                t("Введите фразу или вставьте ссылку YouTube.", "Type a phrase or paste a YouTube link."),
                t("Выберите домен — так сбор не уйдёт в чужую тему.", "Pick a domain so collection stays on topic."),
                t("Настройте фильтры поиска и создайте проект.", "Set search filters and create the project.")
            )),
        HelpTopic("project", t("Проект", "Project"),
            t("Всё по одной теме. Нижняя панель: Обзор, Данные (видео, сбор, каналы), Анализ (аналитика, рекомендации, аудитория, SEO), AI, Ещё (экспорт, схемы, настройки).",
                "Everything about one topic. Bottom bar: Overview, Data (videos, collect, channels), Analysis (analytics, recommendations, audience, SEO), AI, More (export, diagrams, settings)."),
            listOf(
                t("Сбор: запустите поиск и загрузку субтитров и комментариев. Долгие задачи идут в фоне с уведомлением.", "Collect: run the search and subtitle/comment download. Long tasks run in the background with a notification."),
                t("Анализ: топ каналов и видео, динамика, кластеры; Рекомендации: граф «снежный ком», цепочка, Mix.", "Analysis: top channels and videos, trends, clusters; Recommendations: snowball graph, chain, Mix."),
                t("Аудитория: боли, вопросы и запросы из комментариев; SEO: конвейер P1–P7 с проверкой цитат.", "Audience: pains, questions and requests from comments; SEO: P1–P7 pipeline with quote checks."),
                t("AI: сводки, отчёт, чат по данным проекта.", "AI: summaries, report, chat over the project data."),
                t("Ещё → Экспорт: CSV, JSON, Markdown, PDF, GEXF.", "More → Export: CSV, JSON, Markdown, PDF, GEXF.")
            ),
            listOf(t("Мониторинг в настройках проекта сам проверяет новые видео по расписанию.", "Monitoring in project settings checks for new videos on a schedule."),
                t("«Искать на всех языках»: тема переводится AI, уведомление сообщает, сколько вышло, на каких языках и о чём.", "\"Search in all languages\": AI translates the topic; the notification says how many came out, in which languages and what about."))),
        HelpTopic("video", t("Карточка видео", "Video card"),
            t("Метаданные, метки, сводка AI, расшифровка и комментарии одного видео.", "Metadata, labels, AI summary, transcript and comments of one video."),
            listOf(t("Откройте видео на YouTube кнопкой в шапке.", "Open the video on YouTube from the top bar."), t("Сводку AI можно пересоздать.", "The AI summary can be regenerated."), t("Текст можно озвучить 🔊.", "Text can be read aloud 🔊."),
                t("🎧 В фоне — слушать звук плеером NewPipe; ⬇ Аудио / ⬇ Видео — диалог загрузки с выбором качества.", "🎧 Background plays the sound in the NewPipe player; ⬇ Audio / ⬇ Video opens the download dialog with quality choice."),
                t("В любом списке видео (анализ, сбор, рекомендации, аудит, канал) кнопка ⋮ справа даёт те же действия.", "In any video list (analysis, harvest, recommendations, audit, channel) the ⋮ button on the right gives the same actions."))),
        HelpTopic("channel", t("Канал", "Channel"),
            t("Статистика канала, ссылки, свежие видео, плейлисты, рекомендуемые каналы и сводка AI.", "Channel stats, links, recent videos, playlists, featured channels and an AI summary."),
            listOf(t("Нажмите на плейлист или видео — начнётся быстрый анализ.", "Tap a playlist or video to start a quick analysis."))),
        HelpTopic("quick", t("Быстрый анализ из «Поделиться»", "Quick analysis from Share"),
            t("Поделитесь ссылкой YouTube в Lumen — получите сводку, статистику, субтитры, похожие видео и каналы, граф каналов.",
                "Share a YouTube link to Lumen to get a summary, stats, subtitles, related videos and channels, a channel graph."),
            listOf(
                t("В YouTube нажмите «Поделиться» → Lumen: анализ.", "In YouTube tap Share → Lumen: analysis."),
                t("Выберите, что собрать; задача идёт в фоне.", "Choose what to collect; the task runs in the background."),
                t("Результат открывается отдельной карточкой в «Недавних».", "The result opens as its own card in Recents.")
            ),
            listOf(t("Объёмы (100 видео, 100 каналов) меняются в Настройки → Быстрый анализ.", "Amounts (100 videos, 100 channels) are set in Settings → Quick analysis."))),
        HelpTopic("kmap", t("Карта знаний", "Knowledge map"),
            t("Всё, что собрано по проекту, деревом аспектов: тема, каналы, метки, теги, хронология, форматы, аудитория, комментарии, рекомендации, выводы AI. Ветки раскрываются до видео и комментариев.",
                "Everything collected in a project as a tree of aspects: topic, channels, labels, tags, timeline, formats, audience, comments, recommendations, AI findings. Branches open down to videos and comments."),
            listOf(
                t("Откройте кнопкой «🗺 Карта знаний» на карточке проекта или в шапке проекта.", "Open it with \"🗺 Knowledge map\" on a project card or in the project's top bar."),
                t("Нажмите на узел, чтобы раскрыть или свернуть его.", "Tap a node to open or close it."),
                t("✨ у узла — AI-сводка именно по этой ветке; ✨✓ — сводка уже есть.", "✨ on a node makes an AI summary of that branch; ✨✓ means one exists."),
                t("Долгое нажатие на видео или канал открывает его.", "Long press on a video or channel opens it.")
            ),
            listOf(
                t("Сводка берёт обзор всех подветок, а не детали первых: чем выше узел, тем шире взгляд.", "A summary takes an overview of all sub-branches rather than details of the first ones: the higher the node, the wider the view."),
                t("Повтор с теми же данными бесплатный — из кэша. «Обновить» запрашивает заново.", "A repeat with the same data is free from the cache. \"Refresh\" asks again."),
                t("⋮ → «Все сводки карты» собирает их в один текст.", "⋮ → \"All map summaries\" puts them into one text.")
            )),
        HelpTopic("graph", t("Граф рекомендаций", "Recommendation graph"),
            t("Как YouTube связывает видео и каналы темы. Размер узла — важность (PageRank), цвет — кластер.", "How YouTube links videos and channels of the topic. Node size is importance (PageRank), colour is the cluster."),
            listOf(t("Двигайте и масштабируйте двумя пальцами.", "Pan and zoom with two fingers."), t("Нажмите на узел, чтобы открыть видео или канал.", "Tap a node to open the video or channel."), t("⤢ — вписать граф в экран.", "⤢ fits the graph to the screen."))),
        HelpTopic("audit", t("Аудит алгоритма", "Algorithm audit"),
            t("Имитация зрителя без аккаунта: куда YouTube уводит от темы по цепочке «Следующее».", "A simulated viewer without an account: where YouTube's \"Up next\" chain leads away from the topic."),
            listOf(t("Настройте зрителя и запустите прогон.", "Set the viewer up and start a run."), t("Смотрите дрейф по шагам, аттракторы, каналы и вердикт AI.", "See the drift by step, attractors, channels and the AI verdict."), t("Сравните два прогона.", "Compare two runs."))),
        HelpTopic("seo", t("SEO-тема", "SEO topic"),
            t("Одна тема через конвейер: доказательства → P3–P6 → чек-лист → одобрение редактора → метрики.", "One topic through the pipeline: evidence → P3–P6 → checklist → editor approval → metrics."),
            listOf(t("Проходите этапы по порядку.", "Go through the stages in order."), t("Цитаты проверяются по источнику.", "Quotes are checked against the source."), t("Публикуйте только после одобрения.", "Publish only after approval."))),
        HelpTopic("diagram", t("Схема", "Diagram"),
            t("Редактор схем: блоки и связи. AI может построить схему по данным проекта.", "Diagram editor: blocks and links. AI can build a diagram from the project data."),
            listOf(t("Нажмите на блок, чтобы изменить текст.", "Tap a block to edit its text."), t("Экспорт: картинка, PDF, текст.", "Export: image, PDF, text."))),
        HelpTopic("journal", t("Ответы AI", "AI answers"),
            t("Каждый запрос к AI: промпт, ответ, модель, токены, ошибки. Полезно, если результат странный.", "Every AI request: prompt, answer, model, tokens, errors. Useful when a result looks odd."),
            listOf(t("Откройте запись, чтобы увидеть полный ответ.", "Open an entry to see the full answer."), t("Записью можно поделиться с разработчиком.", "An entry can be shared with the developer."))),
        HelpTopic("send_review", t("Что отправляем в AI", "What goes to AI"),
            t("Перед каждым запросом к AI показывает все данные, которые уйдут провайдеру: системную инструкцию, блоки и строки запроса, ссылку на видео, размер и оценку токенов. Ключ API в текст не входит.",
                "Before every AI request it shows all data that will go to the provider: the system instruction, request blocks and lines, the video link, size and token estimate. The API key is not part of the text."),
            listOf(
                t("Снимите галочки с лишних блоков или строк — они не отправятся.", "Untick blocks or lines you don't want to send."),
                t("Текст можно отредактировать вручную; выбор сохраняется для повторов той же задачи.", "You can edit the text by hand; the choice carries over to retries of the same task."),
                t("«Отправить» или «Не отправлять»; фоновые задачи спрашивают через уведомление.", "\"Send\" or \"Don't send\"; background jobs ask via a notification.")
            ),
            listOf(t("Правила по задачам (спрашивать / отправлять / никогда) — в Настройки → AI → Что отправляется.", "Per-task rules (ask / send / never) are in Settings → AI → What is sent."))),
        HelpTopic("browser", t("Браузер", "Browser"),
            t("Встроенный браузер. Может записывать звук вкладки.", "Built-in browser. It can record the tab's sound."),
            listOf(t("Введите адрес или запрос в строке сверху.", "Type an address or a query in the top bar."))),
        HelpTopic("tts", t("Озвучка текста", "Read aloud"),
            t("Голоса Microsoft Edge: бесплатно, без ключа, нужен интернет. Текст отправляется на сервер Microsoft.", "Microsoft Edge voices: free, no key, needs internet. The text is sent to Microsoft's server."),
            listOf(
                t("Вставьте текст или выберите файл: TXT, MD, FB2, EPUB, DOCX, HTML, SRT, VTT.", "Paste text or pick a file: TXT, MD, FB2, EPUB, DOCX, HTML, SRT, VTT."),
                t("Выберите голос, скорость и тон; «▷ Образец» — послушать.", "Pick a voice, speed and pitch; \"▷ Sample\" to listen."),
                t("«Слушать» — читать сразу; «Сохранить MP3» — файл в Music/Lumen.", "\"Listen\" reads now; \"Save MP3\" writes a file to Music/Lumen.")
            ),
            listOf(t("Из любого приложения: выделите текст → Lumen, или «Поделиться» документом.", "From any app: select text → Lumen, or share a document."))),
        HelpTopic("studio", "Lumen Studio",
            t("Ролики в стиле анимированной инфографики: слова появляются под голос, рядом схемы из эмодзи-плиток, диаграммы, цитаты и сравнения. " +
                "Четыре задачи вверху экрана: «Shorts» — вертикальный ролик 9:16 по сценарию; «Видео» — горизонтальный ролик для YouTube 16:9 на 2–10 минут; " +
                "«Озвучка» — готовое аудио или запись голоса превращается в анимированный ролик 9:16, 16:9 или 1:1; " +
                "«Нарезка» — голос за кадром поверх кусков видео с YouTube и графики. Ролик собирается на телефоне в MP4 720p или 1080p.",
                "Videos in an animated infographic style: words appear with the voice, next to emoji-tile schemes, charts, quotes and comparisons. " +
                    "Four tasks at the top: \"Shorts\" is a vertical 9:16 video from a script; \"Video\" is a horizontal 16:9 YouTube video of 2–10 minutes; " +
                    "\"Voice-over\" turns ready audio or a recording into an animated 9:16, 16:9 or 1:1 video; " +
                    "\"Cuts\" puts a voice-over over pieces of YouTube videos and graphics. The video is built on the phone as an MP4, 720p or 1080p."),
            listOf(
                t("1. Сценарий: напишите сами или нажмите «✨ Написать (AI)» по теме. Каждая сцена — абзац; первая строка может начинаться с [icons], [list], [flow], [cycle], [step: заголовок], [big], [stat: 80%]; строка с «|» — плитки «🔍 подпись».",
                    "1. Script: write it or tap \"✨ Write (AI)\" for a topic. Each scene is a paragraph; the first line may start with [icons], [list], [flow], [cycle], [step: title], [big], [stat: 80%]; a line with \"|\" lists tiles \"🔍 label\"."),
                t("2. Голос: голос Microsoft (точные тайминги слов), свой микрофон, аудиофайл или видео. При записи можно включить суфлёр. «Видео» — снимите себя камерой или выберите ролик: звук станет озвучкой, картинка — фоном.",
                    "2. Voice: a Microsoft voice (exact word timing), your microphone, an audio file or a video. A teleprompter can be shown while recording. \"Video\": film yourself or pick a clip; its sound is the voice-over, its picture the background."),
                t("3. Разбор: Lumen находит речь и паузы, считает темп, расставляет слова по времени. Без сценария запись распознаётся (Android 13+).",
                    "3. Analysis: Lumen finds speech and pauses, measures the pace and times every word. Without a script the take is recognised (Android 13+)."),
                t("AI-режиссёр (сразу после разбора или кнопкой «🎬 AI-режиссура»): AI получает расшифровку с номерами слов и возвращает план — сцены и графику, подсветку ключевых слов, резкие зумы, всплывающие плашки, звуки (whoosh, pop, click, ding, boom, riser) и переходы (slide, zoom, whip). Для своей записи он же вырезает слова-паразиты и оговорки. Lumen проверяет план и собирает ролик по нему.",
                    "AI director (right after the analysis or with \"🎬 AI direction\"): AI gets the numbered transcript and returns a plan: scenes and graphics, highlighted key words, punch-in zooms, pop-up stickers, sounds (whoosh, pop, click, ding, boom, riser) and transitions (slide, zoom, whip). For your own take it also cuts filler words and slips. Lumen checks the plan and builds the video from it."),
                t("4. Ролик: выберите стиль (тёмный — группы слов; светлый — заголовок и одно крупное слово; монтаж — ваше видео со словом внизу и графикой вставками или карточками поверх), цвет, качество и фоновую музыку, посмотрите кадр в предпросмотре и нажмите «🎬 Создать ролик».",
                    "4. Video: pick a style (dark: word groups; light: headline and one big word; montage: your video with the spoken word below and graphics as inserts or cards on top), colour, quality and background music, check a frame in the preview and tap \"🎬 Make video\".")
            ),
            listOf(
                t("Готовый ролик сохраняется в «Фильмы/Lumen» и сразу доступен для «Поделиться».", "The video is saved to Movies/Lumen and can be shared right away."),
                t("Сборка минутного ролика занимает 1–3 минуты и идёт в фоне: экран можно закрыть, прогресс и кнопка «Отмена» — в уведомлении, по готовности — «Открыть» и «Поделиться».", "Building a one-minute video takes 1–3 minutes in the background: you can close the screen; progress and Cancel are in the notification, then Open and Share."),
                t("Голос Microsoft требует интернет; запись с микрофона и сборка работают без сети.", "Microsoft voices need internet; microphone recording and building work offline."),
                t("Музыка зацикливается на длину ролика, плавно начинается и затихает, а под голосом сама становится тише.", "Music loops to the video length, fades in and out and gets quieter under the voice."),
                t("«🔎 Найти бесплатную» ищет музыку в официальных каталогах: Openverse (без ключа; ccMixter, Freesound, Wikimedia, Jamendo) и Freesound (бесплатный ключ). По умолчанию показываются только треки, разрешённые в монетизируемых роликах (CC0, CC BY, CC BY-SA). Для CC BY Студия готовит подпись автора — скопируйте её в описание ролика.",
                    "\"🔎 Find free music\" searches official catalogs: Openverse (no key; ccMixter, Freesound, Wikimedia, Jamendo) and Freesound (free key). By default only tracks allowed in monetised videos are shown (CC0, CC BY, CC BY-SA). For CC BY the Studio prepares the author credit; copy it into the video description."),
                t("Монтаж с видео собирается дольше: кадры декодируются и кадрируются под формат ролика.", "Montage takes longer: frames are decoded and cropped to the frame of the video."),
                t("Анимация фигур: AI-режиссёр ставит стрелки, кольца, подчёркивания, галочки, крестики, рамки, «прожектор» и надписи на ключевые кадры (позиция, масштаб, поворот, прозрачность, прорисовка) с плавными кривыми — стрелка рисуется к цифре, кольцо выскакивает вокруг главного.",
                    "Shape animation: the AI director puts arrows, rings, underlines, ticks, crosses, frames, a spotlight and labels on key frames (position, scale, turn, opacity, drawing) with smooth curves: an arrow draws itself to a number, a ring pops around the key point."),
                t("B-roll: для сцен, где фото лучше схемы, AI даёт английский запрос, а Lumen находит фото со свободной лицензией в Openverse (без ключа; уходят только слова запроса) и показывает его на весь кадр или в карточке с медленным наездом. «⧉ Источники фото» копирует подписи авторов для описания ролика.",
                    "B-roll: for scenes where a photo beats a scheme the AI gives English search words and Lumen finds an openly licensed photo on Openverse (no key; only the search words leave the phone), shown full-frame or on a card with a slow push-in. \"⧉ Photo credits\" copies the author credits for the description."),
                t("Звуки эффектов синтезируются в самом приложении, без файлов и сети; их можно выключить в шаге 4.", "Sound effects are synthesised by the app itself, with no files or network; turn them off in step 4."),
                t("Автомонтаж своего видео: «✂ Вырезать паузы» убирает тишину (джамп-каты), «Зумы» чередуют крупность и плавно наезжают камерой, «Переходы» вводят вставки слайдом. «🎞 Раскадровка» показывает кадр каждой сцены.",
                    "Auto-edit of your video: \"✂ Cut out pauses\" removes silence (jump cuts), \"Zooms\" alternate framing and push in, \"Transitions\" slide inserts in. \"🎞 Storyboard\" shows a frame of every scene."),
                t("«Нарезка»: в карточке «🎞 Клипы с YouTube» добавьте клип по ссылке — начало, конец (до 30 с) и подпись, что в кадре. «⬇ Скачать» загружает только эти секунды видео без звука. AI-режиссёр ставит клипы на сцены по подписям; сцену можно поменять вручную нажатием в списке сцен или кнопкой «Расставить по сценам без AI». Между клипами идёт графика. Чужие видео защищены авторским правом: берите короткие вставки.",
                    "\"Cuts\": in the \"🎞 YouTube clips\" card add a clip by link: start, end (up to 30 s) and a note on what is in the shot. \"⬇ Download\" fetches only those seconds of video, without sound. The AI director puts the clips on scenes by their notes; tap a scene in the list or use \"Place on scenes without AI\" to change it. Graphics fill the scenes between clips. Other people's videos are copyrighted: use short inserts."),
                t("«Видео на фоне»: добавьте клипы под любую озвучку (голос Microsoft, микрофон, аудио) — они идут по очереди и повторяются по кругу на всю длину озвучки.",
                    "\"Background video\": add clips under any voice-over (Microsoft voice, microphone, audio); they play in order and loop for the whole voice-over.")
            )),
        HelpTopic("bots", t("Мои ТГ боты", "My Telegram bots"),
            t("Telegram-боты, которые пишет ИИ по вашему описанию, а телефон работает их сервером. У раздела своя иконка «Lumen Боты» на рабочем столе. " +
                "Код бота — JavaScript; он выполняется на телефоне в песочнице без доступа к файлам и ключам.",
                "Telegram bots written by AI from your description, with the phone as their server. The section has its own \"Lumen Bots\" home-screen icon. " +
                    "The bot code is JavaScript; it runs on the phone in a sandbox with no access to files or keys."),
            listOf(
                t("1. В Telegram откройте @BotFather → /newbot, придумайте имя и @username, скопируйте токен.", "1. In Telegram open @BotFather → /newbot, pick a name and @username, copy the token."),
                t("2. «＋ Новый бот»: вставьте токен, опишите, что делает бот и какие у него команды, нажмите «✨ Создать бота». ИИ напишет код, Lumen проверит его и при ошибке попросит ИИ исправить.",
                    "2. \"＋ New bot\": paste the token, describe what the bot does and its commands, tap \"✨ Create the bot\". AI writes the code, Lumen checks it and asks AI to fix any error."),
                t("3. Вкладка «Тест» — чат с ботом прямо на телефоне, без Telegram. Кнопки бота нажимаются.", "3. The Test tab is a chat with the bot right on the phone, without Telegram. The bot's buttons work."),
                t("4. «▶ Запуск» — бот начинает отвечать в Telegram. В шторке висит уведомление «Lumen Боты» с кнопкой «Остановить все».", "4. \"▶ Start\" — the bot answers in Telegram. A \"Lumen Bots\" notification with \"Stop all\" stays in the shade."),
                t("5. «Код» → «✏️ Изменить словами»: опишите правку, ИИ перепишет код; работающий бот перезагрузится сам. Старые версии можно вернуть.", "5. Code → \"✏️ Change in words\": describe the change, AI rewrites the code; a running bot reloads by itself. Old versions can be restored."),
                t("6. «Журнал» — входящие, ответы, ошибки и пользователи. Ошибку можно отдать ИИ на исправление.", "6. Journal — incoming messages, answers, errors and users. An error can be handed to AI to fix.")
            ),
            listOf(
                t("Боты отвечают, пока телефон включён и в сети. Сообщения, пришедшие офлайн, Telegram хранит до суток и бот ответит на них позже.", "Bots answer while the phone is on and online. Telegram keeps messages sent while offline for up to a day; the bot answers them later."),
                t("Разрешите работу в фоне (снимите ограничения батареи), иначе Android усыпит ботов. На Xiaomi, Huawei, Honor, Oppo также включите автозапуск для Lumen.", "Allow background work (remove battery restrictions), or Android puts the bots to sleep. On Xiaomi, Huawei, Honor, Oppo also allow autostart for Lumen."),
                t("Владелец: отправьте боту /lumen_owner и код из настроек — тогда /lumen_stats, /lumen_logs и /lumen_stop работают прямо в Telegram.", "Owner: send /lumen_owner and the code from settings to the bot — then /lumen_stats, /lumen_logs and /lumen_stop work right in Telegram."),
                t("Ответы ИИ работающих ботов уходят без окна проверки и ограничены лимитами в настройках бота, чтобы чужие люди не израсходовали ключ.", "AI answers of running bots are sent without the review screen and are capped by the bot's limits so strangers cannot burn your key."),
                t("Мини-апп (вкладка «Мини-апп»): опишите страницу — ИИ сделает её одним HTML-файлом (форма, каталог, опрос, игра). «👁 Предпросмотр» показывает её как в Telegram, данные из неё уходят в тест-чат. «🔗 Подключить к боту» — ИИ добавит кнопку открытия и обработку данных. Telegram открывает мини-апп только по https: Lumen бесплатно публикует его на GitHub Pages (нужен токен GitHub с правом public_repo) или используйте свой адрес.",
                    "Mini app (the Mini app tab): describe the page — AI makes it as one HTML file (a form, catalog, survey, game). \"👁 Preview\" shows it like Telegram does; its data goes to the test chat. \"🔗 Connect to the bot\" — AI adds an opening button and data handling. Telegram opens mini apps only over https: Lumen publishes for free to GitHub Pages (a GitHub token with public_repo) or use your own address."),
                t("Один токен может обслуживать только один сервер. Если бот уже работает где-то ещё, Lumen покажет «Токен занят».", "One token can be served by one server only. If the bot already runs elsewhere, Lumen shows \"Token in use\".")
            )),
        HelpTopic("settings", t("Настройки", "Settings"),
            t("Язык, тема, режим, AI-провайдеры и модели, промпты, мониторинг, резервные копии, журнал ошибок.", "Language, theme, mode, AI providers and models, prompts, monitoring, backups, error log."),
            listOf(
                t("AI: выберите провайдера, нажмите «Получить ключ», вставьте ключ. Ключ шифруется и хранится только на устройстве.", "AI: pick a provider, tap \"Get key\", paste it. The key is encrypted and stays on the device."),
                t("Промпты: любой промпт можно отредактировать или сбросить.", "Prompts: any prompt can be edited or reset."),
                t("Логи: отчёт об ошибке для разработчика.", "Logs: a bug report for the developer."),
                t("О приложении → Обновления: Lumen сам проверяет новую сборку раз в 12 часов и сразу, если YouTube начал отдавать ошибки разбора. «Скачать и установить» ставит её поверх, данные сохраняются.",
                    "About → Updates: Lumen checks for a new build every 12 hours, and right away if YouTube starts returning parsing errors. \"Download and install\" installs it over the current one, data is kept."),
                t("«Проверить работу YouTube» показывает, что именно сломалось: поиск, видео, потоки, комментарии или канал. «Блокирует IP» значит VPN или прокси, а не поломку.",
                    "\"Test YouTube now\" shows what exactly broke: search, video, streams, comments or channel. \"Blocks this IP\" means a VPN or proxy, not a bug.")
            ),
            listOf(t("Первое обновление: Android один раз попросит разрешить Lumen устанавливать приложения.", "First update: Android asks once to allow Lumen to install apps.")))
    )

    fun topic(id: String): HelpTopic = topics().firstOrNull { it.id == id } ?: topics().first()
}

/** Help: about the project, then how every screen works. Opens with the topic of the screen it came from. */
class HelpActivity : LumenBase() {
    companion object {
        fun open(ctx: Context, topic: String) {
            ctx.startActivity(Intent(ctx, HelpActivity::class.java).putExtra("topic", topic)
                .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val expanded = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = intent.getStringExtra("topic") ?: "about"
        expanded += first
        render(first)
    }

    private fun render(first: String) {
        val c = Col(this)
        val all = Help.topics()
        val ordered = listOfNotNull(all.firstOrNull { it.id == first }) + all.filter { it.id != first }
        for (tp in ordered) {
            c.card {
                section(tp.title, tp.what.take(80) + if (tp.what.length > 80) "…" else "", tp.id in expanded, { on ->
                    if (on) expanded += tp.id else expanded -= tp.id
                    render(first)
                }) {
                    p(tp.what)
                    if (tp.steps.isNotEmpty()) {
                        add(text(ctx, t("Как пользоваться", "How to use"), Type.TITLE_M, bold = true), 16)
                        tp.steps.forEach { p("• $it") }
                    }
                    if (tp.tips.isNotEmpty()) {
                        add(text(ctx, t("Советы", "Tips"), Type.TITLE_M, bold = true), 16)
                        tp.tips.forEach { muted("💡 $it") }
                    }
                }
            }
        }
        c.muted(t("Lumen — свободное ПО (GPL-3.0), основан на NewPipe.", "Lumen is free software (GPL-3.0) based on NewPipe."))
        page(t("Справка", "Help"), c.root, keepScroll = true)
    }
}
