/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.util.Base64
import android.view.ViewGroup
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * Telegram Mini Apps of the bots. The page is one HTML file written by AI; Telegram opens it only from an https
 * address, so Lumen publishes it to the user's GitHub Pages (or the user gives their own address).
 * Data comes back to the bot as a normal update (Telegram.WebApp.sendData), so the phone still needs no public address.
 */

/** AI for mini apps. */
object MiniAppAi {
    fun build(b: BotInfo, desc: String, onStep: (String) -> Unit): String {
        onStep(t("ИИ делает мини-апп…", "AI is making the mini app…"))
        val reply = BotAi.ask(
            "bot_miniapp",
            Prompts.fill("bot_miniapp", mapOf("botdesc" to b.description.ifBlank { b.name }, "desc" to desc, "lang" to BotAi.lang())),
            "bot:${b.id}"
        )
        return checked(MiniApp.extractHtml(reply))
    }

    fun edit(b: BotInfo, html: String, request: String, onStep: (String) -> Unit): String {
        onStep(t("ИИ меняет мини-апп…", "AI is changing the mini app…"))
        val reply = BotAi.ask("bot_miniapp_edit", Prompts.fill("bot_miniapp_edit", mapOf("html" to html, "request" to request, "lang" to BotAi.lang())), "bot:${b.id}")
        return checked(MiniApp.extractHtml(reply))
    }

    private fun checked(html: String): String {
        val bad = MiniApp.problems(html)
        if (bad.isNotEmpty()) throw IllegalStateException(t("ИИ вернул не HTML: ", "AI did not return HTML: ") + bad.joinToString())
        return html
    }

    /** The data format the page sends (its <!-- DATA: … --> comment). */
    fun dataFormat(html: String): String =
        Regex("<!--\\s*DATA:([\\s\\S]*?)-->").find(html)?.groupValues?.get(1)?.trim().orEmpty()

    /** Asks AI to add the opening button and the handling of the page's data to the bot code. */
    fun connect(b: BotInfo, html: String, onStep: (String) -> Unit): BotAi.Result {
        val format = dataFormat(html).ifBlank { t("смотри код мини-аппа: ", "see the mini app code: ") + html.take(3000) }
        val request = t(
            "Подключи мини-апп «${b.miniAppDesc.ifBlank { "мини-апп" }}». В /start и где уместно добавь keyboard-кнопку {text: '📱 …', app: true} " +
                "(только с неё мини-апп может прислать данные). Добавь bot.on('app', ctx => …): обработай ctx.appData, ответь пользователю и, если это заявка/заказ, notify() владельцу. " +
                "Формат данных: $format",
            "Connect the mini app \"${b.miniAppDesc.ifBlank { "mini app" }}\". In /start and where useful add a keyboard button {text: '📱 …', app: true} " +
                "(only from it can the mini app send data). Add bot.on('app', ctx => …): handle ctx.appData, answer the user and notify() the owner for orders/requests. " +
                "Data format: $format"
        )
        return BotAi.edit(Bots.code(b.id), request, "bot:${b.id}", onStep)
    }
}

/** Publishing to the user's GitHub Pages: repo "lumen-miniapps", folder per bot. */
object GhPages {
    private const val KEY = "github_pages"

    var repo: String
        get() = Lumen.prefs.getString("gh_pages_repo", null)?.takeIf { it.isNotBlank() } ?: "lumen-miniapps"
        set(v) = Lumen.prefs.edit().putString("gh_pages_repo", v.trim()).apply()

    var login: String
        get() = Lumen.prefs.getString("gh_login", "") ?: ""
        private set(v) = Lumen.prefs.edit().putString("gh_login", v).apply()

    fun token(): String? = KeyVault.get(KEY)

    const val TOKEN_PAGE = "https://github.com/settings/tokens/new?scopes=public_repo&description=Lumen%20Mini%20Apps"

    private class Resp(val code: Int, val body: String) {
        val json: JSONObject get() = try {
            JSONObject(body)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun api(token: String, method: String, path: String, body: JSONObject? = null): Resp {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "Lumen")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun fail(r: Resp, what: String): Nothing {
        val msg = r.json.optString("message").ifBlank { "HTTP ${r.code}" }
        throw IllegalStateException(when (r.code) {
            401 -> t("GitHub не принял токен", "GitHub rejected the token")
            403, 404 -> t("$what: у токена нет прав (нужен классический токен с правом public_repo) — $msg", "$what: the token lacks rights (a classic token with public_repo is needed) — $msg")
            else -> "$what: $msg"
        })
    }

    /** Checks the token and remembers the account name. */
    fun connect(token: String): String {
        val r = api(token.trim(), "GET", "/user")
        if (r.code != 200) fail(r, "GitHub")
        KeyVault.put(KEY, token.trim())
        login = r.json.optString("login")
        return login
    }

    fun disconnect() {
        KeyVault.put(KEY, "")
        login = ""
    }

    /** Uploads the page to <repo>/<botId>/index.html, turns Pages on; returns the page address. */
    fun publish(botId: String, html: String, onStep: (String) -> Unit): String {
        val token = token() ?: throw IllegalStateException(t("Сначала подключите GitHub", "Connect GitHub first"))
        val user = login.ifBlank { connect(token) }
        val r = repo
        var info = api(token, "GET", "/repos/$user/$r")
        if (info.code == 404) {
            onStep(t("Создаю репозиторий $r…", "Creating the repository $r…"))
            val made = api(token, "POST", "/user/repos", JSONObject().put("name", r).put("description", "Telegram mini apps made with Lumen")
                .put("auto_init", true).put("private", false).put("has_issues", false).put("has_wiki", false))
            if (made.code != 201) fail(made, t("Создание репозитория", "Creating the repository"))
            Thread.sleep(2500)
            info = api(token, "GET", "/repos/$user/$r")
        }
        if (info.code != 200) fail(info, t("Репозиторий", "Repository"))
        val branch = info.json.optString("default_branch").ifBlank { "main" }
        onStep(t("Загружаю страницу…", "Uploading the page…"))
        putFile(token, user, r, branch, ".nojekyll", ByteArray(0), "Lumen: Pages without Jekyll", onlyIfMissing = true)
        putFile(token, user, r, branch, "$botId/index.html", html.toByteArray(), "Lumen: mini app $botId")
        val pages = api(token, "GET", "/repos/$user/$r/pages")
        if (pages.code == 404) {
            onStep(t("Включаю GitHub Pages…", "Turning GitHub Pages on…"))
            val on = api(token, "POST", "/repos/$user/$r/pages", JSONObject().put("source", JSONObject().put("branch", branch).put("path", "/")))
            if (on.code != 201 && on.code != 409) fail(on, "GitHub Pages")
        }
        return MiniApp.pagesUrl(user, r, botId)
    }

    private fun putFile(token: String, user: String, r: String, branch: String, path: String, data: ByteArray, message: String, onlyIfMissing: Boolean = false) {
        val enc = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        val old = api(token, "GET", "/repos/$user/$r/contents/$enc?ref=$branch")
        if (old.code == 200 && onlyIfMissing) return
        val body = JSONObject().put("message", message).put("branch", branch).put("content", Base64.encodeToString(data, Base64.NO_WRAP))
        if (old.code == 200) body.put("sha", old.json.optString("sha"))
        val put = api(token, "PUT", "/repos/$user/$r/contents/$enc", body)
        if (put.code != 200 && put.code != 201) fail(put, t("Загрузка файла", "Uploading the file"))
    }
}

/** Shows the page in a WebView the way Telegram would: theme colours, MainButton, sendData → the test chat. */
object MiniAppPreview {
    private fun shim(dark: Boolean) = """
<script>
(function () {
  var dark = $dark;
  var p = dark
    ? { bg_color: '#1c1b1f', text_color: '#e6e1e5', hint_color: '#938f99', link_color: '#d0bcff', button_color: '#6750a4', button_text_color: '#ffffff', secondary_bg_color: '#2b2930' }
    : { bg_color: '#ffffff', text_color: '#1d1b20', hint_color: '#79747e', link_color: '#6750a4', button_color: '#6750a4', button_text_color: '#ffffff', secondary_bg_color: '#f3edf7' };
  var root = document.documentElement.style;
  Object.keys(p).forEach(function (k) { root.setProperty('--tg-theme-' + k.replace(/_/g, '-'), p[k]); });
  var w = window.Telegram && window.Telegram.WebApp;
  if (!w) return;
  try { w.themeParams = p; } catch (e) {}
  try { w.colorScheme = dark ? 'dark' : 'light'; } catch (e) {}
  w.sendData = function (d) { LumenPreview.sendData(String(d)); };
  w.close = function () { LumenPreview.close(); };
  w.showAlert = function (m, cb) { alert(m); if (cb) cb(); };
  w.showConfirm = function (m, cb) { var r = confirm(m); if (cb) cb(r); };
  w.showPopup = function (o, cb) { alert((o.title ? o.title + '\n' : '') + o.message); if (cb) cb('ok'); };
  var mb = w.MainButton, cbs = [], el = null, st = { text: 'OK', visible: false, active: true };
  function draw() {
    if (!el) return;
    el.textContent = st.text; el.style.display = st.visible ? 'block' : 'none'; el.style.opacity = st.active ? '1' : '.5';
  }
  function mk() {
    el = document.createElement('button');
    el.style.cssText = 'position:fixed;left:0;right:0;bottom:0;height:52px;border:0;font-size:16px;font-weight:600;z-index:2147483647;' +
      'background:' + p.button_color + ';color:' + p.button_text_color;
    el.onclick = function () { if (st.active) cbs.slice().forEach(function (f) { f(); }); };
    document.body.appendChild(el);
    document.body.style.paddingBottom = '64px';
    draw();
  }
  if (document.body) mk(); else document.addEventListener('DOMContentLoaded', mk);
  mb.setText = function (t) { st.text = String(t); draw(); return mb; };
  mb.show = function () { st.visible = true; draw(); return mb; };
  mb.hide = function () { st.visible = false; draw(); return mb; };
  mb.enable = function () { st.active = true; draw(); return mb; };
  mb.disable = function () { st.active = false; draw(); return mb; };
  mb.showProgress = function () { return mb; };
  mb.hideProgress = function () { return mb; };
  mb.onClick = function (f) { cbs.push(f); return mb; };
  mb.offClick = function (f) { cbs = cbs.filter(function (x) { return x !== f; }); return mb; };
  mb.setParams = function (o) {
    if (o.text !== undefined) st.text = String(o.text);
    if (o.is_visible !== undefined) st.visible = !!o.is_visible;
    if (o.is_active !== undefined) st.active = !!o.is_active;
    draw(); return mb;
  };
  var onEvent = w.onEvent;
  w.onEvent = function (name, f) { if (name === 'mainButtonClicked') cbs.push(f); else if (onEvent) onEvent.call(w, name, f); };
})();
</script>
"""

    /** The page with the preview helpers right after the Telegram script. */
    fun prepare(html: String, dark: Boolean): String {
        val page = MiniApp.ensureSdk(html)
        val m = Regex("(?i)<script[^>]*telegram-web-app\\.js[^>]*>\\s*</script>").find(page) ?: return shim(dark) + page
        return page.substring(0, m.range.last + 1) + shim(dark) + page.substring(m.range.last + 1)
    }

    private class Bridge(val a: Activity, val onData: (String) -> Unit, val onClose: () -> Unit) {
        @JavascriptInterface
        fun sendData(d: String) {
            a.runOnUiThread { onData(d) }
        }

        @JavascriptInterface
        fun close() {
            a.runOnUiThread { onClose() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(a: Activity, html: String, params: String, onData: (String) -> Unit) {
        val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val wv = WebView(a)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(Bridge(a, { data ->
            d.dismiss()
            onData(data)
        }, { d.dismiss() }), "LumenPreview")
        val base = "https://lumen.preview/" + if (params.isNotBlank()) "?d=" + URLEncoder.encode(params, "UTF-8").replace("+", "%20") else ""
        wv.loadDataWithBaseURL(base, prepare(html, a.isNight()), "text/html", "utf-8", null)
        d.setContentView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.setOnDismissListener { wv.destroy() }
        d.show()
    }
}
