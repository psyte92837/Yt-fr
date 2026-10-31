/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONObject
import org.mozilla.javascript.Callable
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.LambdaFunction
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/*
 * Runs a bot's JavaScript in Rhino (already in the app through NewPipeExtractor).
 * Sandbox: safe standard objects only (no Packages/java), every Java class hidden, time limit per call.
 * No Android here: the engine is tested on the JVM (BotCoreTest).
 */

/** What the bot code can do outside the sandbox; the service talks to Telegram, the test chat to the screen. */
interface BotHost {
    fun send(chatId: Long, text: String, opts: String): Long
    fun edit(chatId: Long, messageId: Long, text: String, opts: String)
    fun answer(callbackId: String, text: String)
    fun photo(chatId: Long, url: String, caption: String, opts: String): Long
    fun ai(prompt: String, system: String, userId: Long): String
    fun dbGet(key: String): String?
    fun dbSet(key: String, json: String?)
    fun dbKeys(prefix: String): List<String>
    fun notify(text: String)
    fun log(text: String)

    /** Returns JSON {status, text}. */
    fun http(method: String, url: String, body: String, headers: String): String

    /** Returns a JSON array of videos. */
    fun youtube(query: String, n: Int): String

    /** Returns a JSON array of {id, name, username}. */
    fun users(): String

    /** Link to the bot's mini app with [params] (JSON) in "d"; empty when there is no mini app. */
    fun appUrl(params: String): String = ""
}

/** Does nothing: for checking code right after the AI wrote it. */
open class NoopBotHost : BotHost {
    override fun send(chatId: Long, text: String, opts: String) = 1L
    override fun edit(chatId: Long, messageId: Long, text: String, opts: String) = Unit
    override fun answer(callbackId: String, text: String) = Unit
    override fun photo(chatId: Long, url: String, caption: String, opts: String) = 1L
    override fun ai(prompt: String, system: String, userId: Long) = "(AI)"
    private val db = HashMap<String, String>()
    override fun dbGet(key: String) = db[key]
    override fun dbSet(key: String, json: String?) {
        if (json == null) db.remove(key) else db[key] = json
    }
    override fun dbKeys(prefix: String) = db.keys.filter { it.startsWith(prefix) }.sorted()
    override fun notify(text: String) = Unit
    override fun log(text: String) = Unit
    override fun http(method: String, url: String, body: String, headers: String) = JSONObject().put("status", 200).put("text", "{}").toString()
    override fun youtube(query: String, n: Int) = "[]"
    override fun users() = "[]"
}

class BotJsError(message: String, val line: Int = 0, val timeout: Boolean = false) : Exception(message) {
    /** "Ошибка (строка 12): …" for the journal and for the AI fixer. */
    val full: String get() = if (line > 0) "line $line: $message" else message.orEmpty()
}

private class BotTimeout : Error("time limit")

class BotRuntime(private val code: String, private val host: BotHost, private val limitMs: Long = 15_000) {
    class Meta(val commands: List<Pair<String, String>>, val schedules: List<String>, val handlers: Int)

    @Volatile private var deadline = Long.MAX_VALUE
    private var scope: ScriptableObject? = null

    var meta = Meta(emptyList(), emptyList(), 0)
        private set

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.setInterpretedMode(true)
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 5_000
            cx.maximumInterpreterStackDepth = 800
            cx.setClassShutter(ClassShutter { false })
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.currentTimeMillis() > deadline) throw BotTimeout()
        }
    }

    /** Loads the prelude and the bot code; returns its commands and schedules. */
    fun load(): Meta = run { cx ->
        val s = cx.initSafeStandardObjects()
        install(s)
        cx.evaluateString(s, PRELUDE, "lumen", 1, null)
        scope = s
        cx.evaluateString(s, code, "bot.js", 1, null)
        val m = JSONObject(Context.toString(ScriptableObject.callMethod(s, "__meta", emptyArray())))
        val cmds = m.optJSONArray("commands")
        val sch = m.optJSONArray("schedules")
        meta = Meta(
            (0 until (cmds?.length() ?: 0)).map { i -> cmds!!.getJSONObject(i).let { it.optString("name") to it.optString("desc") } },
            (0 until (sch?.length() ?: 0)).map { sch!!.optString(it) },
            m.optInt("handlers")
        )
        meta
    }

    /** Routes one update to the bot's handlers; returns what handled it ("command:start", "hears", "action", "on", "none"). */
    fun handle(u: TgUpdate): String = run {
        Context.toString(ScriptableObject.callMethod(scope ?: error("not loaded"), "__dispatch", arrayOf(u.toJson().toString())))
    }

    fun runSchedule(index: Int) {
        run { ScriptableObject.callMethod(scope ?: error("not loaded"), "__runSched", arrayOf<Any>(index)) }
    }

    private fun <T> run(block: (Context) -> T): T {
        deadline = System.currentTimeMillis() + limitMs
        val cx = factory.enterContext()
        try {
            return block(cx)
        } catch (e: BotTimeout) {
            throw BotJsError("time limit ${limitMs / 1000} s exceeded (endless loop?)", 0, true)
        } catch (e: RhinoException) {
            throw BotJsError(e.details() ?: e.message ?: "error", lineOf(e))
        } catch (e: StackOverflowError) {
            throw BotJsError("too deep recursion")
        } finally {
            Context.exit()
            deadline = Long.MAX_VALUE
        }
    }

    private fun lineOf(e: RhinoException): Int {
        if (e.sourceName() == "bot.js" && e.lineNumber() > 0) return e.lineNumber()
        return Regex("""bot\.js:(\d+)""").find(e.scriptStackTrace.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun install(s: ScriptableObject) {
        fun def(name: String, f: (Array<Any?>) -> Any?) {
            val fn = LambdaFunction(s, name, 0, Callable { _, _, _, args ->
                // time spent outside JS (AI, network) does not count against the limit
                deadline = Long.MAX_VALUE
                try {
                    f(arrayOf<Any?>(*args))
                } catch (e: RhinoException) {
                    throw e
                } catch (e: BotTimeout) {
                    throw e
                } catch (e: Exception) {
                    throw ScriptRuntime.constructError("Error", e.message ?: e.javaClass.simpleName)
                } finally {
                    deadline = System.currentTimeMillis() + limitMs
                }
            })
            ScriptableObject.defineProperty(s, name, fn, ScriptableObject.DONTENUM or ScriptableObject.READONLY)
        }
        def("__send") { a -> host.send(long(a, 0), str(a, 1), str(a, 2)).toDouble() }
        def("__edit") { a -> host.edit(long(a, 0), long(a, 1), str(a, 2), str(a, 3)); null }
        def("__answer") { a -> host.answer(str(a, 0), str(a, 1)); null }
        def("__photo") { a -> host.photo(long(a, 0), str(a, 1), str(a, 2), str(a, 3)).toDouble() }
        def("__ai") { a -> host.ai(str(a, 0), str(a, 1), long(a, 2)) }
        def("__dbGet") { a -> host.dbGet(str(a, 0)) }
        def("__dbSet") { a -> host.dbSet(str(a, 0), a.getOrNull(1)?.takeIf { it !is Undefined }?.let { Context.toString(it) }); null }
        def("__dbKeys") { a -> org.json.JSONArray(host.dbKeys(str(a, 0))).toString() }
        def("__notify") { a -> host.notify(str(a, 0)); null }
        def("__log") { a -> host.log(str(a, 0)); null }
        def("__http") { a -> host.http(str(a, 0), str(a, 1), str(a, 2), str(a, 3)) }
        def("__youtube") { a -> host.youtube(str(a, 0), long(a, 1).toInt().coerceIn(1, 20)) }
        def("__users") { _ -> host.users() }
        def("__appUrl") { a -> host.appUrl(str(a, 0)) }
    }

    private fun str(a: Array<Any?>, i: Int): String {
        val v = a.getOrNull(i)
        return if (v == null || v is Undefined) "" else Context.toString(v)
    }

    private fun long(a: Array<Any?>, i: Int): Long {
        val v = a.getOrNull(i)
        if (v == null || v is Undefined) return 0
        return Context.toString(v).trim().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() ?: 0 }
    }

    companion object {
        /**
         * Checks freshly written code: syntax, load, at least one handler, /start runs.
         * Returns null when fine, else a message for the AI fixer.
         */
        fun check(code: String): String? {
            val bad = BotCode.unsupported(code)
            if (bad.isNotEmpty()) return "Unsupported syntax for this engine: ${bad.joinToString()}. Rewrite without it (all calls are synchronous)."
            val rt = BotRuntime(code, NoopBotHost(), 5_000)
            return try {
                val m = rt.load()
                if (m.handlers == 0) return "The code registers no handlers (bot.start/command/hears/on/action)."
                val me = TgUser(1, "Test", "test")
                rt.handle(TgUpdate(1, "message", 1, "private", me, "/start", 1, command = "start"))
                rt.handle(TgUpdate(2, "message", 1, "private", me, "hello", 2))
                null
            } catch (e: BotJsError) {
                e.full
            }
        }

        private val PRELUDE = """
(function (g) {
  var H = { cmds: [], hears: [], on: {}, actions: [], scheds: [], onError: null };
  function o2s(o) { return JSON.stringify(o || {}); }
  function str(x) {
    if (x === undefined || x === null) return '';
    if (typeof x === 'string') return x;
    if (typeof x === 'object') { try { return JSON.stringify(x); } catch (e) { return String(x); } }
    return String(x);
  }
  var bot = {};
  bot.command = function (name, desc, fn) {
    if (typeof desc === 'function') { fn = desc; desc = ''; }
    var names = Array.isArray(name) ? name : [name];
    names.forEach(function (n) { H.cmds.push({ name: String(n).replace(/^\//, '').toLowerCase(), desc: str(desc), fn: fn }); });
    return bot;
  };
  bot.start = function (fn) { return bot.command('start', '', fn); };
  bot.help = function (fn) { return bot.command('help', '', fn); };
  bot.hears = function (p, fn) { H.hears.push({ p: p, fn: fn }); return bot; };
  bot.on = function (type, fn) {
    var types = Array.isArray(type) ? type : [type];
    types.forEach(function (t) { (H.on[t] = H.on[t] || []).push(fn); });
    return bot;
  };
  bot.action = function (p, fn) { H.actions.push({ p: p, fn: fn }); return bot; };
  bot['catch'] = function (fn) { H.onError = fn; return bot; };
  bot.launch = function () { return bot; };
  g.bot = bot;
  g.schedule = function (spec, fn) { H.scheds.push({ spec: String(spec), fn: fn }); };
  g.send = function (chatId, text, opts) { return __send(String(chatId), str(text), o2s(opts)); };
  g.ai = function (prompt, opts) { opts = opts || {}; return String(__ai(str(prompt), str(opts.system), String(opts.user || 0))); };
  g.db = {
    get: function (k, d) { var v = __dbGet(String(k)); if (v === null || v === undefined) return d === undefined ? null : d; return JSON.parse(String(v)); },
    set: function (k, v) { __dbSet(String(k), v === undefined ? null : JSON.stringify(v)); return v; },
    del: function (k) { __dbSet(String(k), null); },
    keys: function (p) { return JSON.parse(String(__dbKeys(str(p)))); }
  };
  function resp(s) {
    var r = JSON.parse(String(s));
    return { status: r.status, ok: r.status >= 200 && r.status < 300, text: r.text, json: function () { return JSON.parse(r.text); } };
  }
  g.http = {
    get: function (url, o) { o = o || {}; return resp(__http('GET', str(url), '', o2s(o.headers))); },
    post: function (url, body, o) { o = o || {}; return resp(__http('POST', str(url), typeof body === 'string' ? body : JSON.stringify(body), o2s(o.headers))); }
  };
  g.notify = function (t) { __notify(str(t)); };
  g.log = function () { __log(Array.prototype.slice.call(arguments).map(str).join(' ')); };
  g.console = { log: g.log, info: g.log, warn: g.log, error: g.log };
  g.users = function () { return JSON.parse(String(__users())); };
  g.youtube = function (q, n) { return JSON.parse(String(__youtube(str(q), String(n || 5)))); };
  g.appUrl = function (data) { return String(__appUrl(data === undefined ? '' : JSON.stringify(data))); };

  function match(p, s) {
    s = s === undefined || s === null ? '' : String(s);
    if (p instanceof RegExp) { p.lastIndex = 0; return p.exec(s); }
    if (typeof p === 'function') return p(s) ? [s] : null;
    if (Array.isArray(p)) { for (var i = 0; i < p.length; i++) { var m = match(p[i], s); if (m) return m; } return null; }
    return String(p).toLowerCase() === s.trim().toLowerCase() ? [s] : null;
  }
  function makeCtx(u) {
    var user = u.user;
    var c = {
      update: u, kind: u.kind, chatId: u.chatId, text: u.text, data: u.data, command: u.command, args: u.args,
      messageId: u.messageId, user: user, from: user, chat: { id: u.chatId, type: u.chatType }, match: null, photo: u.photo, answered: false
    };
    c.appData = null;
    if (u.appData) { try { c.appData = JSON.parse(u.appData); } catch (e) { c.appData = u.appData; } }
    c.message = { text: u.text, message_id: u.messageId, chat: c.chat, from: user };
    if (u.appData) c.message.web_app_data = { data: u.appData };
    c.webAppData = c.appData;
    if (u.kind === 'callback') c.callbackQuery = { data: u.data, id: u.callbackId, message: c.message, from: user };
    c.reply = function (t, o) { return send(u.chatId, t, o); };
    c.replyWithHTML = function (t, o) { o = o || {}; o.html = true; return c.reply(t, o); };
    c.replyPhoto = function (url, caption, o) { return __photo(String(u.chatId), str(url), str(caption), o2s(o)); };
    c.replyWithPhoto = function (url, o) {
      if (url && typeof url === 'object') url = url.url || url.source;
      o = o || {};
      return c.replyPhoto(url, o.caption, o);
    };
    c.edit = function (t, o) { __edit(String(u.chatId), String(u.messageId), str(t), o2s(o)); };
    c.editMessageText = c.edit;
    c.answer = function (t) { c.answered = true; if (u.callbackId) __answer(u.callbackId, str(t)); };
    c.answerCbQuery = c.answer;
    c.ai = function (p, o) { o = o || {}; o.user = user.id; return ai(p, o); };
    var pre = 'u:' + user.id + ':';
    c.store = {
      get: function (k, d) { return db.get(pre + k, d); },
      set: function (k, v) { return db.set(pre + k, v); },
      del: function (k) { db.del(pre + k); }
    };
    return c;
  }
  g.__dispatch = function (json) {
    var u = JSON.parse(json);
    var c = makeCtx(u);
    // without bot.catch the error goes up untouched (a rethrow would lose the line in bot.js)
    if (!H.onError) return route(u, c);
    try {
      return route(u, c);
    } catch (e) {
      H.onError(e, c);
      return 'error';
    }
  };
  function route(u, c) {
      if (u.appData) {
        var apps = (H.on.app || []).concat(H.on.web_app_data || []);
        apps.forEach(function (fn) { fn(c); });
        if (apps.length) return 'app';
      }
      if (u.kind === 'callback') {
        for (var i = 0; i < H.actions.length; i++) {
          var m = match(H.actions[i].p, u.data);
          if (m) { c.match = m; H.actions[i].fn(c); if (!c.answered) c.answer(''); return 'action'; }
        }
        c.answer('');
        // a plain button ("Да") works like the same text typed by the user
        c.text = u.data;
        if (c.text.charAt(0) === '/') {
          var head = c.text.slice(1).split(/\s/)[0];
          c.command = head.split('@')[0].toLowerCase();
          c.args = c.text.slice(1 + head.length).trim();
        }
      }
      if (c.command) {
        for (var j = 0; j < H.cmds.length; j++) {
          if (H.cmds[j].name === c.command) { H.cmds[j].fn(c); return 'command:' + c.command; }
        }
      }
      for (var k = 0; k < H.hears.length; k++) {
        var hm = match(H.hears[k].p, c.text);
        if (hm) { c.match = hm; H.hears[k].fn(c); return 'hears'; }
      }
      var list = [];
      if (u.photo && H.on.photo) list = list.concat(H.on.photo);
      if (c.text && H.on.text) list = list.concat(H.on.text);
      if (H.on.message) list = list.concat(H.on.message);
      list.forEach(function (fn) { fn(c); });
      return list.length ? 'on' : 'none';
  }
  g.__meta = function () {
    return JSON.stringify({
      commands: H.cmds.map(function (c) { return { name: c.name, desc: c.desc }; }),
      schedules: H.scheds.map(function (s) { return s.spec; }),
      handlers: H.cmds.length + H.hears.length + H.actions.length + Object.keys(H.on).length + H.scheds.length
    });
  };
  g.__runSched = function (i) { H.scheds[i].fn(); };
})(this);
"""
    }
}
