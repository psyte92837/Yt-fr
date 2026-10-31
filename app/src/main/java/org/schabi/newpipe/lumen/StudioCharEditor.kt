/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** A character that talks, blinks and shows a mood in a loop: the live preview of the editor. */
class ToonPreview(ctx: Context) : View(ctx) {
    var char: SChar = SChar("?", "blob")
        set(v) {
            field = v
            invalidate()
        }
    var mood = "explain"
    private val art = StudioToonArt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = SystemClock.uptimeMillis()
        // says something for 2.4 s, listens for 1.2 s
        val talking = t % 3600 < 2400
        val mouth = if (talking) (0.15f + 0.85f * abs(sin(t / 110.0)).toFloat()) else 0f
        val pose = ToonPose(mouth = mouth, blink = StudioToon.blink(t, 5), mood = mood, talking = talking,
            look = 0.4f * sin(t / 1300.0).toFloat(), side = 1f, tMs = t, seed = 2, accent = StudioPrefs.accent)
        val h = height.toFloat()
        art.draw(canvas, char, width / 2f, h * 0.96f, h * 0.76f, pose)
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** Tapping the eyes and the mouth on the user's picture. */
private class FaceView(ctx: Context, val bmp: Bitmap, var im: ToonImage) : View(ctx) {
    /** 0 = left eye, 1 = right eye, 2 = mouth. */
    var step = 0
    var onChange: () -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private fun box(): RectF {
        val k = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * k
        val h = bmp.height * k
        return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val b = box()
        // checkerboard shows the transparent background
        val cell = 24f
        var y = b.top
        var row = 0
        while (y < b.bottom) {
            var x = b.left
            var col = row
            while (x < b.right) {
                paint.color = if (col % 2 == 0) 0xFFDDDDDD.toInt() else 0xFFBBBBBB.toInt()
                canvas.drawRect(x, y, minOf(x + cell, b.right), minOf(y + cell, b.bottom), paint)
                x += cell
                col++
            }
            y += cell
            row++
        }
        canvas.drawBitmap(bmp, null, b, paint)
        fun at(fx: Float, fy: Float, text: String, color: Int) {
            if (fx < 0f || fy < 0f) return
            val x = b.left + fx * b.width()
            val yy = b.top + fy * b.height()
            mark.color = color
            canvas.drawCircle(x, yy, 22f, mark)
            label.color = color
            canvas.drawText(text, x, yy - 30f, label)
        }
        at(im.eyeLx, im.eyeLy, "L", 0xFF2E7DFF.toInt())
        at(im.eyeRx, im.eyeRy, "R", 0xFF2E7DFF.toInt())
        if (im.hasMouth) {
            val x = b.left + im.mouthX * b.width()
            val yy = b.top + im.mouthY * b.height()
            val half = im.mouthW * b.width() / 2f
            mark.color = 0xFFE0436F.toInt()
            canvas.drawLine(x - half, yy, x + half, yy, mark)
            at(im.mouthX, im.mouthY, "", 0xFFE0436F.toInt())
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val b = box()
        val fx = ((e.x - b.left) / b.width()).coerceIn(0f, 1f)
        val fy = ((e.y - b.top) / b.height()).coerceIn(0f, 1f)
        im = when (step) {
            0 -> im.copy(eyeLx = fx, eyeLy = fy)
            1 -> im.copy(eyeRx = fx, eyeRy = fy)
            else -> im.copy(mouthX = fx, mouthY = fy)
        }
        step = (step + 1) % 3
        invalidate()
        onChange()
        return true
    }
}

/**
 * The character editor: ready looks, parts, colours, proportions, voice and persona, the user's own picture with
 * face points, and characters from an AI — the built-in one (by a description or a picture) or any other one
 * through the "lumen.char" protocol (copy the prompt, paste the answer).
 */
class StudioCharEditor(
    private val act: Activity,
    start: SChar,
    private val isNew: Boolean,
    private val lang: String,
    /** True when another character of the cast already has the name. */
    private val taken: (String) -> Boolean,
    /** Opens the picture picker; the callback gets the chosen picture. */
    private val pickImage: ((Uri) -> Unit) -> Unit,
    private val onSave: (SChar) -> Unit
) {
    private var ch = start
    private val col = Col(act, 20)
    private val preview = ToonPreview(act)
    private val nameE = edt(act, t("Имя (одно слово)", "Name (one word)"), start.name)
    private val personaE = edt(act, t("Характер и манера речи: «ворчливый, но добрый, шутит про еду»", "Persona and manner: \"grumpy but kind, jokes about food\""), start.persona, multiline = true)
    private val status = text(act, "", 13f, color = Palette.MUTED)
    private var busy = false
    private val charsDir by lazy { File(act.filesDir, "lumen/studio/chars").apply { mkdirs() } }

    fun show() {
        build()
        AlertDialog.Builder(act).setTitle(if (isNew) t("Новый персонаж", "New character") else "@" + ch.name)
            .setView(ScrollView(act).apply { addView(col.root) })
            .setPositiveButton("OK") { _, _ ->
                sync()
                val name = StudioToon.cleanName(nameE.text.toString()).ifEmpty { StudioToon.cleanName(StudioToon.lookLabel(ch.look)) }
                if (taken(name)) {
                    act.toast(t("Имя «$name» уже занято", "The name \"$name\" is taken"))
                    return@setPositiveButton
                }
                onSave(ch.copy(name = name))
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    /** Name and persona from the fields into [ch]. */
    private fun sync() {
        ch = ch.copy(
            name = StudioToon.cleanName(nameE.text.toString()).ifEmpty { ch.name },
            persona = personaE.text.toString().trim().replace(Regex("\\s+"), " ").take(300)
        )
    }

    private fun parts() = StudioParts.of(ch)

    private fun setParts(p: ToonParts) {
        ch = ch.copy(parts = StudioParts.normalize(p))
        preview.char = ch
    }

    private fun detach(v: View) = (v.parent as? ViewGroup)?.removeView(v)

    private fun label(s: String) = col.add(text(act, s, Type.LABEL, bold = true, color = Palette.MUTED), 12)

    private fun build() {
        col.root.removeAllViews()
        preview.char = ch
        detach(preview)
        col.add(preview, 0).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(150))
        col.add(choiceChips(act, StudioToon.MOODS.map { it to StudioToon.moodLabel(it) }, preview.mood) { preview.mood = it }, 4)
        detach(nameE)
        col.add(nameE, 8)
        detach(status)
        col.add(status, 4)
        label(t("Создать", "Create"))
        col.chips(listOf(
            chip(act, "🎲 " + t("Случайно", "Random")) {
                sync()
                ch = ch.copy(parts = StudioParts.random(), image = null)
                build()
            },
            chip(act, "✨ " + t("По описанию", "From a description")) { describe() },
            chip(act, "🖼 " + t("По картинке", "From a picture")) { fromPicture() },
            chip(act, "🧸 " + t("Картинка — сам герой", "Picture as the character")) { ownPicture() },
            chip(act, "🤖 " + t("Через другой ИИ", "With another AI")) { externalAi() },
            chip(act, "📥 " + t("Вставить код или ответ", "Paste code or answer")) { paste() },
            chip(act, "⧉ " + t("Копировать код", "Copy code")) { copyCode() },
            chip(act, "💾 " + t("В библиотеку", "To the library")) { saveToLibrary() }
        ), collapseAfter = 99)
        label(t("Основа", "Base"))
        col.add(choiceChips(act, StudioToon.LOOKS.map { it to StudioToon.emoji(it) + " " + StudioToon.lookLabel(it) },
            if (ch.parts == null && ch.image == null) ch.look else "") { l ->
            sync()
            val auto = nameE.text.isBlank() || StudioToon.LOOKS.any { StudioToon.lookLabel(it) == nameE.text.toString() }
            ch = ch.copy(look = l, parts = null, color = 0, image = null)
            if (auto) nameE.setText(StudioToon.lookLabel(l))
            build()
        }, 4)
        val im = ch.image
        if (im != null) imageSection(im) else partsSection()
        voiceSection()
        label(t("Характер", "Persona"))
        col.muted(t("AI-сценарист пишет реплики героя в этом характере.", "The AI script writer keeps the character's persona."))
        detach(personaE)
        col.add(personaE, 4)
    }

    private fun partsSection() {
        val p = parts()
        fun group(title: String, values: List<String>, sel: String, set: (ToonParts, String) -> ToonParts) {
            label(title)
            col.add(choiceChips(act, values.map { it to StudioParts.label(it) }, sel) { setParts(set(parts(), it)) }, 4)
        }
        group(t("Тело", "Body"), StudioParts.BODIES, p.body) { o, v -> o.copy(body = v) }
        group(t("Макушка: уши, рога, антенны", "Top: ears, horns, antennas"), StudioParts.TOPS, p.top) { o, v -> o.copy(top = v) }
        group(t("Волосы", "Hair"), StudioParts.HAIRS, p.hair) { o, v -> o.copy(hair = v) }
        group(t("Живот или одежда", "Belly or clothes"), StudioParts.FRONTS, p.front) { o, v -> o.copy(front = v) }
        group(t("Глаза", "Eyes"), StudioParts.EYES, p.eyes) { o, v -> o.copy(eyes = v) }
        group(t("Вокруг глаз", "Around the eyes"), StudioParts.EYE_MARKS, p.eyeMark) { o, v -> o.copy(eyeMark = v) }
        group(t("Нос", "Nose"), StudioParts.NOSES, p.nose) { o, v -> o.copy(nose = v) }
        group(t("Хвост", "Tail"), StudioParts.TAILS, p.tail) { o, v -> o.copy(tail = v) }
        label(t("Аксессуары (до ${StudioParts.MAX_ACC})", "Accessories (up to ${StudioParts.MAX_ACC})"))
        col.add(toggles(StudioParts.ACCS.map { it to StudioParts.label(it) }, { parts().acc.contains(it) }) { v ->
            val acc = parts().acc
            setParts(parts().copy(acc = if (v in acc) acc - v else acc + v))
        }, 4)
        col.add(toggles(listOf("muzzle" to t("мордочка", "muzzle"), "whiskers" to t("усы", "whiskers"), "cheeks" to t("румянец", "blush")), {
            val q = parts()
            when (it) {
                "muzzle" -> q.muzzle
                "whiskers" -> q.whiskers
                else -> q.cheeks
            }
        }) { v ->
            val q = parts()
            setParts(when (v) {
                "muzzle" -> q.copy(muzzle = !q.muzzle)
                "whiskers" -> q.copy(whiskers = !q.whiskers)
                else -> q.copy(cheeks = !q.cheeks)
            })
        }, 8)
        label(t("Цвет тела или кожи", "Body or skin colour"))
        col.add(swatches(p.main, false) { setParts(parts().copy(main = it)) }, 4)
        label(t("Одежда и кепка", "Clothes and cap"))
        col.add(swatches(p.clothes, false) { setParts(parts().copy(clothes = it)) }, 4)
        label(t("Волосы", "Hair"))
        col.add(swatches(p.hairColor, false) { setParts(parts().copy(hairColor = it)) }, 4)
        label(t("Руки", "Arms"))
        col.add(swatches(p.limbs, true) { setParts(parts().copy(limbs = it)) }, 4)
        label(t("Ноги", "Feet"))
        col.add(swatches(p.feet, true) { setParts(parts().copy(feet = it)) }, 4)
        label(t("Пропорции", "Proportions"))
        col.add(choiceChips(act, listOf(0.85f to t("узкий", "slim"), 1f to t("обычный", "normal"), 1.15f to t("широкий", "wide"), 1.25f to t("толстяк", "chubby")),
            nearest(p.width, listOf(0.85f, 1f, 1.15f, 1.25f))) { setParts(parts().copy(width = it)) }, 4)
        col.add(choiceChips(act, listOf(0.88f to t("низкий", "short"), 1f to t("средний", "medium"), 1.12f to t("высокий", "tall")),
            nearest(p.height, listOf(0.88f, 1f, 1.12f))) { setParts(parts().copy(height = it)) }, 4)
        col.add(choiceChips(act, listOf(0.8f to t("глаза S", "eyes S"), 1f to "M", 1.2f to "L", 1.4f to "XL"),
            nearest(p.eyeSize, listOf(0.8f, 1f, 1.2f, 1.4f))) { setParts(parts().copy(eyeSize = it)) }, 4)
    }

    private fun nearest(v: Float, opts: List<Float>) = opts.minByOrNull { abs(it - v) } ?: opts[0]

    /** Chips that switch on and off; [on] tells the state, [flip] changes it. */
    private fun toggles(opts: List<Pair<String, String>>, on: (String) -> Boolean, flip: (String) -> Unit): ViewGroup {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            box.removeAllViews()
            box.addView(chipRow(act, opts.map { (v, l) -> chip(act, (if (on(v)) "✓ " else "") + l, on(v)) { flip(v); fill() } }, collapseAfter = 99))
        }
        fill()
        return box
    }

    /** Colour swatches; [auto] adds the automatic choice (0). */
    private fun swatches(sel0: Int, auto: Boolean, pick: (Int) -> Unit): ViewGroup {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun fill(sel: Int) {
            box.removeAllViews()
            val chips = mutableListOf<View>()
            if (auto) chips += chip(act, t("авто", "auto"), sel == 0) { pick(0); fill(0) }
            StudioParts.COLORS.forEach { c ->
                chips += chip(act, "⬤", (sel or (0xFF shl 24)) == c && sel != 0) { pick(c); fill(c) }.apply { setTextColor(c) }
            }
            if (sel != 0 && StudioParts.COLORS.none { it == (sel or (0xFF shl 24)) }) {
                chips += chip(act, "⬤ " + StudioParts.hex(sel), true).apply { setTextColor(sel or (0xFF shl 24)) }
            }
            chips += chip(act, "#…") {
                act.prompt(t("Цвет", "Colour"), "#RRGGBB", if (sel != 0) StudioParts.hex(sel) else "") { s ->
                    val c = StudioParts.color(s)
                    if (c == null) act.toast(t("Нужен цвет вида #FF8800", "A colour like #FF8800")) else {
                        pick(c)
                        fill(c)
                    }
                }
            }
            box.addView(chipRow(act, chips, collapseAfter = 99))
        }
        fill(sel0)
        return box
    }

    private fun voiceSection() {
        label(t("Голос", "Voice"))
        val opts = listOf("" to t("Авто", "Auto"), "f" to t("♀ Женский", "♀ Female"), "m" to t("♂ Мужской", "♂ Male"))
        val sel = if (ch.voice in listOf("", "f", "m")) ch.voice else ""
        col.add(choiceChips(act, opts, sel) { ch = ch.copy(voice = it) }, 4)
        label(t("Тон", "Pitch"))
        val pitches: List<Pair<Int?, String>> = listOf<Pair<Int?, String>>(null to t("по виду (${StudioToon.pitch(ch.look)})", "by look (${StudioToon.pitch(ch.look)})")) +
            listOf(-25, -15, -5, 0, 10, 20, 30, 40).map { it to (if (it > 0) "+$it" else "$it") }
        col.add(choiceChips(act, pitches, ch.pitch?.let { p -> pitches.mapNotNull { it.first }.minByOrNull { abs(it - p) } }) { ch = ch.copy(pitch = it) }, 4)
        label(t("Темп относительно общего", "Rate against the common one"))
        col.add(choiceChips(act, listOf(-15 to "−15%", -8 to "−8%", 0 to "0", 8 to "+8%", 15 to "+15%", 25 to "+25%"),
            listOf(-15, -8, 0, 8, 15, 25).minByOrNull { abs(it - ch.rate) } ?: 0) { ch = ch.copy(rate = it) }, 4)
    }

    // ---------- own picture ----------

    private fun imageSection(im: ToonImage) {
        label(t("Своя картинка", "Own picture"))
        col.muted(if (im.hasEyes && im.hasMouth) t("Глаза и рот отмечены: герой моргает и говорит.", "Eyes and mouth are marked: the character blinks and talks.")
            else t("Отметьте глаза и рот — тогда герой будет моргать и открывать рот. Лучше всего PNG с прозрачным фоном, герой в полный рост или по пояс.",
                "Mark the eyes and the mouth so the character blinks and talks. Best: a PNG with a transparent background, full body or half."))
        col.chips(listOf(
            chip(act, "📍 " + t("Глаза и рот", "Eyes and mouth")) { facePoints(im) },
            chip(act, "✨ " + t("AI найдёт глаза и рот", "AI finds eyes and mouth")) { aiFace(im) },
            chip(act, "🪄 " + t("Убрать фон", "Remove background")) { removeBg(im) },
            chip(act, (if (im.flip) "✓ " else "") + t("Поворачивать к центру", "Turn to the middle"), im.flip) {
                ch = ch.copy(image = im.copy(flip = !im.flip))
                build()
            },
            chip(act, "✕ " + t("Вернуть рисованного", "Back to drawn")) {
                sync()
                ch = ch.copy(image = null)
                build()
            }
        ), collapseAfter = 99)
        col.muted(t("Код героя с картинкой переносит только точки лица и детали: на другом устройстве без картинки он будет рисованным.",
            "The code of a picture character carries only the face points and parts: without the picture it is drawn."))
    }

    private fun ownPicture() = pickImage { uri ->
        Thread {
            val res = runCatching { importPicture(uri) }
            act.runOnUiThread {
                res.onSuccess { im ->
                    sync()
                    ch = ch.copy(image = im)
                    build()
                    facePoints(im)
                }.onFailure { act.toast(t("Не удалось открыть картинку: ", "Could not open the picture: ") + (it.message ?: "")) }
            }
        }.start()
    }

    /** Copies the picture into the app (≤ 768 px, PNG keeps transparency). */
    private fun importPicture(uri: Uri): ToonImage {
        val bmp = decode(uri, 768) ?: throw IllegalStateException(t("неизвестный формат", "unknown format"))
        val f = File(charsDir, "char_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ToonImage(f.absolutePath)
    }

    private fun decode(uri: Uri, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > max * 2) sample *= 2
        val raw = act.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val k = minOf(1f, max.toFloat() / maxOf(raw.width, raw.height))
        return if (k < 1f) Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * k).toInt()), maxOf(1, (raw.height * k).toInt()), true) else raw
    }

    private fun facePoints(im0: ToonImage) {
        val bmp = BitmapFactory.decodeFile(im0.path) ?: return act.toast(t("Картинка не найдена", "Picture not found"))
        val hint = text(act, "", 14f)
        val view = FaceView(act, bmp, im0)
        fun say() {
            hint.text = when (view.step) {
                0 -> t("Тапните по центру ЛЕВОГО глаза (слева на картинке)", "Tap the centre of the LEFT eye (on the left of the picture)")
                1 -> t("Теперь по центру ПРАВОГО глаза", "Now the centre of the RIGHT eye")
                else -> t("Теперь по центру рта", "Now the centre of the mouth")
            }
        }
        view.onChange = { say() }
        say()
        val box = Col(act, 16)
        box.add(hint, 0)
        box.add(view, 8).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(320))
        box.add(text(act, t("Ширина рта", "Mouth width"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
        box.add(choiceChips(act, listOf(0.07f to t("узкий", "narrow"), 0.12f to t("средний", "medium"), 0.18f to t("широкий", "wide"), 0.26f to t("огромный", "huge")),
            nearest(im0.mouthW, listOf(0.07f, 0.12f, 0.18f, 0.26f))) { view.im = view.im.copy(mouthW = it); view.invalidate() }, 4)
        AlertDialog.Builder(act).setTitle(t("Глаза и рот", "Eyes and mouth"))
            .setView(ScrollView(act).apply { addView(box.root) })
            .setPositiveButton("OK") { _, _ -> applyFace(view.im, bmp) }
            .setNeutralButton(t("Без глаз", "No eyes")) { _, _ -> applyFace(view.im.copy(eyeLx = -1f, eyeLy = -1f, eyeRx = -1f, eyeRy = -1f), bmp) }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    /** Saves the face points with the eyelid colour taken above the eyes. */
    private fun applyFace(im: ToonImage, bmp: Bitmap) {
        var out = im
        if (im.hasEyes) {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val (lx, ly) = StudioCharImage.lidPoint(im)
            out = im.copy(lid = StudioCharImage.skinAt(px, bmp.width, bmp.height, lx, ly))
        }
        sync()
        ch = ch.copy(image = out)
        build()
    }

    private fun removeBg(im: ToonImage) {
        Thread {
            val n = runCatching {
                val bmp = BitmapFactory.decodeFile(im.path)?.copy(Bitmap.Config.ARGB_8888, true) ?: return@runCatching -1
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val cnt = StudioCharImage.removeBg(px, bmp.width, bmp.height)
                if (cnt > 0) {
                    bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    File(im.path).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    StudioToonImages.drop(im.path)
                }
                cnt
            }.getOrDefault(-1)
            act.runOnUiThread {
                act.toast(when {
                    n > 0 -> t("Фон убран", "Background removed")
                    n == 0 -> t("Фон не однотонный или уже прозрачный — уберите его в другом приложении", "The background is not plain or already transparent")
                    else -> t("Не удалось обработать картинку", "Could not process the picture")
                })
                preview.invalidate()
            }
        }.start()
    }

    // ---------- AI ----------

    private fun describe() = act.prompt(t("Опишите героя", "Describe the character"),
        t("Например: рыжий кот-учёный в очках, ворчливый", "E.g. a ginger cat scientist in glasses, grumpy"), "", multiline = true) { d ->
        if (d.isNotBlank()) runAi(d, null, false)
    }

    private fun fromPicture() = pickImage { uri ->
        Thread {
            val b64 = runCatching { base64(decode(uri, 512) ?: throw IllegalStateException()) }.getOrNull()
            act.runOnUiThread {
                if (b64 == null) act.toast(t("Не удалось открыть картинку", "Could not open the picture"))
                else runAi(t("повтори героя с картинки", "copy the character from the picture"), b64, false)
            }
        }.start()
    }

    private fun aiFace(im: ToonImage) {
        val bmp = BitmapFactory.decodeFile(im.path) ?: return
        // on a white background, so that a transparent one does not turn black for the model
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(0xFFFFFFFF.toInt()); drawBitmap(bmp, 0f, 0f, null) }
        runAi(t("найди глаза и рот на картинке", "find the eyes and the mouth on the picture"), base64(flat), true)
    }

    private fun base64(bmp: Bitmap): String {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** Asks the built-in AI for a "lumen.char" answer; [image] — a picture for models that see. */
    private fun runAi(desc: String, image: String?, face: Boolean) {
        if (busy) return
        sync()
        busy = true
        status.text = "⏳ " + t("AI создаёт персонажа…", "AI creates the character…")
        val base = ch
        Thread {
            val res = runCatching {
                val prov = AiTasks.requireProvider()
                val prompt = Prompts.fill("studio_char", mapOf(
                    "mode" to StudioCharProto.mode(image != null, face),
                    "desc" to desc.ifBlank { "—" },
                    "current" to StudioCharProto.encode(base),
                    "lang" to lang,
                    "schema" to StudioCharProto.schema()
                ))
                val r = Llm.complete(prov, prov.smartModel, "You design cartoon characters. Answer with JSON only.", prompt,
                    json = true, maxTokens = 2000, task = "studio_char", pid = "studio", image = image)
                StudioCharProto.parse(r.text, base) ?: throw LlmException(t("AI ответил не по протоколу. Откройте «Ответы AI».", "The AI answer does not follow the protocol. See \"AI answers\"."))
            }
            act.runOnUiThread {
                busy = false
                res.onSuccess { pr ->
                    ch = if (face) base.copy(image = pr.char.image) else pr.char.copy(image = base.image)
                    nameE.setText(ch.name)
                    personaE.setText(ch.persona)
                    status.text = if (pr.notes.isEmpty()) "✓ " + t("Готово", "Done") else "✓ " + t("Готово; пропущено: ", "Done; skipped: ") + pr.notes.joinToString(", ")
                    build()
                    if (face) {
                        val im = ch.image
                        if (im != null && pr.hasFace) {
                            val bmp = BitmapFactory.decodeFile(im.path)
                            if (bmp != null) applyFace(im, bmp)
                            facePoints(ch.image ?: im)
                        } else status.text = t("AI не нашёл глаза и рот — отметьте их сами", "The AI found no eyes and mouth — mark them yourself")
                    }
                }.onFailure { e ->
                    if (e is SendCancelled) {
                        status.text = ""
                        return@onFailure
                    }
                    status.text = "⚠ " + (e.message ?: e.javaClass.simpleName) + if (image != null)
                        t("\nМодель может не видеть картинки: выберите модель с поддержкой изображений или «🤖 Через другой ИИ».",
                            "\nThe model may not see pictures: choose a vision model or \"🤖 With another AI\".") else ""
                }
            }
        }.start()
    }

    /** A prompt for any other AI; the answer comes back through "📥 Paste". */
    private fun externalAi() {
        sync()
        val face = ch.image != null
        var withImage = face
        val descE = edt(act, t("Пожелания (необязательно)", "Wishes (optional)"), "", multiline = true)
        val box = Col(act, 20)
        box.muted(t("Скопируйте запрос в ChatGPT, Gemini, Claude или другой ИИ, приложите картинку, если нужно, а ответ вставьте сюда кнопкой «📥 Вставить». Формат ответа — протокол lumen.char.",
            "Copy the request to ChatGPT, Gemini, Claude or another AI, attach a picture if needed, then paste the answer with \"📥 Paste\". The answer format is the lumen.char protocol."))
        box.add(descE, 8)
        if (!face) box.check(t("Я приложу картинку героя", "I will attach a picture of the character"), withImage) { withImage = it }
        else box.muted(t("Приложите в том ИИ эту же картинку: он найдёт глаза и рот.", "Attach the same picture there: it will find the eyes and the mouth."))
        AlertDialog.Builder(act).setTitle(t("Персонаж через другой ИИ", "Character with another AI"))
            .setView(ScrollView(act).apply { addView(box.root) })
            .setPositiveButton(t("⧉ Копировать запрос", "⧉ Copy request")) { _, _ ->
                copy(StudioCharProto.externalPrompt(descE.text.toString(), withImage, face))
                act.toast(t("Запрос скопирован. Ответ вставьте кнопкой «📥 Вставить».", "Request copied. Paste the answer with \"📥 Paste\"."))
            }
            .setNeutralButton(t("Поделиться", "Share")) { _, _ ->
                act.shareText("Lumen character", StudioCharProto.externalPrompt(descE.text.toString(), withImage, face))
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    private fun paste() {
        val clip = runCatching {
            act.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString()
        }.getOrNull().orEmpty()
        act.prompt(t("Код или ответ ИИ", "Code or AI answer"), "{\"format\":\"lumen.char\", …}", if (clip.contains("{")) clip else "", multiline = true) { s ->
            sync()
            val pr = StudioCharProto.parse(s, ch)
            if (pr == null) {
                act.toast(t("Не нашёл JSON персонажа", "No character JSON found"))
                return@prompt
            }
            ch = pr.char
            nameE.setText(ch.name)
            personaE.setText(ch.persona)
            status.text = if (pr.notes.isEmpty()) "✓ " + t("Персонаж загружен", "Character loaded") else "✓ " + t("Загружен; пропущено: ", "Loaded; skipped: ") + pr.notes.joinToString(", ")
            build()
            val im = ch.image
            if (pr.hasFace && im != null) BitmapFactory.decodeFile(im.path)?.let { applyFace(im, it) }
        }
    }

    private fun copy(s: String) {
        act.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("lumen.char", s))
    }

    private fun copyCode() {
        sync()
        copy(StudioCharProto.encode(ch))
        act.toast(t("Код персонажа скопирован", "Character code copied"))
    }

    private fun saveToLibrary() {
        sync()
        StudioPrefs.library = StudioToon.libraryWith(StudioPrefs.library, ch)
        act.toast(t("«${ch.name}» сохранён в библиотеке", "\"${ch.name}\" saved to the library"))
    }
}

/** The library of characters: tap to put one into the cast, ✕ to delete it. */
fun Activity.charLibrary(onPick: (SChar) -> Unit) {
    val lib = StudioPrefs.library
    if (lib.isEmpty()) {
        toast(t("Библиотека пуста: в редакторе героя нажмите «💾 В библиотеку»", "The library is empty: press \"💾 To the library\" in the character editor"))
        return
    }
    val col = Col(this, 16)
    lateinit var dlg: AlertDialog
    fun fill() {
        col.root.removeAllViews()
        StudioPrefs.library.forEach { ch ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ToonPreview(this@charLibrary).apply { char = ch; mood = "happy" }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(TextView(this@charLibrary).apply {
                    text = "@${ch.name}\n${StudioToon.kindLabel(ch)}" + if (ch.persona.isNotBlank()) " · " + ch.persona.take(40) else ""
                    textSize = 14f
                    setTextColor(Palette.ON_SURFACE)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
                addView(btn(this@charLibrary, "✕", style = BtnStyle.TEXT) {
                    StudioPrefs.library = StudioPrefs.library.filter { it.name != ch.name }
                    if (StudioPrefs.library.isEmpty()) dlg.dismiss() else fill()
                }.apply { contentDescription = t("Удалить ${ch.name}", "Delete ${ch.name}") })
                setOnClickListener {
                    dlg.dismiss()
                    onPick(ch)
                }
            }
            col.add(line, 6)
        }
    }
    fill()
    dlg = AlertDialog.Builder(this).setTitle(t("📚 Библиотека персонажей", "📚 Character library"))
        .setView(ScrollView(this).apply { addView(col.root) })
        .setNegativeButton(t("Закрыть", "Close"), null).show()
}
