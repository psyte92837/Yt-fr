/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

/**
 * A ready block-scheme template: a known framework of some field (SWOT, AIDA, Maslow, PDCA…) as a lumen.scheme
 * layout with placeholder nodes, and the look ([SchemeStyle] id) it is designed in. Nodes are written as lines
 * "emoji Label — value" separated by ";": "*" before the line marks the main node, "^" makes it a child of the
 * nearest plain node before it (tree), the first node of a tree is the root. [loop] closes a chain into a cycle.
 */
data class SchemeTpl(
    val id: String, val area: String, val layout: String, val look: String,
    val name: String, val nameEn: String,
    val nodes: String, val nodesEn: String,
    val use: String = "", val useEn: String = "",
    val axes: String = "", val axesEn: String = "",
    val center: String = "", val centerEn: String = "",
    val loop: Boolean = false
)

/**
 * 100 built-in scheme templates in 10 fields, each a best-known framework of its field in a modern look.
 * Pure, no Android.
 */
object SchemeTemplates {
    data class Area(val id: String, val name: String, val nameEn: String)

    val AREAS = listOf(
        Area("biz", "💼 Бизнес и стратегия", "💼 Business & strategy"),
        Area("mkt", "📣 Маркетинг и продажи", "📣 Marketing & sales"),
        Area("product", "🛠 Продукт и IT", "🛠 Product & IT"),
        Area("edu", "🎓 Обучение", "🎓 Education"),
        Area("sci", "🔬 Наука и техника", "🔬 Science & tech"),
        Area("money", "💰 Финансы", "💰 Money"),
        Area("health", "🏃 Здоровье и спорт", "🏃 Health & sport"),
        Area("mind", "🧠 Психология и рост", "🧠 Psychology & growth"),
        Area("story", "📜 История и новости", "📜 History & news"),
        Area("creator", "🎬 Блогинг и контент", "🎬 Creators & content")
    )

    private fun T(id: String, area: String, layout: String, look: String, name: String, nameEn: String, nodes: String, nodesEn: String,
                  use: String = "", useEn: String = "", axes: String = "", axesEn: String = "", center: String = "", centerEn: String = "",
                  loop: Boolean = false) = SchemeTpl(id, area, layout, look, name, nameEn, nodes, nodesEn, use, useEn, axes, axesEn, center, centerEn, loop)

    val ALL: List<SchemeTpl> = listOf(
        // ---------- business and strategy ----------
        T("swot", "biz", "matrix", "swiss", "SWOT-анализ", "SWOT analysis",
            "💪 Сильные стороны;⚠️ Слабые стороны;🚀 Возможности;🧨 Угрозы", "💪 Strengths;⚠️ Weaknesses;🚀 Opportunities;🧨 Threats",
            "оценка компании, проекта, себя", "a company, a project, yourself", "Помогает → мешает|Внутри → снаружи", "Helps → harms|Inside → outside"),
        T("eisenhower", "biz", "matrix", "bento", "Матрица Эйзенхауэра", "Eisenhower matrix",
            "🔥 Сделать сейчас;📅 Запланировать;🤝 Поручить;🗑 Убрать", "🔥 Do now;📅 Schedule;🤝 Delegate;🗑 Drop",
            "приоритеты и время", "priorities and time", "Срочно → не срочно|Важно → не важно", "Urgent → not urgent|Important → not"),
        T("bcg", "biz", "matrix", "finance", "Матрица BCG", "BCG matrix",
            "⭐ Звёзды;❓ Трудные дети;🐄 Дойные коровы;🐕 Собаки", "⭐ Stars;❓ Question marks;🐄 Cash cows;🐕 Dogs",
            "портфель товаров и направлений", "a portfolio of products", "Доля: выше → ниже|Рост: выше → ниже", "Share: high → low|Growth: high → low"),
        T("porter", "biz", "hub", "editorial", "5 сил Портера", "Porter's five forces",
            "⚔️ Конкуренция в отрасли;🆕 Новые игроки;🔁 Товары-замены;🛒 Сила покупателей;🏭 Сила поставщиков",
            "⚔️ Industry rivalry;🆕 New entrants;🔁 Substitutes;🛒 Buyer power;🏭 Supplier power",
            "насколько выгоден рынок", "how attractive a market is"),
        T("pest", "biz", "hub", "blueprint", "PEST-анализ", "PEST analysis",
            "🌍 Внешняя среда;🏛 Политика;💰 Экономика;👥 Общество;🔬 Технологии", "🌍 Environment;🏛 Politics;💰 Economy;👥 Society;🔬 Technology",
            "что вокруг влияет на дело", "what around affects the business"),
        T("okr", "biz", "tree", "bento", "Цели и ключевые результаты", "OKR",
            "🎯 Цель квартала;📈 Результат 1 — +20%;^✅ Задача;📊 Результат 2 — ×2;^✅ Задача;🧪 Результат 3",
            "🎯 Quarter goal;📈 Result 1 — +20%;^✅ Task;📊 Result 2 — ×2;^✅ Task;🧪 Result 3",
            "цель → измеримые результаты → задачи", "goal → measurable results → tasks"),
        T("seven_s", "biz", "hub", "ocean", "Модель 7S", "7S model",
            "💎 Общие ценности;🧭 Стратегия;🏗 Структура;⚙️ Системы;🎨 Стиль;👥 Персонал;🛠 Навыки",
            "💎 Shared values;🧭 Strategy;🏗 Structure;⚙️ Systems;🎨 Style;👥 Staff;🛠 Skills",
            "из чего состоит организация", "what an organisation is made of"),
        T("golden_circle", "biz", "pyramid", "luxury", "Золотой круг", "Golden circle",
            "*❤️ Зачем;🛠 Как;📦 Что", "*❤️ Why;🛠 How;📦 What",
            "смысл бренда и идеи", "the purpose of a brand or idea"),
        T("pdca", "biz", "chain", "mint", "Цикл PDCA", "PDCA cycle",
            "📝 Планируй;🔨 Делай;🔍 Проверяй;🔁 Улучшай", "📝 Plan;🔨 Do;🔍 Check;🔁 Act",
            "постоянные улучшения", "continuous improvement", loop = true),
        T("value_chain", "biz", "chain", "brutal", "Цепочка ценности", "Value chain",
            "📦 Закупка;🏭 Производство;🚚 Логистика;📣 Продажи;🛟 Сервис", "📦 Sourcing;🏭 Production;🚚 Logistics;📣 Sales;🛟 Service",
            "где компания создаёт ценность", "where a company creates value"),

        // ---------- marketing and sales ----------
        T("aida", "mkt", "funnel", "sunset", "Воронка AIDA", "AIDA funnel",
            "👀 Внимание;🤔 Интерес;😍 Желание;*🛒 Действие", "👀 Attention;🤔 Interest;😍 Desire;*🛒 Action",
            "реклама, продающий текст", "ads, sales copy"),
        T("aarrr", "mkt", "funnel", "finance", "Пиратские метрики AARRR", "AARRR pirate metrics",
            "🧲 Привлечение;✨ Активация;🔁 Удержание;💸 Доход;📣 Рекомендации", "🧲 Acquisition;✨ Activation;🔁 Retention;💸 Revenue;📣 Referral",
            "рост продукта по этапам", "product growth by stages"),
        T("four_p", "mkt", "hub", "bold", "Маркетинг-микс 4P", "Marketing mix 4P",
            "🎯 Маркетинг-микс;📦 Продукт;💲 Цена;📍 Место;📣 Продвижение", "🎯 Marketing mix;📦 Product;💲 Price;📍 Place;📣 Promotion",
            "из чего состоит предложение", "what an offer is made of"),
        T("stp", "mkt", "chain", "clean", "Сегментация → позиционирование", "STP",
            "🧩 Сегментация;🎯 Выбор цели;📌 Позиционирование", "🧩 Segmentation;🎯 Targeting;📌 Positioning",
            "кому и как продавать", "who to sell to and how"),
        T("cjm", "mkt", "timeline", "pastel", "Путь клиента", "Customer journey",
            "💡 Узнал;🔎 Сравнил;🛒 Купил;😊 Пользуется;📣 Советует", "💡 Aware;🔎 Compares;🛒 Buys;😊 Uses;📣 Recommends",
            "этапы отношений с клиентом", "stages of a customer relationship"),
        T("sales_funnel", "mkt", "funnel", "news", "Воронка продаж", "Sales funnel",
            "👥 Охват — 10 000;🖱 Переходы — 1 000;📝 Заявки — 100;*🤝 Сделки — 10", "👥 Reach — 10,000;🖱 Clicks — 1,000;📝 Leads — 100;*🤝 Deals — 10",
            "конверсия по шагам", "conversion step by step"),
        T("persona", "mkt", "hub", "pastel", "Портрет клиента", "Customer persona",
            "🙋 Клиент;🎂 Кто он;😣 Боли;🎯 Цели;📱 Где бывает;💬 Возражения", "🙋 Customer;🎂 Who;😣 Pains;🎯 Goals;📱 Where;💬 Objections",
            "для кого продукт", "who the product is for"),
        T("pas", "mkt", "chain", "bold", "Проблема → обострение → решение", "Problem → agitate → solve",
            "😣 Проблема;🔥 Обострение;*✅ Решение", "😣 Problem;🔥 Agitate;*✅ Solution",
            "убеждающий текст, реклама", "persuasive copy, ads"),
        T("positioning", "mkt", "matrix", "bento", "Карта позиционирования", "Positioning map",
            "💎 Премиум-классика;🚀 Инноваторы;🏷 Эконом;⚡ Разрушители", "💎 Premium classic;🚀 Innovators;🏷 Budget;⚡ Disruptors",
            "место среди конкурентов", "a place among competitors", "Классика → новизна|Дорого → дёшево", "Classic → new|Premium → budget"),
        T("jtbd", "mkt", "chain", "editorial", "Работа, которую нанимают", "Jobs to be done",
            "📍 Ситуация;💭 Мотивация;*🎯 Ожидаемый результат", "📍 Situation;💭 Motivation;*🎯 Expected outcome",
            "зачем на самом деле покупают", "why people really buy"),

        // ---------- product and IT ----------
        T("design_thinking", "product", "chain", "bento", "Дизайн-мышление", "Design thinking",
            "❤️ Эмпатия;🎯 Проблема;💡 Идеи;🛠 Прототип;🧪 Тест", "❤️ Empathise;🎯 Define;💡 Ideate;🛠 Prototype;🧪 Test",
            "решить задачу пользователя", "solve a user's problem"),
        T("scrum", "product", "chain", "ocean", "Цикл Scrum", "Scrum cycle",
            "📋 Бэклог;🗓 Планирование;🏃 Спринт;🎬 Демо;🔄 Ретро", "📋 Backlog;🗓 Planning;🏃 Sprint;🎬 Review;🔄 Retro",
            "работа команды итерациями", "team work in iterations", loop = true),
        T("build_measure", "product", "chain", "terminal", "Создай → измерь → узнай", "Build → measure → learn",
            "🛠 Создай;📏 Измерь;🧠 Узнай", "🛠 Build;📏 Measure;🧠 Learn",
            "запуск стартапа и MVP", "a startup and its MVP", loop = true),
        T("impact_effort", "product", "matrix", "bento", "Польза и усилия", "Impact vs effort",
            "🍒 Быстрые победы;🏔 Большие проекты;🧹 Мелочи;🕳 Пустая трата", "🍒 Quick wins;🏔 Big projects;🧹 Fill-ins;🕳 Time sinks",
            "что делать первым", "what to do first", "Усилия: мало → много|Польза: много → мало", "Effort: low → high|Value: high → low"),
        T("moscow", "product", "hub", "swiss", "Приоритеты MoSCoW", "MoSCoW priorities",
            "📋 Требования;✅ Обязательно;👍 Желательно;🙂 Можно;🚫 Не сейчас", "📋 Requirements;✅ Must;👍 Should;🙂 Could;🚫 Won't",
            "что войдёт в релиз", "what goes into a release"),
        T("layers", "product", "pyramid", "blueprint", "Слои приложения", "App layers",
            "🖥 Интерфейс;⚙️ Логика;🔌 API;🗄 Данные", "🖥 Interface;⚙️ Logic;🔌 API;🗄 Data",
            "как устроена программа", "how software is built"),
        T("user_flow", "product", "chain", "clean", "Путь пользователя", "User flow",
            "🚪 Вход;🔎 Поиск;📄 Карточка;🛒 Корзина;*💳 Оплата", "🚪 Sign in;🔎 Search;📄 Product page;🛒 Cart;*💳 Payment",
            "шаги в приложении или на сайте", "steps in an app or a site"),
        T("kano", "product", "tree", "mint", "Модель Кано", "Kano model",
            "🧩 Функции продукта;🔒 Базовые;📈 Чем больше, тем лучше;🤩 Восторг", "🧩 Product features;🔒 Basic;📈 More is better;🤩 Delighters",
            "какие функции радуют", "which features delight"),
        T("devops", "product", "chain", "terminal", "Цикл DevOps", "DevOps loop",
            "💻 Код;🧱 Сборка;🧪 Тесты;🚀 Релиз;📡 Мониторинг", "💻 Code;🧱 Build;🧪 Test;🚀 Release;📡 Monitor",
            "как выпускают программы", "how software ships", loop = true),
        T("ml_pipeline", "product", "chain", "aurora", "Как учат нейросеть", "ML pipeline",
            "📥 Данные;🧹 Очистка;🧠 Обучение;📏 Оценка;🚀 Запуск", "📥 Data;🧹 Cleaning;🧠 Training;📏 Evaluation;🚀 Deploy",
            "ИИ и машинное обучение", "AI and machine learning"),

        // ---------- education ----------
        T("bloom", "edu", "pyramid", "chalk", "Таксономия Блума", "Bloom's taxonomy",
            "🎨 Создавать;⚖️ Оценивать;🔬 Анализировать;🛠 Применять;💡 Понимать;🧠 Помнить",
            "🎨 Create;⚖️ Evaluate;🔬 Analyse;🛠 Apply;💡 Understand;🧠 Remember",
            "уровни понимания темы", "levels of learning"),
        T("kolb", "edu", "chain", "pastel", "Цикл Колба", "Kolb's cycle",
            "🧪 Опыт;🪞 Наблюдение;💡 Вывод;🛠 Проба", "🧪 Experience;🪞 Reflection;💡 Concept;🛠 Experiment",
            "как учатся на опыте", "learning by experience", loop = true),
        T("feynman", "edu", "chain", "chalk", "Метод Фейнмана", "Feynman technique",
            "📚 Выбери тему;🗣 Объясни просто;🕳 Найди пробелы;✂️ Упрости", "📚 Pick a topic;🗣 Explain simply;🕳 Find the gaps;✂️ Simplify",
            "понять сложное", "understand something hard"),
        T("mind_map", "edu", "hub", "kids", "Карта мыслей", "Mind map",
            "🧠 Тема;📌 Идея 1;📌 Идея 2;📌 Идея 3;📌 Идея 4", "🧠 Topic;📌 Idea 1;📌 Idea 2;📌 Idea 3;📌 Idea 4",
            "всё о теме вокруг центра", "everything around one topic"),
        T("compare", "edu", "venn", "clean", "Сравнение двух вещей", "Compare two things",
            "🅰️ Вариант А;🅱️ Вариант Б", "🅰️ Option A;🅱️ Option B",
            "чем похожи и чем отличаются", "how they are alike and differ", center = "Общее", centerEn = "Shared"),
        T("cause_effect", "edu", "chain", "editorial", "Причина и следствие", "Cause and effect",
            "❓ Причина;⚙️ Механизм;*💥 Следствие", "❓ Cause;⚙️ Mechanism;*💥 Effect",
            "почему так происходит", "why it happens"),
        T("mastery", "edu", "timeline", "kids", "Путь к мастерству", "Path to mastery",
            "🌱 Новичок;📘 Ученик;🛠 Практик;🏆 Мастер", "🌱 Novice;📘 Learner;🛠 Practitioner;🏆 Master",
            "этапы навыка", "stages of a skill"),
        T("dikw", "edu", "pyramid", "blueprint", "Пирамида знаний DIKW", "DIKW pyramid",
            "🦉 Мудрость;🧠 Знания;📄 Информация;🔢 Данные", "🦉 Wisdom;🧠 Knowledge;📄 Information;🔢 Data",
            "от фактов к пониманию", "from facts to wisdom"),
        T("five_w", "edu", "hub", "comic", "5W + H", "5W + H",
            "❓ Вопрос;👤 Кто;📦 Что;📍 Где;⏰ Когда;🤔 Почему;🛠 Как", "❓ Question;👤 Who;📦 What;📍 Where;⏰ When;🤔 Why;🛠 How",
            "разобрать любую тему", "break down any topic"),
        T("spaced", "edu", "timeline", "mint", "Интервальное повторение", "Spaced repetition",
            "📖 Урок — день 0;🔁 Повтор — день 1;🔁 Повтор — день 3;🔁 Повтор — день 7;🏁 Закреплено — день 30",
            "📖 Lesson — day 0;🔁 Review — day 1;🔁 Review — day 3;🔁 Review — day 7;🏁 Learned — day 30",
            "как запомнить надолго", "how to remember for long"),

        // ---------- science and tech ----------
        T("sci_method", "sci", "chain", "ocean", "Научный метод", "Scientific method",
            "👀 Наблюдение;❓ Вопрос;💡 Гипотеза;🧪 Эксперимент;📊 Вывод", "👀 Observe;❓ Question;💡 Hypothesis;🧪 Experiment;📊 Conclusion",
            "как проверяют идеи", "how ideas are tested"),
        T("water_cycle", "sci", "chain", "ocean", "Круговорот воды", "Water cycle",
            "☀️ Испарение;☁️ Конденсация;🌧 Осадки;🌊 Сток", "☀️ Evaporation;☁️ Condensation;🌧 Precipitation;🌊 Runoff",
            "природные циклы", "natural cycles", loop = true),
        T("food_chain", "sci", "pyramid", "mint", "Пищевая пирамида экосистемы", "Food pyramid of an ecosystem",
            "🦅 Хищники;🦊 Потребители 2;🐇 Потребители 1;🌿 Растения", "🦅 Predators;🦊 Consumers 2;🐇 Consumers 1;🌿 Plants",
            "кто кого ест, энергия по уровням", "who eats whom, energy by levels"),
        T("evolution", "sci", "timeline", "editorial", "Эволюция жизни", "Evolution of life",
            "🦠 Клетки — 3,5 млрд лет;🐟 Рыбы — 500 млн лет;🦖 Динозавры — 230 млн лет;🐒 Приматы — 60 млн лет;🧍 Человек — 300 тыс. лет",
            "🦠 Cells — 3.5 bn years;🐟 Fish — 500 m years;🦖 Dinosaurs — 230 m years;🐒 Primates — 60 m years;🧍 Humans — 300 k years",
            "большая история по векам", "big history by ages"),
        T("atom", "sci", "hub", "blueprint", "Строение атома", "Atom structure",
            "⚛️ Атом;➕ Протоны;⚪ Нейтроны;➖ Электроны", "⚛️ Atom;➕ Protons;⚪ Neutrons;➖ Electrons",
            "из чего состоит вещество", "what matter is made of"),
        T("system", "sci", "chain", "blueprint", "Как работает система", "How a system works",
            "📥 Вход;⚙️ Процесс;📤 Выход;🔁 Обратная связь", "📥 Input;⚙️ Process;📤 Output;🔁 Feedback",
            "любой механизм или процесс", "any mechanism or process", loop = true),
        T("taxonomy", "sci", "tree", "medical", "Классификация живого", "Kingdoms of life",
            "🧬 Живое;🌿 Растения;🍄 Грибы;🐾 Животные;^🐟 Позвоночные;^🐛 Беспозвоночные",
            "🧬 Life;🌿 Plants;🍄 Fungi;🐾 Animals;^🐟 Vertebrates;^🐛 Invertebrates",
            "деление на группы", "sorting into groups"),
        T("energy", "sci", "chain", "sunset", "Путь энергии", "Path of energy",
            "☀️ Солнце;🌿 Фотосинтез;🍎 Пища;🏃 Движение", "☀️ Sun;🌿 Photosynthesis;🍎 Food;🏃 Motion",
            "превращения энергии", "how energy changes form"),
        T("tech_risk", "sci", "matrix", "aurora", "Польза и риски технологии", "Benefits and risks of a technology",
            "✅ Польза сейчас;🔮 Польза потом;⚠️ Риск сейчас;🌀 Риск потом", "✅ Benefit now;🔮 Benefit later;⚠️ Risk now;🌀 Risk later",
            "взвесить новую технологию", "weigh a new technology", "Сейчас → потом|Польза → риск", "Now → later|Benefit → risk"),
        T("experiment", "sci", "venn", "medical", "Опыт и контроль", "Test and control",
            "🧪 Опытная группа;🧫 Контрольная группа", "🧪 Test group;🧫 Control group",
            "честное сравнение в эксперименте", "a fair comparison in an experiment", center = "Те же условия", centerEn = "Same conditions"),

        // ---------- money ----------
        T("budget", "money", "hub", "finance", "Бюджет 50/30/20", "50/30/20 budget",
            "💰 Доход;🏠 Нужды — 50%;🎉 Желания — 30%;🏦 Сбережения — 20%", "💰 Income;🏠 Needs — 50%;🎉 Wants — 30%;🏦 Savings — 20%",
            "как делить деньги", "how to split money"),
        T("invest_pyramid", "money", "pyramid", "finance", "Пирамида риска", "Risk pyramid",
            "🎰 Спекуляции;📈 Акции;🏢 Облигации;🏦 Подушка безопасности", "🎰 Speculation;📈 Stocks;🏢 Bonds;🏦 Emergency fund",
            "куда вкладывать по порядку", "where to invest, in order"),
        T("compound", "money", "timeline", "finance", "Сложный процент, 8% в год", "Compound interest, 8% a year",
            "🌱 Старт — 100 тыс.;📈 5 лет — 147 тыс.;🚀 10 лет — 216 тыс.;🏔 20 лет — 466 тыс.",
            "🌱 Start — 100k;📈 5 years — 147k;🚀 10 years — 216k;🏔 20 years — 466k",
            "рост денег во времени", "money growing over time"),
        T("cashflow", "money", "chain", "mint", "Денежный поток", "Cash flow",
            "💵 Доходы;➖ Расходы;💰 Остаток;*📈 Инвестиции", "💵 Income;➖ Expenses;💰 Left over;*📈 Investing",
            "куда уходят деньги", "where the money goes"),
        T("risk_return", "money", "matrix", "finance", "Риск и доходность", "Risk and return",
            "🛡 Надёжно, но мало;🦄 Редкая удача;🕳 Ловушка;🎲 Риск ради роста", "🛡 Safe but small;🦄 Rare luck;🕳 Trap;🎲 Risk for growth",
            "сравнить вложения", "compare investments", "Доход: мало → много|Риск: мало → много", "Return: low → high|Risk: low → high"),
        T("snowball", "money", "chain", "bold", "Снежный ком долгов", "Debt snowball",
            "📋 Список долгов;🔽 Самый маленький;✅ Закрыть;⛄ Платёж — на следующий", "📋 List the debts;🔽 The smallest;✅ Pay it off;⛄ Roll the payment",
            "как выбраться из долгов", "how to get out of debt"),
        T("income_streams", "money", "hub", "luxury", "Источники дохода", "Income streams",
            "💼 Доход;🧑‍💻 Работа;🏠 Аренда;📈 Дивиденды;🎨 Свои проекты", "💼 Income;🧑‍💻 Job;🏠 Rent;📈 Dividends;🎨 Side projects",
            "откуда приходят деньги", "where money comes from"),
        T("money_pyramid", "money", "pyramid", "luxury", "Финансовая пирамида целей", "Financial goals pyramid",
            "🏝 Свобода;📈 Инвестиции;🛡 Страховка;🏦 Подушка;🧾 Без долгов", "🏝 Freedom;📈 Investing;🛡 Insurance;🏦 Emergency fund;🧾 No debt",
            "шаги к финансовой свободе", "steps to financial freedom"),
        T("price", "money", "hub", "finance", "Из чего состоит цена", "What a price is made of",
            "🏷 Цена — 100%;🏭 Себестоимость — 40%;📦 Логистика — 15%;🧾 Налоги — 20%;💰 Прибыль — 25%",
            "🏷 Price — 100%;🏭 Cost — 40%;📦 Logistics — 15%;🧾 Taxes — 20%;💰 Profit — 25%",
            "разбор цены товара", "a price taken apart"),
        T("blockchain", "money", "chain", "aurora", "Как работает блокчейн", "How a blockchain works",
            "📝 Транзакция;📡 Сеть;⛏ Проверка;🧱 Блок;🔗 Цепочка", "📝 Transaction;📡 Network;⛏ Validation;🧱 Block;🔗 Chain",
            "крипта простыми словами", "crypto in plain words"),

        // ---------- health and sport ----------
        T("plate", "health", "hub", "mint", "Здоровая тарелка", "Healthy plate",
            "🍽 Тарелка;🥦 Овощи — 1/2;🍗 Белок — 1/4;🍚 Крупы — 1/4;💧 Вода", "🍽 Plate;🥦 Veggies — 1/2;🍗 Protein — 1/4;🍚 Grains — 1/4;💧 Water",
            "питание без подсчётов", "eating without counting"),
        T("workout", "health", "timeline", "sport", "План тренировки", "Workout plan",
            "🔥 Разминка — 10 мин;💪 Основная часть — 30 мин;🏃 Кардио — 15 мин;🧘 Заминка — 5 мин",
            "🔥 Warm-up — 10 min;💪 Main part — 30 min;🏃 Cardio — 15 min;🧘 Cool-down — 5 min",
            "структура занятия", "the structure of a session"),
        T("sleep", "health", "chain", "ocean", "Циклы сна", "Sleep cycle",
            "😴 Засыпание;🌙 Лёгкий сон;🌊 Глубокий сон;💭 Быстрый сон", "😴 Falling asleep;🌙 Light sleep;🌊 Deep sleep;💭 REM sleep",
            "что происходит ночью", "what happens at night", loop = true),
        T("habit_loop", "health", "chain", "pastel", "Петля привычки", "Habit loop",
            "🔔 Сигнал;🔁 Действие;🎁 Награда", "🔔 Cue;🔁 Routine;🎁 Reward",
            "как появляются привычки", "how habits form", loop = true),
        T("fitness_pyramid", "health", "pyramid", "sport", "Пирамида результата", "Fitness pyramid",
            "🏆 Результат;😴 Восстановление;🏋️ Тренировки;🥗 Питание;💧 Сон и вода", "🏆 Results;😴 Recovery;🏋️ Training;🥗 Nutrition;💧 Sleep and water",
            "что важнее в спорте", "what matters most in sport"),
        T("care_path", "health", "chain", "medical", "Путь к лечению", "Path of care",
            "🤒 Симптом;📞 Врач;🔬 Анализы;💊 Лечение;✅ Контроль", "🤒 Symptom;📞 Doctor;🔬 Tests;💊 Treatment;✅ Follow-up",
            "что делать, если болит", "what to do when it hurts"),
        T("wellbeing", "health", "venn", "pastel", "Три опоры здоровья", "Three pillars of health",
            "🏃 Тело;🧠 Разум;🤝 Связи", "🏃 Body;🧠 Mind;🤝 Connections",
            "баланс здоровья", "a balance of health", center = "Благополучие", centerEn = "Well-being"),
        T("progress", "health", "timeline", "sport", "Прогресс по времени", "Progress over time",
            "🗓 Неделя 1;📈 Месяц 1;💪 Месяц 3;🏆 Полгода", "🗓 Week 1;📈 Month 1;💪 Month 3;🏆 Six months",
            "чего ждать и когда", "what to expect and when"),
        T("first_aid", "health", "chain", "medical", "Первая помощь", "First aid",
            "⚠️ Безопасность;📞 Вызов 112;🫀 Помощь;👀 Наблюдение", "⚠️ Safety;📞 Call emergency;🫀 Help;👀 Watch",
            "порядок действий", "the order of actions"),
        T("macros", "health", "hub", "mint", "Энергия из еды", "Energy from food",
            "⚡ Энергия;🍗 Белки — 4 ккал/г;🥑 Жиры — 9 ккал/г;🍞 Углеводы — 4 ккал/г", "⚡ Energy;🍗 Protein — 4 kcal/g;🥑 Fat — 9 kcal/g;🍞 Carbs — 4 kcal/g",
            "калории по составу", "calories by nutrient"),

        // ---------- psychology and growth ----------
        T("maslow", "mind", "pyramid", "pastel", "Пирамида Маслоу", "Maslow's hierarchy",
            "🌟 Самореализация;🏅 Уважение;❤️ Любовь;🛡 Безопасность;🍞 Физиология", "🌟 Self-actualisation;🏅 Esteem;❤️ Belonging;🛡 Safety;🍞 Physiology",
            "потребности человека", "human needs"),
        T("ikigai", "mind", "venn", "sunset", "Икигай", "Ikigai",
            "❤️ Что люблю;💪 Что умею;💰 За что платят", "❤️ What I love;💪 What I'm good at;💰 What pays",
            "найти своё дело", "find your calling", center = "Икигай", centerEn = "Ikigai"),
        T("johari", "mind", "matrix", "aurora", "Окно Джохари", "Johari window",
            "🪟 Открытое;🙈 Слепое пятно;🎭 Скрытое;❓ Неизвестное", "🪟 Open;🙈 Blind spot;🎭 Hidden;❓ Unknown",
            "что о нас знают другие", "what others know about us", "Знаю я → не знаю|Видят → не видят", "I know → I don't|Others see → don't"),
        T("cbt", "mind", "chain", "mint", "Мысли, чувства, действия", "Thoughts, feelings, actions",
            "💭 Мысли;😟 Чувства;🏃 Действия", "💭 Thoughts;😟 Feelings;🏃 Actions",
            "как мысли влияют на поступки", "how thoughts drive actions", loop = true),
        T("smart", "mind", "hub", "bento", "SMART-цель", "SMART goal",
            "🎯 Цель;🔍 Конкретная;📏 Измеримая;✅ Достижимая;🧭 Значимая;⏰ Со сроком", "🎯 Goal;🔍 Specific;📏 Measurable;✅ Achievable;🧭 Relevant;⏰ Time-bound",
            "поставить цель правильно", "set a goal the right way"),
        T("zones", "mind", "chain", "sunset", "Зоны развития", "Zones of growth",
            "🛋 Комфорт;😨 Страх;📚 Обучение;*🚀 Рост", "🛋 Comfort;😨 Fear;📚 Learning;*🚀 Growth",
            "выход из зоны комфорта", "leaving the comfort zone"),
        T("life_wheel", "mind", "hub", "pastel", "Колесо баланса", "Wheel of life",
            "☸️ Баланс;💼 Работа;💰 Деньги;❤️ Отношения;🏃 Здоровье;🎨 Хобби;🌱 Развитие", "☸️ Balance;💼 Work;💰 Money;❤️ Relationships;🏃 Health;🎨 Hobbies;🌱 Growth",
            "сферы жизни", "areas of life"),
        T("decision", "mind", "tree", "editorial", "Дерево решений", "Decision tree",
            "🤔 Решение;✅ Да;^🎯 Итог А;❌ Нет;^🎯 Итог Б", "🤔 Decision;✅ Yes;^🎯 Outcome A;❌ No;^🎯 Outcome B",
            "выбор и его последствия", "a choice and its outcomes"),
        T("habit_curve", "mind", "timeline", "aurora", "Как рождается привычка", "How a habit is born",
            "🚀 Старт;😓 Спад мотивации;🔁 Повторение;🌳 Привычка — 66 дней", "🚀 Start;😓 Motivation dip;🔁 Repetition;🌳 Habit — 66 days",
            "почему бросают и как дойти", "why people quit and how to keep on"),
        T("mindset", "mind", "venn", "editorial", "Два типа мышления", "Two mindsets",
            "🧱 Фиксированное мышление;🌱 Мышление роста", "🧱 Fixed mindset;🌱 Growth mindset",
            "отношение к ошибкам", "how mistakes are seen", center = "Отношение к ошибкам", centerEn = "View of mistakes"),

        // ---------- history and news ----------
        T("hero_journey", "story", "timeline", "retro", "Путь героя", "Hero's journey",
            "🏡 Обычный мир;📯 Зов;🚪 Порог;⚔️ Испытания;🏆 Награда;🔙 Возвращение", "🏡 Ordinary world;📯 The call;🚪 Threshold;⚔️ Trials;🏆 Reward;🔙 Return",
            "сюжет истории или биографии", "the plot of a story or a life"),
        T("news_5w", "story", "hub", "news", "Новость в 5 вопросах", "News in 5 questions",
            "📰 Событие;👤 Кто;📦 Что;📍 Где;⏰ Когда;❓ Почему", "📰 Event;👤 Who;📦 What;📍 Where;⏰ When;❓ Why",
            "суть новости", "the core of a news story"),
        T("history_arc", "story", "timeline", "retro", "Ход событий", "Course of events",
            "📜 Начало;⚔️ Конфликт;🔄 Перелом;🕊 Итог", "📜 Beginning;⚔️ Conflict;🔄 Turning point;🕊 Outcome",
            "исторический сюжет", "a historical story"),
        T("investigation", "story", "chain", "editorial", "Расследование", "Investigation",
            "🧩 Улика;🔍 Версия;🧪 Проверка;🧾 Доказательство;*⚖️ Вывод", "🧩 Clue;🔍 Theory;🧪 Check;🧾 Proof;*⚖️ Verdict",
            "детектив, разбор фактов", "a mystery, fact checking"),
        T("cause_tree", "story", "tree", "news", "Причины события", "Causes of an event",
            "💥 Событие;📉 Причина 1;^🔎 Корень;📉 Причина 2;^🔎 Корень", "💥 Event;📉 Cause 1;^🔎 Root;📉 Cause 2;^🔎 Root",
            "почему это случилось", "why it happened"),
        T("before_after", "story", "chain", "brutal", "Было → стало", "Before → after",
            "⏪ Было;⚡ Что изменилось;*⏩ Стало", "⏪ Before;⚡ What changed;*⏩ After",
            "перемены, реформы, кейсы", "changes, reforms, cases"),
        T("five_whys", "story", "chain", "swiss", "5 почему", "5 whys",
            "❓ Почему 1;❓ Почему 2;❓ Почему 3;❓ Почему 4;*🎯 Корень проблемы", "❓ Why 1;❓ Why 2;❓ Why 3;❓ Why 4;*🎯 Root cause",
            "добраться до настоящей причины", "get to the real cause"),
        T("two_sides", "story", "venn", "news", "Две стороны", "Two sides",
            "🔵 Сторона А;🔴 Сторона Б", "🔵 Side A;🔴 Side B",
            "спор, конфликт, переговоры", "a dispute, a conflict, talks", center = "Общие интересы", centerEn = "Shared interests"),
        T("rise_fall", "story", "timeline", "retro", "Взлёт и падение", "Rise and fall",
            "📈 Подъём;👑 Расцвет;🧨 Кризис;🏚 Упадок", "📈 Rise;👑 Peak;🧨 Crisis;🏚 Decline",
            "история империи, компании, звезды", "the story of an empire, a company, a star"),
        T("three_acts", "story", "chain", "luxury", "Три акта", "Three acts",
            "🎬 Завязка;🔥 Конфликт;🏁 Развязка", "🎬 Setup;🔥 Confrontation;🏁 Resolution",
            "структура рассказа", "the shape of a story"),

        // ---------- creators and content ----------
        T("video_structure", "creator", "timeline", "sunset", "Структура ролика", "Video structure",
            "🪝 Хук — 0–3 с;📌 Обещание — 3–10 с;📚 Суть;🎁 Итог;📣 Призыв", "🪝 Hook — 0–3 s;📌 Promise — 3–10 s;📚 Core;🎁 Payoff;📣 Call to action",
            "как удержать зрителя", "how to keep the viewer"),
        T("hook", "creator", "chain", "bold", "Формула хука", "Hook formula",
            "😲 Удивить;❓ Интрига;*🎁 Польза", "😲 Surprise;❓ Curiosity;*🎁 Value",
            "первые секунды видео", "the first seconds of a video"),
        T("content_pyramid", "creator", "pyramid", "bento", "Пирамида контента", "Content pyramid",
            "🎬 Основной ролик;✂️ Короткие нарезки;📝 Посты;💬 Сторис", "🎬 Main video;✂️ Short clips;📝 Posts;💬 Stories",
            "один ролик → много форматов", "one video → many formats"),
        T("pillars", "creator", "hub", "sunset", "Рубрики канала", "Content pillars",
            "📺 Канал;🎓 Обучение;😂 Развлечение;🤝 Вовлечение;🛍 Продажи", "📺 Channel;🎓 Educate;😂 Entertain;🤝 Engage;🛍 Sell",
            "о чём снимать", "what to post about"),
        T("flywheel", "creator", "chain", "aurora", "Маховик роста", "Growth flywheel",
            "🎬 Ролик;👀 Просмотры;👥 Подписчики;💬 Отзывы;💡 Идеи", "🎬 Video;👀 Views;👥 Subscribers;💬 Feedback;💡 Ideas",
            "как канал растёт сам", "how a channel grows itself", loop = true),
        T("yt_funnel", "creator", "funnel", "finance", "Воронка ролика", "Video funnel",
            "🖼 Показы — 100%;🖱 Клики — 5%;⏱ Досмотры — 50%;*👍 Подписки — 2%", "🖼 Impressions — 100%;🖱 Clicks — 5%;⏱ Watched — 50%;*👍 Subscribed — 2%",
            "где теряются зрители", "where viewers drop off"),
        T("production", "creator", "chain", "clean", "От идеи до публикации", "From idea to upload",
            "💡 Идея;📝 Сценарий;🎥 Съёмка;✂️ Монтаж;🖼 Обложка;*🚀 Публикация", "💡 Idea;📝 Script;🎥 Shoot;✂️ Edit;🖼 Thumbnail;*🚀 Publish",
            "план производства ролика", "a video production plan"),
        T("collab", "creator", "venn", "pastel", "Коллаборация", "Collaboration",
            "🙋 Моя аудитория;👥 Аудитория партнёра", "🙋 My audience;👥 Partner's audience",
            "с кем снимать совместно", "who to make a video with", center = "Общий интерес", centerEn = "Shared interest"),
        T("idea_matrix", "creator", "matrix", "bento", "Оценка идей", "Idea matrix",
            "🏆 Хит;🎯 Ниша;🔥 Хайп;💤 Мимо", "🏆 Hit;🎯 Niche;🔥 Hype;💤 Miss",
            "какую идею снять", "which idea to shoot", "Спрос: выше → ниже|Рынок: пусто → тесно", "Demand: high → low|Market: empty → busy"),
        T("thumbnail", "creator", "hub", "bold", "Сильная обложка", "Strong thumbnail",
            "🖼 Обложка;😮 Эмоция;🔤 До 3 слов;🎨 Контраст;👀 Взгляд в кадр", "🖼 Thumbnail;😮 Emotion;🔤 Up to 3 words;🎨 Contrast;👀 Eye contact",
            "из чего кликабельная обложка", "what makes a thumbnail clickable")
    )

    private val byId: Map<String, SchemeTpl> by lazy { ALL.associateBy { it.id } }

    fun of(id: String): SchemeTpl? = byId[id]

    fun inArea(area: String): List<SchemeTpl> = ALL.filter { it.area == area }

    fun name(t: SchemeTpl, ru: Boolean): String = if (ru) t.name else t.nameEn

    fun use(t: SchemeTpl, ru: Boolean): String = if (ru) t.use else t.useEn

    fun areaName(id: String, ru: Boolean): String = AREAS.firstOrNull { it.id == id }?.let { if (ru) it.name else it.nameEn } ?: id

    fun search(q: String): List<SchemeTpl> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return ALL
        return ALL.filter { t ->
            listOf(t.name, t.nameEn, t.use, t.useEn, t.nodes, t.nodesEn, t.layout, t.id).any { it.lowercase().contains(s) }
        }
    }

    private fun isIcon(s: String) = s.isNotEmpty() && s.none { it.isLetterOrDigit() }

    /** The template as a scheme in the language of [ru]: placeholder nodes, the template name as the title. */
    fun scheme(t: SchemeTpl, ru: Boolean = true): SScheme {
        val raw = (if (ru) t.nodes else t.nodesEn).split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val nodes = ArrayList<SchNode>()
        var lastTop = ""
        raw.forEachIndexed { i, l0 ->
            var l = l0
            val child = l.startsWith("^")
            if (child) l = l.removePrefix("^").trim()
            val accent = l.startsWith("*")
            if (accent) l = l.removePrefix("*").trim()
            val first = l.substringBefore(' ')
            val icon = if (isIcon(first)) first else ""
            val rest = (if (icon.isNotEmpty()) l.removePrefix(first) else l).trim()
            val parts = rest.split(" — ", limit = 2)
            val id = "n${i + 1}"
            val parent = when {
                t.layout != "tree" || i == 0 -> ""
                child -> lastTop
                else -> "n1"
            }
            if (!child && i > 0) lastTop = id
            nodes += SchNode(id, icon, parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty(), parent = parent, accent = accent)
        }
        val links = if (t.loop && t.layout == "chain" && nodes.size > 2)
            nodes.zipWithNext { a, b -> SchLink(a.id, b.id) } + SchLink(nodes.last().id, nodes.first().id) else emptyList()
        val axes = (if (ru) t.axes else t.axesEn).split('|').map { it.trim() }.filter { it.isNotEmpty() }
        return StudioScheme.repair(SScheme(t.layout, name(t, ru).take(40), nodes, links, axes, if (ru) t.center else t.centerEn))
    }

    /** The template as the lines of «✍️ Схема своим текстом». */
    fun lines(t: SchemeTpl, ru: Boolean = true): String = StudioEdit.schemeLines(scheme(t, ru))

    /** A wish for the schemes of a whole video (Studio, Mix): use the template where it fits the speech. */
    fun studioWish(t: SchemeTpl, ru: Boolean = true): String {
        val s = scheme(t, ru)
        return "Где по смыслу подходит, используй готовый шаблон «${name(t, ru)}» (layout ${t.layout}: " +
            s.nodes.joinToString("; ") { n -> listOf(n.icon, n.label).filter { it.isNotEmpty() }.joinToString(" ") } +
            (if (s.axes.isNotEmpty()) "; оси: " + s.axes.joinToString(" / ") else "") + "), подписи — из сказанного."
    }

    /** The wish for the AI to fill the template with what is said (the layout is passed separately). */
    fun wish(t: SchemeTpl, ru: Boolean = true): String {
        val s = scheme(t, ru)
        return buildString {
            append("Используй готовый шаблон «").append(t.name).append("» (").append(t.nameEn).append("): ")
            append(s.nodes.joinToString("; ") { n -> listOf(n.icon, n.label).filter { it.isNotEmpty() }.joinToString(" ") })
            if (s.axes.isNotEmpty()) append(". Оси: ").append(s.axes.joinToString(" / "))
            if (s.center.isNotEmpty()) append(". Общее: ").append(s.center)
            append(". Сохрани структуру и число блоков, подписи и значения возьми из сказанного (не выдумывай), эмодзи можно поменять по смыслу.")
        }
    }
}
