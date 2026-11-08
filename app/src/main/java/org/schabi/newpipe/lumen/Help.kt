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
        "seo", "diagram", "journal", "send_review", "relay", "browser", "tts", "studio", "montage", "insert", "bite", "player", "bots", "bot_posts", "settings"
    )

    private val screens = mapOf(
        "LumenActivity" to "home",
        "IdeasActivity" to "home",
        "LangGameActivity" to "project",
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
        "RelayActivity" to "relay",
        "BrowserActivity" to "browser",
        "TtsActivity" to "tts",
        "TtsShareListen" to "tts",
        "TtsShareSave" to "tts",
        "StudioActivity" to "studio",
        "StudioTemplatesActivity" to "studio",
        "StudioMixActivity" to "studio",
        "BiteActivity" to "bite",
        "BiteShareActivity" to "bite",
        "PlayerActivity" to "player",
        "StudioEditorActivity" to "studio",
        "WebLayerActivity" to "montage",
        "BotsActivity" to "bots",
        "BotNewActivity" to "bots",
        "BotActivity" to "bots",
        "SettingsActivity" to "settings",
        "FreeAiActivity" to "settings",
        "TasksActivity" to "settings",
        "HelpActivity" to "about",
        "SearchActivity" to "home"
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
                t("Нажмите на проект, чтобы открыть его; ⋮ на карточке — переименовать, дублировать, архив, закрепить, удалить.", "Tap a project to open it; ⋮ on the card renames, duplicates, archives, pins or deletes."),
                t("Долгое нажатие на проект включает выбор нескольких: архив, закрепить, в корзину сразу для всех. «Назад» — выйти из выбора.",
                    "A long press starts selecting several: archive, pin or trash them at once. Back leaves the selection."),
                t("⋮ в шапке: домен для наблюдения, архив, восстановление из копии, журнал ошибок.", "⋮ in the top bar: a domain to watch, archive, restore from a backup, error log.")
            ),
            listOf(t("Копия данных сама сохраняется в «Загрузки/Lumen».", "A backup is saved to Downloads/Lumen automatically."),
                t("🔍 в шапке — поиск по всему приложению: проекты, видео всех проектов, справка, настройки, разделы.",
                    "🔍 in the top bar searches the whole app: projects, videos of all projects, help, settings, screens."),
                t("Удалённый проект 7 дней лежит в корзине (⋮ → Корзина) — его можно восстановить.",
                    "A deleted project stays in the trash for 7 days (⋮ → Trash) and can be restored."),
                t("📌 Закрепить сверху и сортировка — в меню проекта и ⋮ в шапке.", "📌 Pin to top and sorting are in the project menu and ⋮ in the top bar."),
                t("После удаления или сброса внизу появляется «Отменить» на несколько секунд.", "After a delete or reset, \"Undo\" shows at the bottom for a few seconds."),
                t("Долгое нажатие на значок Lumen: новый проект, поиск, Студия, озвучка.", "Long press on the Lumen icon: new project, search, Studio, read aloud."),
                t("Смахните карточку проекта в сторону — в архив; строку видео в проекте — удалить из проекта. Есть «Отменить».",
                    "Swipe a project card sideways to archive it; a video row in a project to remove it. \"Undo\" is offered."))),
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
                t("Анализ: топ каналов и видео, динамика, кластеры; Алгоритм: граф «Следующего» (снежный ком, блуждания, цепочка, Mix), утечка с темы, ниши, потоки каналов, мой канал, возможности, динамика, аудит, прогноз и радар ролика.",
                    "Analysis: top channels and videos, trends, clusters; Algorithm: the Up next graph (snowball, walks, chain, Mix), topic leak, niches, channel flows, my channel, opportunities, dynamics, audit, forecast and video radar."),
                t("Аудитория: боли, вопросы и запросы из комментариев; SEO: конвейер P1–P7 с проверкой цитат.", "Audience: pains, questions and requests from comments; SEO: P1–P7 pipeline with quote checks."),
                t("AI: сводки, отчёт, чат по данным проекта; Предсказания: сообщества «Следующее», двери, что отличает рекомендуемые, оценка черновика, проверка ролика после публикации.",
                    "AI: summaries, report, chat over the project data; Predictions: Up next communities, doors, what recommended videos have, a draft score, a post-publish check."),
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
                t("Долгое нажатие на узел: фокус на ветке, сводка, открыть видео или канал, подробнее.", "Long press on a node: focus on the branch, summary, open the video or channel, details."),
                t("🌳 Дерево / 🪐 Карта — переключение вида. На карте двигайте и масштабируйте двумя пальцами: чем ближе, тем больше подписей. ⤢ — вписать в экран.", "🌳 Tree / 🪐 Map switches the view. On the map pan and zoom with two fingers: the closer, the more labels. ⤢ fits it to the screen."),
                t("В фокусе ветка становится корнем; цепочка 🏠 › … сверху ведёт обратно.", "In focus a branch becomes the root; the 🏠 › … chain at the top leads back."),
                t("❓ Пробелы и 🧭 Путь обучения — AI находит, что видео не раскрывают, и порядок просмотра; ответ становится веткой карты.", "❓ Gaps and 🧭 Learning path: the AI finds what the videos do not cover and a viewing order; the answer becomes a branch of the map.")
            ),
            listOf(
                t("Сводка берёт обзор всех подветок, а не детали первых: чем выше узел, тем шире взгляд.", "A summary takes an overview of all sub-branches rather than details of the first ones: the higher the node, the wider the view."),
                t("Повтор с теми же данными бесплатный — из кэша. «Обновить» запрашивает заново.", "A repeat with the same data is free from the cache. \"Refresh\" asks again."),
                t("⋮ → «Все сводки карты» собирает их в один текст.", "⋮ → \"All map summaries\" puts them into one text."),
                t("Зелёный бейдж «+N» — видео, добавленные с прошлого открытия карты. Полоска под узлом — его доля видео.", "The green \"+N\" badge marks videos added since the map was last opened. The bar under a node is its share of videos."),
                t("Цвет на карте — аспект, размер — число видео, кольцо — есть AI-сводка, «+» — внутри есть ещё.", "On the map colour is the aspect, size the number of videos, a ring means an AI summary, \"+\" means there is more inside.")
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
                t("Провайдер «Другой ИИ вручную» вместо отправки показывает экран «Другой ИИ»: запрос копируется в Gemini/ChatGPT/Claude, ответ возвращается из буфера, вставкой или «Поделиться → Lumen: ответ ИИ».",
                    "The \"Any AI by hand\" provider shows the \"Another AI\" screen instead of sending: the request is copied to Gemini/ChatGPT/Claude, the answer comes back from the clipboard, by pasting or \"Share → Lumen: AI answer\"."),
                t("«Отправить» или «Не отправлять»; фоновые задачи спрашивают через уведомление.", "\"Send\" or \"Don't send\"; background jobs ask via a notification.")
            ),
            listOf(t("Правила по задачам (спрашивать / отправлять / никогда) — в Настройки → AI → Что отправляется.", "Per-task rules (ask / send / never) are in Settings → AI → What is sent."))),
        HelpTopic("relay", t("Другой ИИ вручную", "Another AI by hand"),
            t("Любой запрос Lumen к ИИ можно выполнить в Gemini, ChatGPT, Claude или другом чате — без ключа API. Lumen собирает один готовый текст запроса, вы переносите его в чат, а ответ возвращаете обратно; дальше ответ проходит те же проверки, что и ответ по API.",
                "Any AI request of Lumen can be done in Gemini, ChatGPT, Claude or another chat — without an API key. Lumen builds one ready request text, you move it to the chat and bring the answer back; then it gets the same checks as an API answer."),
            listOf(
                t("Включить: Настройки → AI → провайдер «Другой ИИ вручную» (все задачи) или строка «Через другой ИИ вручную» (только Студия и Языковая игра / все задачи). В Студии — галочка «🌐 Через другой ИИ вручную» в шаге 3.",
                    "Turn on: Settings → AI → the \"Any AI by hand\" provider (every task) or the \"Through another AI by hand\" row (Studio and Language game only / all tasks). In the Studio — the \"🌐 Through another AI by hand\" tick in step 3."),
                t("Запустите задачу как обычно (например, «🎬 Полный AI-режиссёр»). Откроется экран «Другой ИИ»; если приложение свёрнуто — уведомление «Ждём ответ другого ИИ».",
                    "Start the task as usual (e.g. \"🎬 Full AI director\"). The \"Another AI\" screen opens; if the app is in the background — the \"Waiting for the other AI\" notification."),
                t("По желанию допишите своё пожелание в поле — оно встанет в конец запроса.", "Optionally add your wish in the field — it goes to the end of the request."),
                t("Нажмите «⧉ Скопировать», «↗ Отправить в…» или сразу Gemini / ChatGPT / Claude (запрос копируется и открывается сайт). Вставьте запрос в чат; свою просьбу можно дописать и там.",
                    "Tap \"⧉ Copy\", \"↗ Send to…\" or Gemini / ChatGPT / Claude right away (the request is copied and the site opens). Paste the request into the chat; you can add your own words there too."),
                t("Скопируйте ответ ИИ целиком (кнопкой копирования в чате) и вернитесь в Lumen — появится карточка «📋 В буфере ответ ИИ» → «✓ Передать в Lumen».",
                    "Copy the whole AI answer (with the chat's copy button) and come back to Lumen — the \"📋 AI answer in the clipboard\" card appears → \"✓ Hand to Lumen\"."),
                t("Или в приложении ИИ: «Поделиться» → «Lumen: ответ ИИ» — ответ уйдёт в Lumen сам. Или вставьте его в поле «Ответ ИИ» и нажмите «✓ Передать».",
                    "Or in the AI app: \"Share\" → \"Lumen: AI answer\" — the answer goes to Lumen by itself. Or paste it into the \"AI answer\" field and tap \"✓ Hand over\"."),
                t("Если ИИ ошибся в формате, Lumen попросит исправить — откроется новый запрос, повторите шаги. «Отменить запрос» останавливает задачу.",
                    "If the AI got the format wrong, Lumen asks for a fix — a new request opens, repeat the steps. \"Cancel the request\" stops the task.")
            ),
            listOf(
                t("Lumen сам чистит ответ: обёртку ```json, комментарии, лишние запятые и «умные» кавычки. Если JSON нет совсем — предупредит.", "Lumen cleans the answer itself: the ```json wrapper, comments, trailing commas and smart quotes. If there is no JSON at all, it warns."),
                t("Gemini умеет смотреть видео по ссылке YouTube — ссылка уже в запросе. Картинки задачи прикладываются через «↗ Отправить в…».", "Gemini can watch a YouTube link — the link is in the request. Pictures of the task are attached with \"↗ Send to…\"."),
                t("Ответа ждут до часа; задача в это время не считается зависшей.", "The answer is awaited up to an hour; the task does not count as stuck meanwhile.")
            )),
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
                "Задачи вверху экрана: «Shorts» — вертикальный ролик 9:16 по сценарию; «Видео» — горизонтальный ролик для YouTube 16:9 на 2–10 минут; " +
                "«Озвучка» — готовое аудио или запись голоса превращается в анимированный ролик 9:16, 16:9 или 1:1; " +
                "«Клипы» — ваш текст голосом поверх кусков видео с YouTube и графики; «Анимация» — герои объясняют тему. Ролик собирается на телефоне в MP4 720p или 1080p.",
                "Videos in an animated infographic style: words appear with the voice, next to emoji-tile schemes, charts, quotes and comparisons. " +
                    "Tasks at the top: \"Shorts\" is a vertical 9:16 video from a script; \"Video\" is a horizontal 16:9 YouTube video of 2–10 minutes; " +
                    "\"Voice-over\" turns ready audio or a recording into an animated 9:16, 16:9 or 1:1 video; " +
                    "\"Clips\" puts your text as a voice-over over pieces of YouTube videos and graphics; \"Animation\" has characters explain the topic. The video is built on the phone as an MP4, 720p or 1080p."),
            listOf(
                t("1. Сценарий: тема, длительность и язык (у «Клипов» и «Анимации» ещё кадр), затем напишите сами или нажмите «✨ Написать (ИИ)». У «Клипов» и «Анимации» на шаг больше: клипы с YouTube — шаг 2, персонажи — шаг 1. Каждая сцена — абзац; первая строка может начинаться с [icons], [list], [flow], [cycle], [step: заголовок], [big], [stat: 80%]; строка с «|» — плитки «🔍 подпись».",
                    "1. Script: topic, length and language (plus the frame for Clips and Animation), then write it or tap \"✨ Write (AI)\". Clips and Animation have one more step: YouTube clips are step 2, characters step 1. Each scene is a paragraph; the first line may start with [icons], [list], [flow], [cycle], [step: title], [big], [stat: 80%]; a line with \"|\" lists tiles \"🔍 label\"."),
                t("2. Голос: «🗣 Синтез» (голоса Microsoft, точные тайминги слов), «🎙 Запись» с микрофона, «📎 Файл» или «🎥 Видео». При записи можно включить суфлёр. «🎥 Видео» — снимите себя камерой или выберите ролик: звук станет озвучкой, картинка — фоном.",
                    "2. Voice: \"🗣 Synth\" (Microsoft voices, exact word timing), \"🎙 Record\" with the microphone, \"📎 File\" or \"🎥 Video\". A teleprompter can be shown while recording. \"🎥 Video\": film yourself or pick a clip; its sound is the voice-over, its picture the background."),
                t("3. Сцены и режиссура: «🔍 Разметить сцены» — Lumen находит речь и паузы, считает темп, расставляет слова по времени. Без сценария запись распознаётся (Android 13+).",
                    "3. Scenes and direction: \"🔍 Lay out scenes\" — Lumen finds speech and pauses, measures the pace and times every word. Without a script the take is recognised (Android 13+)."),
                t("ИИ-режиссёр (сразу после разметки или кнопкой «🎬 AI-режиссура»): AI получает расшифровку с номерами слов и возвращает план — сцены и графику, подсветку ключевых слов, резкие зумы, всплывающие плашки, звуки (whoosh, pop, click, ding, boom, riser) и переходы (slide, zoom, whip). Для своей записи он же вырезает слова-паразиты и оговорки. Lumen проверяет план и собирает ролик по нему.",
                    "AI director (right after the layout or with \"🎬 AI direction\"): AI gets the numbered transcript and returns a plan: scenes and graphics, highlighted key words, punch-in zooms, pop-up stickers, sounds (whoosh, pop, click, ding, boom, riser) and transitions (slide, zoom, whip). For your own take it also cuts filler words and slips. Lumen checks the plan and builds the video from it."),
                t("4. Ролик: выберите стиль (тёмный — группы слов; светлый — заголовок и одно крупное слово; объяснение — главы по вопросам; «Моё видео» или «Клипы на фоне» — видео со словом внизу и графикой вставками или карточками поверх), цвет, качество и фоновую музыку, посмотрите кадр в предпросмотре и нажмите «🎬 Сохранить видео».",
                    "4. Video: pick a style (dark: word groups; light: headline and one big word; explainer: chapters from the questions; \"My video\" or \"Clips behind\": a video with the spoken word below and graphics as inserts or cards on top), colour, quality and background music, check a frame in the preview and tap \"🎬 Save the video\".")
            ),
            listOf(
                t("«Поверх видео» — один выбор во всех разделах Студии и в Миксе: «💬 Субтитры» или «🧩 Инфосхемы». Инфосхемы ИИ составляет сам — сразу при выборе и перед сборкой, если их ещё нет. " +
                    "Фон блоков как в Редакторе: ▣ Плотный, ◩ Полупрозрачный или ◻ Прозрачный (только текст и значки с мягкой тенью — видео видно целиком). " +
                    "В «⚙ Опции схем»: оформление (Авто, Чистый, Стекло, Бумага, Неон, Смело, Мягко, Новости, Минимал), где (поверх, сбоку от спикера, спикер в углу, на весь экран), размер, сколько схем, субтитры вместе со схемами, пожелание ИИ, составить заново или убрать. " +
                    "Где схема закрыла бы видео целиком («На весь экран» или места нарезки без своего клипа), можно выбрать: 🖼 На весь экран, 🧩 Только инфоблоки (прозрачный фон, без субтитров), 💬 Только субтитры или ✕ Выключить (просто видео); в нарезке там продолжается соседний клип. Ручная работа со схемами — в Редакторе.",
                    "\"Over the picture\" is one choice in every Studio section and in the Mix: \"💬 Subtitles\" or \"🧩 Info-schemes\". The AI composes the schemes by itself — right on the choice and before the build if there are none yet. " +
                    "Block background as in the Editor: ▣ Solid, ◩ Semi-transparent or ◻ Transparent (text and icons with a soft shadow only — the whole video stays visible). " +
                    "\"⚙ Scheme options\": look (Auto, Clean, Glass, Paper, Neon, Bold, Soft, News, Minimal), where (over, beside the speaker, speaker in a corner, full screen), size, how many, subtitles together, a wish for the AI, compose again or remove. " +
                    "Where a scheme would cover the whole video (\"Full screen\" or cut montage scenes with no clip of their own) choose: 🖼 Full screen, 🧩 Blocks only (transparent, no subtitles), 💬 Subtitles only or ✕ Off (just the video); in a cut montage the clip next to them plays on. Hands-on scheme work is in the Editor."),
                t("Готовый ролик сохраняется в «Фильмы/Lumen» и сразу доступен для «Поделиться».", "The video is saved to Movies/Lumen and can be shared right away."),
                t("Сборка минутного ролика занимает 1–3 минуты и идёт в фоне: экран можно закрыть, прогресс и кнопка «Отмена» — в уведомлении, по готовности — «Открыть» и «Поделиться».", "Building a one-minute video takes 1–3 minutes in the background: you can close the screen; progress and Cancel are in the notification, then Open and Share."),
                t("Голос Microsoft требует интернет; запись с микрофона и сборка работают без сети.", "Microsoft voices need internet; microphone recording and building work offline."),
                t("Музыка зацикливается на длину ролика, плавно начинается и затихает, а под голосом сама становится тише.", "Music loops to the video length, fades in and out and gets quieter under the voice."),
                t("«🔎 Найти бесплатную» ищет музыку в официальных каталогах: Openverse (без ключа; ccMixter, Freesound, Wikimedia, Jamendo) и Freesound (бесплатный ключ). По умолчанию показываются только треки, разрешённые в монетизируемых роликах (CC0, CC BY, CC BY-SA). Для CC BY Студия готовит подпись автора — скопируйте её в описание ролика.",
                    "\"🔎 Find free music\" searches official catalogs: Openverse (no key; ccMixter, Freesound, Wikimedia, Jamendo) and Freesound (free key). By default only tracks allowed in monetised videos are shown (CC0, CC BY, CC BY-SA). For CC BY the Studio prepares the author credit; copy it into the video description."),
                t("Редактор (вкладка «✏️ Редактор» во втором ряду Студии) устроен как CapCut/YouCut, тёмный: сверху ✕ (к проектам), формат кадра, ? и «Сохранить»; посередине кадр, под ним время, ▶, ↶ ↷ и ⛶ (во весь экран); ниже таймлайн и панель разделов. Разделы: ＋ Вставить, ✂ Кусок (под курсором), 🎵 Аудио (музыка с телефона, громкость и выключение звука видео), T Текст (надпись, схема текстом, текст речи), 😀 Стикеры, 🖼 Наложение (картинка, фото из сети, второе видео рядом), 🖌 Тема, 🧩 ИИ (схемы и анимации, где нужны вставки, отчёт, перевод), ☰ Слои; формат кадра (9:16, 16:9, 1:1, 4:5…, вписать или заполнить, цвет фона) — чип сверху; «‹ Назад» — к разделам. Тап по кадрам на таймлайне выделяет кусок (или «✂ Кусок» внизу): разрезать, скорость, громкость, 🌈 фильтры (11 с силой), ☀ настройка (яркость, контраст, насыщенность, температура), ⇆ переход (через чёрный/белый, вспышка), удалить, дубль, стоп-кадр, зеркало, масштаб; удержание — переставить; белый квадрат на шве — переход. Надписи, картинки, схемы и музыка — цветные полоски: тяните целиком или за края; на кадре блок двигается пальцем, два пальца — размер и поворот. Музыка: громкость, звук видео под ней, затухание, с какого места трека; при монтаже музыка остаётся на месте. «Сохранить» собирает MP4 на видеокарте (запасной путь — процессор) в Movies/Lumen.",
                    "The editor (the \"✏️ Editor\" tab in the second row of the Studio) works like CapCut/YouCut, dark: on top ✕ (to the projects), the frame format, ? and \"Save\"; the frame in the middle, under it the time, ▶, ↶ ↷ and ⛶ (full screen); below the timeline and the bar of sections. Sections: ＋ Insert, ✂ Piece (under the playhead), 🎵 Audio (music from the phone, volume and muting of the video), T Text (caption, text scheme, speech text), 😀 Stickers, 🖼 Overlay (picture, web photo, a second video beside), 🖌 Theme, 🧩 AI (schemes and animations, where inserts are needed, report, translation), ☰ Layers; the frame format (9:16, 16:9, 1:1, 4:5…, fit or fill, background colour) is the chip at the top; \"‹ Back\" returns. A tap on the frames of the timeline selects a piece (or \"✂ Piece\" below): split, speed, volume, 🌈 filters (11 with strength), ☀ adjust (brightness, contrast, saturation, warmth), ⇆ transition (dip to black/white, flash), delete, duplicate, freeze, mirror, zoom; hold to reorder; the white square at a seam is its transition. Captions, pictures, schemes and music are coloured bars: drag them or their edges; on the frame drag a block, two fingers scale and turn it. Music: volume, the video sound under it, fades, the start in the track; cuts do not move the music. \"Save\" builds the MP4 on the GPU (the CPU as a fallback) into Movies/Lumen."),
                t("Слой ИИ (кнопка «🎛 Анимированный слой ИИ поверх ролика» под готовым роликом Студии): ИИ пишет код слоя анимации по протоколу lumen.canvas — плашки, титры, стрелки, счётчики поверх готового ролика. «⧉ Запрос для другого ИИ» копирует задание (под это видео с речью или универсальный шаблон) для Gemini, ChatGPT или Claude; ответ подхватывается из буфера или кнопкой «📥 Вставить ответ». Каждый ответ сохраняется в «📚 Шаблоны монтажа»: выбирайте, сравнивайте до 8 на одном видео и сохраняйте «🎬 Сохранить видео со слоем». Слой рисуется в WebView без интернета, медленнее основной сборки — держите экран открытым.",
                    "AI layer (the \"🎛 An animated AI layer over the video\" button under a finished Studio video): AI writes the code of an animation layer by the lumen.canvas protocol — stickers, titles, arrows, counters over a finished video. \"⧉ Request for another AI\" copies the task (for this video with its speech, or a reusable template) for Gemini, ChatGPT or Claude; the answer is picked up from the clipboard or with \"📥 Paste the answer\". Every answer is kept in \"📚 Montage templates\": pick, compare up to 8 on one video and \"🎬 Save the video with the layer\". The layer is drawn in a WebView without internet, slower than the main build — keep the screen open."),
                t("Без ключа API: в шаге 3 галочка «🌐 Через другой ИИ вручную» (или провайдер «Другой ИИ вручную» в Настройки → AI). Каждый запрос Студии откроется экраном «Другой ИИ»: скопируйте его в чат, ответ скопируйте обратно — Lumen найдёт его в буфере, или «Поделиться → Lumen: ответ ИИ».",
                    "No API key: in step 3 tick \"🌐 Through another AI by hand\" (or the \"Any AI by hand\" provider in Settings → AI). Every Studio request opens the \"Another AI\" screen: copy it into a chat and copy the answer back — Lumen finds it in the clipboard, or use \"Share → Lumen: AI answer\"."),
                t("Стиль «Моё видео» собирается дольше: кадры декодируются и кадрируются под формат ролика.", "The \"My video\" style takes longer: frames are decoded and cropped to the frame of the video."),
                t("Способы анимации (шаг 3, кнопки): «⚡ Правила» и «🥁 Ритм» работают без AI и токенов (графика по смыслу слов; сцены по паузам, зумы на громких словах и долях музыки); «💸 Эконом AI» — быстрая модель и сжатый протокол; «🧩 Гибрид» — черновик по правилам, AI правит только ключевые сцены; «🎬 Полный AI-режиссёр» — всё, включая фигуры, B-roll и вырезку паразитов. После вызова AI видно, сколько токенов ушло.",
                    "Ways to animate (step 3, buttons): \"⚡ Rules\" and \"🥁 Rhythm\" need no AI or tokens (graphics from the words; scenes by pauses, zooms on loud words and music beats); \"💸 Lite AI\" uses the fast model and a compact protocol; \"🧩 Hybrid\" is a rules draft where AI redoes only the key scenes; \"🎬 Full AI director\" does everything, including shapes, B-roll and cutting fillers. After an AI call the tokens used are shown."),
                t("«📚 Шаблоны» (шаг 3): встроенный AI придумывает по описанию свои моушн-шаблоны (протокол lumen.motion: слои, фигуры, тексты и эмодзи на ключевых кадрах, пункты появляются под слова, формулы и именованные позиции) и подходы к монтажу (lumen.edit: темп, зумы, переходы, звуки и правила «если в сцене цифра — диаграмма и зум»). Lumen проверяет ответ и при ошибках просит AI исправить. «✏️ Править» меняет шаблон промптом (AI присылает только изменения), «🎞 Превью» рисует кадры на пробных данных, «👁 AI-проверка» показывает их модели, которая видит картинки, и та сама поправляет. «⧉ Код» и «🤖 Через другой ИИ» + «📥 Вставить» — обмен с любым ИИ. Моушн-шаблоны режиссёр ставит в сцены как «tpl:…», подходы к монтажу появляются кнопками «🧪» и работают без AI и токенов.",
                    "\"📚 Templates\" (step 3): the built-in AI designs own motion templates from a description (lumen.motion protocol: layers, shapes, texts and emoji on key frames, items appear with their words, formulas and named positions) and ways of editing (lumen.edit: pace, zooms, transitions, sounds and rules like \"if a scene has a number — chart and zoom\"). Lumen checks the answer and asks the AI to fix errors. \"✏️ Edit\" changes a template by a prompt (the AI sends only the changes), \"🎞 Preview\" draws frames on sample data, \"👁 AI look\" shows them to a model that sees images, which fixes the template itself. \"⧉ Code\" and \"🤖 With another AI\" + \"📥 Paste\" exchange templates with any AI. The director puts motion templates into scenes as \"tpl:…\"; ways of editing show as \"🧪\" buttons and run without AI or tokens."),
                t("Шаблон по видео: в «📚 Шаблоны» кнопка «🎬 По видео» (ссылка из буфера подставится сама) или в YouTube «Поделиться» → Lumen → «🧪 Шаблон монтажа по этому видео». Lumen снимает замеры (темп речи, длины планов по раскадровке, паузы, цвета), берёт 12 кадров раскадровки и субтитры с таймкодами; AI воссоздаёт подход к монтажу (🧪) и 1–2 моушн-шаблона. Gemini смотрит само видео, другие модели видят лист кадров (если умеют картинки). Копируются только приёмы, не тексты и не персонажи.",
                    "Template from a video: \"🎬 From a video\" in \"📚 Templates\" (a link in the clipboard is filled in) or YouTube \"Share\" → Lumen → \"🧪 Editing template from this video\". Lumen measures the video (speech pace, shot lengths from the storyboard, pauses, colours), takes 12 storyboard frames and timed subtitles; the AI recreates its way of editing (🧪) and 1–2 motion templates. Gemini watches the video itself, other models see a sheet of frames (if they can see pictures). Only techniques are copied, not texts or characters."),
                t("«💓 Пульс» — картинка слегка дышит в такт громкости голоса. «✨ Живые эмодзи» — анимированные эмодзи Noto (Google, CC BY 4.0) загружаются при сборке ролика; у каких нет анимации, остаются обычными.",
                    "\"💓 Pulse\" makes the picture breathe with the loudness of the voice. \"✨ Live emoji\": animated Noto emoji (Google, CC BY 4.0) are downloaded when the video is built; those without an animation stay still."),
                t("Анимация фигур: AI-режиссёр ставит стрелки, кольца, подчёркивания, галочки, крестики, рамки, «прожектор» и надписи на ключевые кадры (позиция, масштаб, поворот, прозрачность, прорисовка) с плавными кривыми — стрелка рисуется к цифре, кольцо выскакивает вокруг главного.",
                    "Shape animation: the AI director puts arrows, rings, underlines, ticks, crosses, frames, a spotlight and labels on key frames (position, scale, turn, opacity, drawing) with smooth curves: an arrow draws itself to a number, a ring pops around the key point."),
                t("B-roll: для сцен, где фото лучше схемы, AI даёт английский запрос, а Lumen находит фото со свободной лицензией в Openverse (без ключа; уходят только слова запроса) и показывает его на весь кадр или в карточке с медленным наездом. С галочкой «🎞 Сначала реальные видео-клипы» Lumen сперва ищет настоящее видео: Wikimedia Commons (без ключа), Pexels и Pixabay (бесплатные ключи, кнопка «🔑»). Запрос расширяется ступенями (вся фраза → два главных слова → одно), берётся клип под формат кадра и длину сцены, один клип не повторяется в разных сценах; если клипа нет — фото. «⧉ Источники B-roll» копирует подписи авторов для описания ролика.",
                    "B-roll: for scenes where a photo beats a scheme the AI gives English search words and Lumen finds an openly licensed photo on Openverse (no key; only the search words leave the phone), shown full-frame or on a card with a slow push-in. With \"🎞 Real video clips first\" Lumen first looks for real footage: Wikimedia Commons (no key), Pexels and Pixabay (free keys, the \"🔑\" button). The search widens step by step (whole phrase → two main words → one), the clip fitting the frame shape and the scene length wins, a clip is not repeated across scenes; with no clip a photo is used. \"⧉ B-roll credits\" copies the author credits for the description."),
                t("Звуки эффектов синтезируются в самом приложении, без файлов и сети; их можно выключить в шаге 4.", "Sound effects are synthesised by the app itself, with no files or network; turn them off in step 4."),
                t("Автомонтаж своего видео: «✂ Вырезать паузы» убирает тишину (джамп-каты), «Зумы» чередуют крупность и плавно наезжают камерой, «Переходы» вводят вставки слайдом. «🎞 Раскадровка» показывает кадр каждой сцены.",
                    "Auto-edit of your video: \"✂ Cut out pauses\" removes silence (jump cuts), \"Zooms\" alternate framing and push in, \"Transitions\" slide inserts in. \"🎞 Storyboard\" shows a frame of every scene."),
                t("«Клипы»: в карточке «🎞 Клипы с YouTube» добавьте клип по ссылке — начало, конец (до 30 с) и подпись, что в кадре. «⬇ Скачать» загружает только эти секунды видео без звука. AI-режиссёр ставит клипы на сцены по подписям; сцену можно поменять вручную нажатием в списке сцен или кнопкой «🎞 Расставить клипы по сценам без ИИ» под списком сцен. Между клипами идёт графика. Чужие видео защищены авторским правом: берите короткие вставки.",
                    "\"Clips\": in the \"🎞 YouTube clips\" card add a clip by link: start, end (up to 30 s) and a note on what is in the shot. \"⬇ Download\" fetches only those seconds of video, without sound. The AI director puts the clips on scenes by their notes; tap a scene in the list or use \"🎞 Place the clips on scenes without AI\" under the scene list to change it. Graphics fill the scenes between clips. Other people's videos are copyrighted: use short inserts."),
                t("«Видео на фоне»: добавьте клипы под любую озвучку (голос Microsoft, микрофон, аудио) — они идут по очереди и повторяются по кругу на всю длину озвучки.",
                    "\"Background video\": add clips under any voice-over (Microsoft voice, microphone, audio); they play in order and loop for the whole voice-over."),
                t("🪄 AI-помощник сценария (под сценарием): выберите просьбы — сильнее хук, короче, проще, смешнее, проверь факты, больше фактов или графики, концовка; в «Анимации» ещё живее диалог, поровну реплик, эмоции — или напишите свою. AI вернёт весь сценарий с правками и список изменений; «↩ Вернуть прежний» отменяет правку.",
                    "🪄 Script AI assistant (under the script): pick requests — stronger hook, shorter, simpler, funnier, check facts, more facts or visuals, ending; in Animation also livelier dialogue, balance lines, moods — or write your own. AI returns the whole script with the changes and a list of them; \"↩ Undo\" restores the old text."),
                t("«Анимация»: в карточке «🎭 Персонажи» выберите до четырёх героев из 16 (лиса, кот, медведь, робот, сова, ребёнок, девочка, профессор, пёс, кролик, панда, пингвин, лягушка, пришелец, динозавр, капля), имя и голос. AI пишет диалог, где реплика начинается с @Имя или @Имя(эмоция). Каждый герой говорит своим голосом Microsoft с собственным тоном, рот двигается по слогам, герои моргают, жестикулируют и показывают эмоции, а рядом идут схемы и цифры. Реплики без @Имени читает рассказчик.",
                    "\"Animation\": in the \"🎭 Characters\" card pick up to four of 16 characters (fox, cat, bear, robot, owl, kid, girl, professor, dog, bunny, panda, penguin, frog, alien, dino, blob), a name and a voice. AI writes a dialogue where a line starts with @Name or @Name(mood). Each character speaks in its own Microsoft voice and pitch, the mouth moves by syllables, characters blink, gesture and show emotions while schemes and numbers play next to them. Lines without @Name are read by the narrator."),
                t("Свои персонажи: в редакторе героя (✎ или «＋ Персонаж») есть основа из 16 заготовок и детали — тело, уши и рога, волосы, живот или одежда, глаза, нос, хвост, до 4 аксессуаров (очки, цилиндр, кепка, бантик, корона, наушники, галстук, шарф), 5 цветов и пропорции; ниже голос, тон, темп и характер — AI-сценарист пишет реплики в этом характере. «🎲 Случайно» собирает героя наугад, «✨ По описанию» и «🖼 По картинке» — встроенным AI (для картинки нужна модель, которая видит изображения). «🧸 Картинка — сам герой»: своя картинка (лучше PNG с прозрачным фоном) становится персонажем — отметьте глаза и рот, и он будет моргать, говорить и качаться по эмоции; «🪄 Убрать фон» убирает однотонный фон. «🤖 Через другой ИИ» копирует запрос для ChatGPT, Gemini или другого ИИ, ответ вставляется кнопкой «📥 Вставить». Формат ответа — протокол lumen.char (JSON), им же героя можно передать «⧉ Копировать код». «💾 В библиотеку» сохраняет героя для других роликов (кнопка «📚 Библиотека»).",
                    "Own characters: the character editor (✎ or \"＋ Character\") has a base of 16 looks and parts — body, ears and horns, hair, belly or clothes, eyes, nose, tail, up to 4 accessories (glasses, top hat, cap, bow, crown, headphones, tie, scarf), 5 colours and proportions; below are the voice, pitch, rate and persona — the AI script writer keeps it. \"🎲 Random\" builds one at random, \"✨ From a description\" and \"🖼 From a picture\" use the built-in AI (a picture needs a model that sees images). \"🧸 Picture as the character\": your own picture (best a PNG with a transparent background) becomes the character — mark the eyes and the mouth and it blinks, talks and sways with the mood; \"🪄 Remove background\" clears a plain background. \"🤖 With another AI\" copies a request for ChatGPT, Gemini or any AI; paste the answer with \"📥 Paste\". The answer format is the lumen.char protocol (JSON), also used to share a character with \"⧉ Copy code\". \"💾 To the library\" keeps a character for other videos (\"📚 Library\")."),
                t("🤖 «AI сделает всё» (Анимация, необязательно): опишите идею, сцены и/или сценарий — любое поле можно оставить пустым. AI напишет реплики героев, озвучит их, расставит графику и монтаж и сам соберёт видео.",
                    "🤖 \"AI makes everything\" (Animation, optional): describe an idea, scenes and/or a script; any field may stay empty. AI writes the lines, voices the characters, adds graphics and editing and builds the video."),
                t("Длинные задачи (AI, озвучка, импорт, загрузка, сборка) идут в фоне с уведомлением о прогрессе: приложение можно свернуть или закрыть экран — по окончании придёт уведомление.",
                    "Long tasks (AI, voice, import, download, build) run in the background with a progress notification: minimise the app or close the screen, a notification comes when they end."),
            )),
        HelpTopic("montage", t("Слой ИИ: анимация поверх видео", "AI layer: animation over a video"),
            t("Второй рендер Студии. ИИ пишет код слоя анимации (протокол lumen.canvas): плашки, титры, стрелки, счётчики, подсветку слов, прогресс. Lumen рисует его кадр за кадром поверх готового ролика и сохраняет новый MP4 со звуком. Каждый ответ ИИ становится шаблоном — их можно переключать и сравнивать на одном видео. Ручной редактор — отдельный экран «✏️ Редактор ↗» внизу Студии.",
                "The Studio's second renderer. AI writes the code of an animation layer (lumen.canvas protocol): stickers, titles, arrows, counters, word highlights, progress. Lumen draws it frame by frame over a finished video and saves a new MP4 with the sound. Every AI answer becomes a template — switch and compare them on one video. The hand editor is its own screen, \"✏️ Editor ↗\" at the bottom of the Studio."),
            listOf(
                t("Соберите ролик в Студии и нажмите под ним «🎛 Анимированный слой ИИ поверх ролика». Если в Студии уже собран ролик, он откроется сразу вместе с речью и сценами (ИИ будет попадать в слова). Иначе — «🎞 Выбрать видео» или «🎬 Последний ролик Студии».",
                    "Build a video in the Studio and tap \"🎛 An animated AI layer over the video\" under it. If a video is already built in the Studio, it opens right away with its speech and scenes (the AI hits the words). Otherwise — \"🎞 Pick a video\" or \"🎬 Last Studio video\"."),
                t("Напишите в поле, каким сделать монтаж: например «неоновые плашки с ценами, стрелка к цифре, счётчик подписчиков в конце». Можно оставить пустым — будет драйв по умолчанию.",
                    "Write what montage you want, e.g. \"neon stickers with prices, an arrow to the number, a subscriber counter at the end\". Leave empty for the default drive style."),
                t("«⧉ Запрос для другого ИИ» → «Под это видео» (с речью и секундами) или «Универсальный шаблон» (подойдёт к любому ролику). Вставьте в Gemini, ChatGPT или Claude.",
                    "\"⧉ Request for another AI\" → \"For this video\" (with speech and seconds) or \"Reusable template\" (fits any video). Paste it into Gemini, ChatGPT or Claude."),
                t("Скопируйте ответ ИИ и вернитесь: появится «📋 В буфере код слоя от ИИ» → «✓ Сохранить и проверить». Или «📥 Вставить ответ». Код сохранится шаблоном, сразу покажется превью 4 моментов ролика.",
                    "Copy the AI answer and come back: \"📋 Layer code from an AI in the clipboard\" → \"✓ Keep and preview\". Or \"📥 Paste the answer\". The code is kept as a template and a preview of 4 moments appears."),
                t("Без чата: «✨ Встроенный ИИ» (нужен провайдер в Настройки → AI) или «🧩 Пример» — готовый слой для проверки.",
                    "Without a chat: \"✨ Built-in AI\" (needs a provider in Settings → AI) or \"🧩 Example\" — a ready layer to try."),
                t("Если в превью ошибка (✗ с номером строки): «🛠 Исправить (ИИ)» или «⧉ Ошибку — в другой ИИ» — скопируется запрос с ошибкой и кодом, исправленный ответ вставьте так же. Исправление обновляет тот же шаблон.",
                    "If the preview shows an error (✗ with a line number): \"🛠 Fix (AI)\" or \"⧉ The error — to another AI\" — a request with the error and the code is copied; paste the fixed answer the same way. A fix updates the same template."),
                t("Пробуйте разные монтажи: просите у ИИ новые варианты — каждый ответ ложится отдельным шаблоном в «📚 Шаблоны монтажа». «▶ Выбрать» делает шаблон текущим и показывает превью, «⇄ Сравнить на этом видео» ставит до 8 шаблонов рядом по 3 одинаковых кадра.",
                    "Try different montages: ask the AI for new variants — each answer becomes its own template in \"📚 Montage templates\". \"▶ Use\" makes it current and shows the preview, \"⇄ Compare on this video\" puts up to 8 templates side by side, 3 same frames each."),
                t("«🎬 Рендер с этим монтажом» собирает видео со звуком исходника и сохраняет в «Фильмы» (Lumen_Montage_…). Держите экран открытым: слой рисуется кадр за кадром, примерно 2–5 минут на минуту ролика. «Остановить» прерывает.",
                    "\"🎬 Render with this montage\" builds the video with the original sound and saves it to Movies (Lumen_Montage_…). Keep the screen open: the layer is drawn frame by frame, about 2–5 minutes per minute of video. \"Stop\" interrupts."),
                t("Ручные правки (схемы, картинки, надписи на таймлайне) — карточка «✏️ Ручной редактор» вверху экрана или вкладка «✏️ Редактор».",
                    "Hand edits (schemes, pictures, captions on a timeline) — the \"✏️ Hand editor\" card at the top or the \"✏️ Editor\" tab.")
            ),
            listOf(
                t("Шаблон — это код с первой строкой // name: Название. Имя меняется кнопкой «Имя», код — «✎ Править», «⧉» копирует код, «🗑» удаляет. Одинаковый код дважды не сохраняется.",
                    "A template is code whose first line is // name: Title. \"Name\" renames, \"✎ Edit\" changes the code, \"⧉\" copies it, \"🗑\" deletes. The same code is never kept twice."),
                t("Универсальные шаблоны берут тексты и время из речи ролика, поэтому один стиль можно применять к разным видео.", "Reusable templates take texts and times from the video's speech, so one style works on different videos."),
                t("Слой работает в WebView без интернета: картинки и шрифты по ссылкам не загрузятся, шрифты только системные, эмодзи можно. Текст протокола — кнопка «📄 Протокол».",
                    "The layer runs in a WebView without internet: pictures and fonts by link do not load, only system fonts, emoji are fine. The protocol text — the \"📄 Protocol\" button."),
                t("Галочка «Слой в полном разрешении» делает надписи чётче, но рендер медленнее. Если в видео уже есть субтитры из Студии, ИИ получает указание их не перекрывать.",
                    "The \"Layer at full resolution\" tick makes text sharper but the render slower. If the video already has Studio subtitles, the AI is told not to cover them.")
            )),
        HelpTopic("player", t("Плеер YouTube", "YouTube player"),
            t("Поиск и просмотр YouTube в стиле Lumen: быстрые фильтры, ИИ-отбор по теме, компактный список с действиями, звук в фоне, прошлые поиски без интернета.",
                "YouTube search and watching in the Lumen style: quick filters, AI picks by topic, a compact list with actions, sound in the background, past searches offline."),
            listOf(
                t("Вход: меню ⋮ на главной → «▶ Плеер», ярлык «Плеер» на значке Lumen, «Поделиться → Lumen» со ссылкой → «▶ Смотреть в плеере Lumen». В режиме «Аналитика + плеер» кнопка «Смотреть» везде открывает этот плеер.",
                    "Entry: the ⋮ menu on Home → \"▶ Player\", the \"Player\" shortcut on the Lumen icon, \"Share → Lumen\" with a link → \"▶ Watch in the Lumen player\". In the \"Analytics + player\" mode the \"Watch\" button opens this player everywhere."),
                t("Строка поиска: слова или ссылка на видео (сразу откроется), 🎤 — голосом. Подсказки: 🕘 ваши прошлые запросы и 🔍 подсказки YouTube; ↖ — вставить в строку, не ища.",
                    "Search bar: words or a video link (opens at once), 🎤 — by voice. Suggestions: 🕘 your past queries and 🔍 YouTube's; ↖ — put into the field without searching."),
                t("Быстрые фильтры под строкой: 📅 период (сегодня, неделя, месяц, год) или год выпуска, 👁 просмотров не меньше, 💬 комментариев не меньше (Lumen считает их у каждого видео — дольше), ⏱ длина, Shorts (все/без/только), ↕ порядок. «✕ Сбросить» — всё по умолчанию. Фильтры запоминаются.",
                    "Quick filters under the bar: 📅 period (today, week, month, year) or upload year, 👁 at least views, 💬 at least comments (Lumen counts them per video — slower), ⏱ length, Shorts (all/none/only), ↕ order. \"✕ Reset\" — all defaults. Filters are remembered."),
                t("🧠 Тема и критерии (ИИ, по желанию): выберите область (наука, технологии, бизнес…), критерии (без кликбейта, экспертно, без реакций…), напишите своими словами или возьмите тему своего проекта. ИИ уберёт неподходящее; под списком «🧠 Отброшено ИИ» — раскрыть, причина у каждого, ↩ — вернуть в выдачу.",
                    "🧠 Topic and criteria (AI, optional): pick an area (science, tech, business…), criteria (no clickbait, expert, no reactions…), write your own words or take your project's topic. The AI removes what does not fit; under the list \"🧠 Dropped by AI\" — open it, each has a reason, ↩ — bring it back."),
                t("Список: тап — смотреть здесь, 🎧 — слушать в фоне (звук с уведомлением, можно выключить экран), ⋮ или долгое нажатие — очередь фона, окно поверх приложений, скачать аудио/видео, откусить кусок, анализ в Lumen, ещё с канала, поделиться. Сверху — «▶ Смотреть подряд», «🎧 Слушать все в фоне», «＋ Все в очередь». Прокрутка вниз подгружает ещё.",
                    "List: tap — watch here, 🎧 — listen in background (sound with a notification, the screen can go off), ⋮ or long press — background queue, floating window, download audio/video, bite a piece, analyse in Lumen, more from the channel, share. On top — \"▶ Watch in a row\", \"🎧 Listen to all in background\", \"＋ All to the queue\". Scrolling down loads more."),
                t("Плеер: тап — показать/скрыть управление, двойной тап слева/справа — на 10 с, в центре — пауза; ⛶ или поворот телефона — весь экран; ⚙ — качество (из того, что есть у видео), скорость ×0.5–×2, следующее видео само, «свернул — слушать в фоне». 🎧 продолжит звук в фоне с того же места; «▶ Сюда» вернёт его на экран.",
                    "Player: tap — show/hide controls, double-tap left/right — 10 s, centre — pause; ⛶ or turning the phone — full screen; ⚙ — quality (what the video has), speed ×0.5–×2, play next by itself, \"leaving — keep sound in background\". 🎧 continues the sound in the background from the same place; \"▶ Here\" brings it back to the screen."),
                t("Без поиска на экране — «Продолжить просмотр» (с полосой просмотренного; долгое нажатие — убрать) и «Прошлые поиски»: результаты с фильтрами и отбором ИИ открываются мгновенно, без интернета; ✕ — удалить, ↻ у открытого — обновить. 🗓 — сколько хранить (1, 3, 7, 30 дней или не хранить), старые удаляются сами.",
                    "With no search on the screen — \"Continue watching\" (with a watched bar; long press — remove) and \"Past searches\": results with filters and AI picks open instantly, offline; ✕ — delete, ↻ on an open one — refresh. 🗓 — how long to keep (1, 3, 7, 30 days or not at all), old ones delete themselves.")
            ),
            listOf(
                t("Фильтры по дате и длине проверяются по данным поиска YouTube; у части видео дата «2 дня назад» приблизительна.", "Date and length filters use YouTube's search data; for some videos \"2 days ago\" is approximate."),
                t("Фоновое прослушивание и окно идут через плеер NewPipe: управление — в уведомлении. Промпт ИИ-отбора: Настройки → AI → Промпты → player_filter.",
                    "Background listening and the window use the NewPipe player: controls are in the notification. The AI filter prompt: Settings → AI → Prompts → player_filter.")
            )),
        HelpTopic("bite", t("Откусить видео или аудио", "Bite a video or audio"),
            t("Сохраняет на телефон кусок видео с YouTube — видео со звуком (MP4) или только звук (M4A). Качается только нужный кусок, без пережатия, поэтому быстро и без потери качества.",
                "Saves a piece of a YouTube video on the phone — a video with sound (MP4) or the sound only (M4A). Only the needed piece is downloaded, without re-encoding, so it is fast and keeps the quality."),
            listOf(
                t("Быстрее всего — из YouTube: «Поделиться» → «Откусить и сохранить отрезок». Окно откроется поверх YouTube: видео или аудио, качество, отрезок ползунком (тянуть ручки или весь отрезок) или точными секундами (−/＋ двигают на шаг). «✂ Откусить и сохранить отрезок» — окно закроется, прогресс и «Сохранено» придут уведомлениями.",
                    "Fastest from YouTube: \"Share\" → \"Bite and save the piece\". A sheet opens over YouTube: video or sound, quality, the piece by the range bar (drag the handles or the whole piece) or exact seconds (−/＋ move by a step). \"✂ Bite and save the piece\" — the sheet closes, progress and \"Saved\" come as notifications."),
                t("Вход: меню ⋮ на главной → «✂ Откусить видео или аудио», или «Поделиться → Lumen» со ссылкой на видео → «✂ Откусить кусок видео или аудио».",
                    "Entry: the ⋮ menu on Home → \"✂ Bite a video or audio\", or \"Share → Lumen\" with a video link → \"✂ Bite a piece of video or audio\"."),
                t("1 Видео: вставьте ссылку или напишите, что искать, и нажмите на найденное видео. Время в ссылке (?t=83) станет началом.",
                    "1 Video: paste a link or type what to search and tap a found video. A time in the link (?t=83) becomes the start."),
                t("2 Кусок: кадры по всему видео — тап включает видео с этого места. «⟦ Начало здесь» и «Конец здесь ⟧» берут время плеера; можно ввести вручную (1:23 или 83) или нажать длину 5 с … 1 мин. «▶ Проверить кусок» проигрывает отрезок.",
                    "2 Piece: frames across the video — a tap plays from there. \"⟦ Start here\" and \"End here ⟧\" take the player's time; you can type it (1:23 or 83) or tap a length 5 s … 1 min. \"▶ Check the piece\" plays it."),
                t("3 Сохранить: «🎬 Видео со звуком» (качество 240p–1080p) → Галерея, Фильмы/Lumen; «🎵 Только аудио» → Музыка/Lumen. Качество звука: высокое (~128 кбит/с) или экономное (~48 кбит/с). Загрузка идёт в фоне, её видно в уведомлении и в «⚙ Задачах».",
                    "3 Save: \"🎬 Video with sound\" (240p–1080p) → Gallery, Movies/Lumen; \"🎵 Sound only\" → Music/Lumen. Sound quality: high (~128 kbit/s) or small (~48 kbit/s). The download runs in the background, shown in the notification and in \"⚙ Tasks\"."),
                t("«Откушенное» внизу: ▶ открыть, ↗ поделиться, «💾 В папку…» — сохранить копию куда угодно, 🗑 — убрать из списка.",
                    "\"Bitten\" below: ▶ open, ↗ share, \"💾 To a folder…\" — save a copy anywhere, 🗑 — remove from the list.")
            ),
            listOf(
                t("Видео без пережатия начинается с ключевого кадра — иногда на 1–3 с раньше выбранного начала. Звук отдельно режется точно.",
                    "Without re-encoding the video starts at a key frame — sometimes 1–3 s before the chosen start. The sound alone is cut exactly."),
                t("Кусок — до 60 минут. Сохраняйте только то, на что у вас есть права.", "A piece is up to 60 minutes. Save only what you have the rights to.")
            )),
        HelpTopic("insert", t("Редактор: схема или анимация от ИИ", "Editor: AI scheme or animation"),
            t("Помощник вставки Редактора Студии. Вы говорите смыслом — «сравнение», «число-факт», «шаги» — а Lumen сам решает, рисовать это нативной инфосхемой или анимацией (код ИИ по протоколу lumen.anim, заранее отрисованный в кадры). Сделать может встроенный ИИ или любой чат-ИИ: запрос копируется, ответ Lumen берёт из буфера.",
                "The insert wizard of the Studio editor. You speak in meaning — \"comparison\", \"number fact\", \"steps\" — and Lumen decides whether to draw it as a native info-scheme or as an animation (AI code by the lumen.anim protocol, rendered into frames in advance). The built-in AI or any chat AI can make it: the request is copied and Lumen takes the answer from the clipboard."),
            listOf(
                t("Поставьте курсор в нужный момент (или выделите фразу в «📝 Текст речи») → «＋ Вставить» → «✨ Схема или анимация от ИИ».",
                    "Put the playhead at the moment (or select a phrase in \"📝 Speech text\") → \"＋ Insert\" → \"✨ AI scheme or animation\"."),
                t("1 Что: выберите тип по смыслу. «🧪 Примеры» вставляют готовые анимации без ИИ, «📚 Моя библиотека» — сохранённые, «⭐ Мои рецепты» и «↺ Как в прошлый раз» — прежний вид.",
                    "1 What: pick a type by meaning. \"🧪 Examples\" insert ready animations without AI, \"📚 My library\" — saved ones, \"⭐ My recipes\" and \"↺ Like last time\" — an earlier look."),
                t("2 Бриф: сверху макет на текущем кадре — место блока, подложка, затемнение, запретные зоны (лицо, субтитры, интерфейс Shorts). Достаточно поля «Что показать»; разделы А–Е раскрываются по желанию, «Авто» решает ИИ или стиль. Предупреждения снизу исправляются одним касанием.",
                    "2 Brief: on top a mock-up on the current frame — the block's place, plate, dimming and zones to keep free (face, subtitles, Shorts interface). \"What to show\" is enough; sections A–F open on demand, \"Auto\" leaves it to the AI or the style. Warnings below are fixed in one tap."),
                t("Видео под блоком можно не только затемнить, но и размыть («В. Прозрачность»: Нет / Слабо / Сильно) — мелкие детали не мешают тексту; размытие плавно появляется и уходит с блоком. У анимации рядом с «Похоже на…» есть «🖼 Картинка-образец»: скриншот понравившегося дизайна — встроенный ИИ увидит его вместе с запросом, другому ИИ его отправит «↗ Поделиться запросом».",
                    "The video under the block can be blurred as well as dimmed (\"C. Transparency\": None / Light / Strong) — small details stay out of the text's way; the blur fades in and out with the block. An animation has \"🖼 Sample picture\" next to \"Looks like…\": a screenshot of a design you like — the built-in AI sees it with the request, \"↗ Share the request\" sends it to another AI."),
                t("3 Кто сделает: «🤖 Встроенный ИИ» или «⧉ Другой ИИ». Во втором случае на таймлайне сразу появится блок «⏳ ждёт ответ ИИ #метка» — вставьте запрос в Gemini, ChatGPT или Claude, скопируйте ответ и вернитесь: Lumen возьмёт его сам.",
                    "3 Who makes it: \"🤖 Built-in AI\" or \"⧉ Another AI\". With the second one a \"⏳ awaits AI answer #mark\" block appears on the timeline at once — paste the request into Gemini, ChatGPT or Claude, copy the answer and come back: Lumen takes it itself."),
                t("4 Проверка: кадры результата поверх видео. Без ИИ меняются прозрачность, подложка, затемнение, место, размер и длина; через ИИ — чипы «Крупнее текст», «Меньше слов», «Медленнее»… и своя правка. «✅ Вставить» — один шаг отмены.",
                    "4 Check: frames of the result over the video. Without AI you change the opacity, plate, dimming, place, size and length; through the AI — chips \"Bigger text\", \"Fewer words\", \"Slower\"… and your own edit. \"✅ Insert\" is one undo step."),
                t("Анимация рисуется в кадры прямо на блоке (значок «🎞 N %» на превью), пока экран редактора открыт. После смены длины или размера блока кадры перерисуются сами через полторы секунды; перед экспортом Lumen предложит перерисовать то, что не успело.",
                    "An animation is rendered into frames right on the block (the \"🎞 N %\" badge on the preview) while the editor is open. After the block's length or size changes the frames are redrawn by themselves in a second and a half; before the export Lumen offers to redraw what is left.")
            ),
            listOf(
                t("У выбранной схемы или анимации на панели: «🤖 Переделать» (та же форма с сохранённым брифом), «🛠 Проверить» (кадры, ошибки кода, правки, прошлые версии), «🌓 Под блоком» (затемнение видео).",
                    "A selected scheme or animation has on its panel: \"🤖 Redo\" (the same form with the saved brief), \"🛠 Check\" (frames, code errors, edits, previous versions), \"🌓 Under it\" (dimming the video)."),
                t("«Е. Стиль ролика» запоминает вид, прозрачность и темп для всех новых вставок этого ролика; «⭐ Сохранить как рецепт» — для любых роликов.",
                    "\"F. Style of the video\" keeps the look, transparency and pace for all new inserts of this video; \"⭐ Save as a recipe\" — for any video."),
                t("Шаблоны «🎛 Слоя ИИ» можно поставить на отрезок: «🎛 Шаблон слоя ИИ» на первом шаге.", "Templates of the \"🎛 AI layer\" can go on a piece: \"🎛 AI layer template\" on the first step."),
                t("⚡ Без ИИ: число, столбцы, кольцо, список, A против B, подпись спикера, призыв и схемы из строк данных Lumen рисует сам — сразу, в теме ролика, любой длины. Превью движения видно прямо в брифе.",
                    "⚡ Without AI: a number, bars, a ring, a list, A versus B, a lower third, a call to action and schemes from data rows are drawn by Lumen itself — at once, in the video theme, at any length. The motion preview is right in the brief."),
                t("🖌 Тема (панель редактора): 12 готовых тем, «Из цветов видео», «По картинке» (ИИ смотрит логотип или скриншот), свой шрифт .ttf/.otf; «Перекрасить все вставки» — одним шагом отмены. Здесь же площадка: Shorts, Reels, TikTok, YouTube — от неё зависят запретные зоны.",
                    "🖌 Theme (the editor bar): 12 ready themes, \"From the video\", \"From a picture\" (the AI looks at a logo or screenshot), an own .ttf/.otf font; \"Recolour all inserts\" — one undo step. The platform is here too: Shorts, Reels, TikTok, YouTube — the zones to keep free depend on it."),
                t("🧩 ИИ → «💡 Где нужны вставки»: Lumen находит в речи числа, перечисления, даты, сравнения и призывы, ставит 💡 на таймлайн и вставляет отмеченное без ИИ; «📊 Отчёт» — плотность, покрытие, время на чтение, зоны, единство стиля; «🌐 Перевести» — тексты всех вставок на другой язык.",
                    "🧩 AI → \"💡 Where inserts are needed\": Lumen finds numbers, enumerations, dates, comparisons and calls in the speech, puts 💡 on the timeline and inserts the marked ones without AI; \"📊 Report\" — density, coverage, reading time, zones, style unity; \"🌐 Translate\" — the texts of all inserts into another language."),
                t("Анимация ИИ (lumen.anim v1.1) может дать 🎛 Параметры (текст, цвет, скорость…) — они меняются без ИИ; с сегментами intro/loop/outro блок подрезается без перерисовки. «🎲 3 варианта» — три ответа сразу, выбор миниатюрой.",
                    "An AI animation (lumen.anim v1.1) may offer 🎛 Parameters (text, colour, speed…) — they change without AI; with intro/loop/outro segments the block is trimmed without a redraw. \"🎲 3 variants\" — three answers at once, picked by a miniature."),
                t("🌀 Lottie (.json) — готовая векторная анимация: тексты меняются, цвет — в акцент темы. 🎯 «Прилипнуть» — блок едет за объектом под ним. Акценты «Пульс», «Дрожь», «Покачивание» и входы «Шторка», «Падение», «Вращение», «Печать» — в «🎞 Анимация».",
                    "🌀 Lottie (.json) — ready vector animation: texts change, the colour goes to the theme accent. 🎯 \"Track\" — the block follows the object under it. Accents \"Pulse\", \"Shake\", \"Wiggle\" and ins \"Wipe\", \"Drop\", \"Spin\", \"Typewriter\" — in \"🎞 Animation\".")
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
                t("Вкладка «Посты» — публикация в ваши каналы и группы и автонаполнение из RSS, YouTube и тем для ИИ. Подробно — раздел «Боты: посты в каналы и группы».",
                    "The Posts tab publishes to your channels and groups and auto-fills them from RSS, YouTube and AI topics. Details: “Bots: posts to channels and groups”."),
                t("Один токен может обслуживать только один сервер. Если бот уже работает где-то ещё, Lumen покажет «Токен занят».", "One token can be served by one server only. If the bot already runs elsewhere, Lumen shows \"Token in use\".")
            )),
        HelpTopic("bot_posts", t("Боты: посты в каналы и группы", "Bots: posts to channels and groups"),
            t("Вкладка «Посты» в экране бота: бот публикует посты в ваши Telegram-каналы и группы — вручную, по времени или сам по расписанию " +
                "(автонаполнение из RSS-лент, YouTube и тем для ИИ). Публикует сам телефон, сервер не нужен.",
                "The Posts tab of a bot: the bot publishes to your Telegram channels and groups — by hand, at a set time or by itself on a schedule " +
                    "(auto-filling from RSS feeds, YouTube and AI topics). The phone posts by itself, no server is needed."),
            listOf(
                t("1. Канал: в Telegram откройте канал → ✏️ / «Управление каналом» → «Администраторы» → «Добавить администратора» → найдите @имя_бота → оставьте право «Публикация сообщений» → «Готово».",
                    "1. Channel: in Telegram open the channel → ✏️ / Manage channel → Administrators → Add admin → find @your_bot → keep “Post messages” → Done."),
                t("   Группа: «Добавить участника» → @имя_бота. Если в группе писать могут не все, сделайте бота администратором.",
                    "   Group: Add member → @your_bot. If not everyone may write in the group, make the bot an admin."),
                t("2. Во вкладке «Посты» нажмите «＋ Канал или группа» и введите @имя канала или ссылку t.me/имя. Lumen проверит, что бот может там писать.",
                    "2. In the Posts tab tap “＋ Channel or group” and enter the channel @name or a t.me/name link. Lumen checks that the bot can post there."),
                t("   Закрытая группа или канал без @имени: запустите бота (▶ Запуск) и добавьте его в чат — чат сам появится в списке. Можно ввести и id (-100…) или ссылку на любое сообщение вида t.me/c/123…/45.",
                    "   A private group or channel without an @name: start the bot (▶ Start) and add it to the chat — the chat appears in the list by itself. You can also enter the id (-100…) or a message link like t.me/c/123…/45."),
                t("3. «Новый пост»: напишите текст, при желании добавьте ссылку на картинку и кнопку (текст + https-ссылка), отметьте чаты.",
                    "3. New post: write the text, optionally add a picture link and a button (text + https link), tick the chats."),
                t("   «✨ Написать ИИ» — пост по теме или новости; «✨ Улучшить» — правка готового текста словами («короче», «добавь призыв»).",
                    "   “✨ Write with AI” — a post on a topic or news; “✨ Improve” — edit the text in words (“shorter”, “add a call to action”)."),
                t("4. «Тест мне» — пост придёт вам в личку от бота (нужно стать владельцем: вкладка «Настройки»). «Опубликовать» — сразу во все отмеченные чаты.",
                    "4. “Test to me” — the bot sends the post to your private chat (become the owner first: Settings tab). “Publish” — to all ticked chats now."),
                t("5. «⏰ Позже» — по времени: 18:30 (сегодня или завтра), 25.12 09:00, +30м, +2ч, +1д. Отложенные посты уходят, пока бот запущен.",
                    "5. “⏰ Later” — at a time: 18:30 (today or tomorrow), 25.12 09:00, +30m, +2h, +1d. Scheduled posts go out while the bot runs."),
                t("6. «＋ Новое автонаполнение»: укажите источники (по одному в строке), куда, как часто, сколько постов за раз и часы работы. Затем «Запустить сейчас», чтобы проверить, и ▶ Запуск бота.",
                    "6. “＋ New auto-filling”: set sources (one per line), where, how often, posts per run and active hours. Then “Run now” to check it, and ▶ Start the bot."),
                t("   Источники: https://сайт/rss — RSS/Atom-лента новостей или блога; yt: запрос — свежие видео YouTube; ai: тема — ИИ сам пишет пост на тему.",
                    "   Sources: https://site/rss — an RSS/Atom feed of news or a blog; yt: query — fresh YouTube videos; ai: topic — AI writes a post on the topic."),
                t("7. «Присылать на проверку» — готовые посты не уходят сразу, а ждут в блоке «На проверку»: опубликуйте, поправьте («В редактор») или удалите. Придёт уведомление.",
                    "7. “Send for review” — ready posts wait in “To review” instead of going out: publish, fix (“To editor”) or delete. You get a notification."),
                t("8. «История» — каждая публикация: куда, когда и ошибка, если что-то не так.", "8. History — every publication: where, when and the error if something went wrong.")
            ),
            listOf(
                t("Где взять RSS: у многих сайтов лента по адресу /rss, /feed или /rss.xml (например, https://habr.com/ru/rss/articles/). Лента YouTube-канала: https://www.youtube.com/feeds/videos.xml?channel_id=UC…",
                    "Finding RSS: many sites have a feed at /rss, /feed or /rss.xml. A YouTube channel feed: https://www.youtube.com/feeds/videos.xml?channel_id=UC…"),
                t("Разметка текста: <b>жирный</b>, <i>курсив</i>, <u>подчёркнутый</u>, <s>зачёркнутый</s>, <a href=\"https://…\">ссылка</a>, <blockquote>цитата</blockquote>. Если Telegram не примет разметку, пост уйдёт простым текстом.",
                    "Formatting: <b>bold</b>, <i>italic</i>, <u>underline</u>, <s>strike</s>, <a href=\"https://…\">link</a>, <blockquote>quote</blockquote>. If Telegram rejects the markup, the post goes as plain text."),
                t("Подпись к картинке — до 1024 символов. Длиннее — картинка уйдёт отдельным сообщением, текст следом.", "A picture caption is up to 1024 characters. Longer — the picture goes separately, then the text."),
                t("«Стиль для ИИ» задаёт голос канала: тон, длину, эмодзи, хэштеги, подпись в конце («— Канал @mychannel»). ИИ не выдумывает факты и пишет на языке приложения.",
                    "“Style for AI” sets the channel voice: tone, length, emoji, hashtags, a signature (“— @mychannel”). AI does not invent facts."),
                t("Повторов не будет: Lumen помнит, что уже публиковал из каждого источника. Для ai:-тем ИИ видит последние посты и не повторяет их.",
                    "No repeats: Lumen remembers what it already posted from each source. For ai: topics AI sees the recent posts and avoids them."),
                t("Каждый пост с «ИИ переписывает» — один запрос к вашему AI-ключу. Раз в 30 минут × 3 поста = до 144 запросов в сутки: ставьте разумный интервал и часы работы.",
                    "Every post with “AI rewrites” is one request to your AI key. Every 30 min × 3 posts = up to 144 requests a day: choose a sensible interval and hours."),
                t("Не спамьте: Telegram ограничивает частоту (около 20 сообщений в минуту в одну группу) и может заблокировать бота за жалобы. Публикуйте только то, на что у вас есть права.",
                    "Do not spam: Telegram limits the rate (about 20 messages a minute per group) and may block a bot after complaints. Post only what you have the rights to."),
                t("«Нет прав на публикацию» — бот не админ или у него снято право «Публикация сообщений». «Чат не найден» — бот не добавлен в чат или адрес неверный. «Проверить права» покажет состояние всех чатов.",
                    "“Cannot post” — the bot is not an admin or lacks “Post messages”. “Chat not found” — the bot is not in the chat or the address is wrong. “Check rights” shows all chats."),
                t("«В ленте нет записей» — ссылка ведёт на обычную страницу, а не на RSS. Откройте её в браузере: лента выглядит как XML-текст.",
                    "“No items in the feed” — the link is a normal page, not RSS. Open it in a browser: a feed looks like XML text."),
                t("Автонаполнение работает, только пока бот запущен (▶ Запуск) и телефон в сети. Разрешите работу в фоне в настройках бота.",
                    "Auto-filling works only while the bot runs (▶ Start) and the phone is online. Allow background work in the bot settings."),
                t("Промпты постов (bot_post, bot_post_topic, bot_post_edit) можно изменить: Настройки → Промпты.", "Post prompts (bot_post, bot_post_topic, bot_post_edit) can be edited: Settings → Prompts.")
            )),
        HelpTopic("settings", t("Настройки", "Settings"),
            t("Язык, тема, режим, AI-провайдеры и модели, промпты, мониторинг, резервные копии, журнал ошибок.", "Language, theme, mode, AI providers and models, prompts, monitoring, backups, error log."),
            listOf(
                t("AI: выберите провайдера, нажмите «Получить ключ», вставьте ключ. Ключ шифруется и хранится только на устройстве.", "AI: pick a provider, tap \"Get key\", paste it. The key is encrypted and stays on the device."),
                t("Промпты: любой промпт можно отредактировать или сбросить.", "Prompts: any prompt can be edited or reset."),
                t("Логи: отчёт об ошибке для разработчика.", "Logs: a bug report for the developer."),
                t("«Проверить работу YouTube» показывает, что именно сломалось: поиск, видео, потоки, комментарии или канал. «Блокирует IP» значит VPN или прокси, а не поломку.",
                    "\"Test YouTube now\" shows what exactly broke: search, video, streams, comments or channel. \"Blocks this IP\" means a VPN or proxy, not a bug.")
            ))
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
    private var query = ""
    private val header by lazy {
        val box = Col(this, 0)
        box.root.setPadding(dp(16), dp(4), dp(16), dp(4))
        val e = box.add(edt(this, t("Найти в справке…", "Search the help…"), query), 0)
        e.onEnter { }
        e.addTextChangedListener(SimpleWatcher {
            query = it
            render(intent.getStringExtra("topic") ?: "about")
        })
        box.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = intent.getStringExtra("topic") ?: "about"
        expanded += first
        render(first)
    }

    private fun render(first: String) {
        val c = Col(this)
        val all = Help.topics()
        val found = if (query.isBlank()) null else all.filter {
            SearchIndex.score(query, SearchIndex.Item("help", it.title, it.what, it.id, (it.steps + it.tips).joinToString(" "))) > 0
        }
        val ordered = found ?: (listOfNotNull(all.firstOrNull { it.id == first }) + all.filter { it.id != first })
        if (found != null && found.isEmpty()) c.empty(t("Ничего не найдено", "Nothing found"), t("Попробуйте другое слово.", "Try another word."))
        for (tp in ordered) {
            c.card {
                section(tp.title, tp.what.take(80) + if (tp.what.length > 80) "…" else "", tp.id in expanded || found != null, { on ->
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
        page(t("Справка", "Help"), c.root, keepScroll = query.isBlank(), header = header)
    }
}
