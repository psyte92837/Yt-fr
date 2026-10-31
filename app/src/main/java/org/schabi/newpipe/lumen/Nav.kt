package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** A live screen as the planner sees it ([order] grows with every resume, so it follows the back stack). */
data class NavScreen(val cls: String, val key: String, val task: Int, val order: Long, val inst: Int)

/** What to do with a tap on a notification (or an "open" from another task). */
sealed class NavPlan {
    /** The screen is already on top of its task: just bring the task forward. */
    data class Front(val task: Int) : NavPlan()
    /** The screen is deeper in its task: move it to the top (the screens above stay). */
    data class Reorder(val task: Int, val above: List<Int>) : NavPlan()
    /** Not open: put it on top of the task where it belongs. */
    data class Push(val task: Int) : NavPlan()
    /** No task yet: start the task's root screen, then this one over it (Back leads home). */
    object Fresh : NavPlan()
}

/** Screens per task, notification taps that bring back the screen they belong to (see HANDOFF, navigation). */
object Nav {
    const val LUMEN = "org.schabi.newpipe.lumen."
    val HOME = LUMEN + "LumenActivity"
    val BOTS_HOME = LUMEN + "BotsActivity"
    private val BOTS = setOf(BOTS_HOME, LUMEN + "BotActivity", LUMEN + "BotNewActivity")
    /** Screens that open over whatever the user looks at (not tied to one task). */
    private val FLOATING = setOf(LUMEN + "SendReviewActivity")
    /** Screens with tasks of their own (documents, share routers): never a home task. */
    private val OWN_TASK = setOf(LUMEN + "HarvestActivity", LUMEN + "MediaActivity", LUMEN + "LinkActivity",
        LUMEN + "NavRouter", LUMEN + "TtsShareListen", LUMEN + "TtsShareSave", "org.schabi.newpipe.RouterActivity")
    const val EXTRA_INST = "lumen.nav.inst"
    val NO_KEY = key(null, null, null)
    private const val EXTRA_TARGET = "lumen.nav.target"

    // ---------- pure logic ----------

    /** Identity of a screen inside its class: data uri + id / pid extras. */
    fun key(data: String?, id: String?, pid: String?): String = listOf(data.orEmpty(), id.orEmpty(), pid.orEmpty()).joinToString("|")

    /** Root screen of the task [cls] belongs to (null for floating screens). */
    fun homeOf(cls: String): String? = when (cls) {
        in BOTS -> BOTS_HOME
        in FLOATING -> null
        else -> HOME
    }

    /** Home of a task by its root screen (null: a task of another kind). */
    fun taskHome(root: String): String? = when {
        root in BOTS -> BOTS_HOME
        root in OWN_TASK -> null
        root.startsWith("org.schabi.newpipe.") -> HOME
        else -> null
    }

    /**
     * Where a tap for screen [cls]/[key] lands. [inst] (non-zero) names the exact instance;
     * [tasks] maps a running task id to its root screen class; [lastTask] is the task used last.
     */
    fun plan(screens: List<NavScreen>, cls: String, key: String, inst: Int, tasks: Map<Int, String>, lastTask: Int?): NavPlan {
        val match = screens.firstOrNull { inst != 0 && it.inst == inst }
            ?: screens.filter { it.cls == cls && it.key == key }.maxByOrNull { it.order }
        val home = homeOf(cls)
        // the task's own root: open it as it was left (like the launcher icon), never pull it over its screens
        val root = cls == home && key == NO_KEY
        if (match != null) {
            if (root) return NavPlan.Front(match.task)
            val above = screens.filter { it.task == match.task && it.order > match.order }
            return if (above.isEmpty()) NavPlan.Front(match.task) else NavPlan.Reorder(match.task, above.map { it.inst })
        }
        val task = if (home == null) lastTask?.takeIf { it in tasks } ?: tasks.entries.firstOrNull { taskHome(it.value) == HOME }?.key
        else tasks.entries.firstOrNull { taskHome(it.value) == home }?.key
        return when {
            task == null -> NavPlan.Fresh
            root -> NavPlan.Front(task)
            else -> NavPlan.Push(task)
        }
    }

    // ---------- live screens ----------

    private class Live(val ref: WeakReference<Activity>, @Volatile var order: Long)
    private val live = CopyOnWriteArrayList<Live>()
    private val tick = AtomicLong(0)
    private var hooked = false

    fun hook(app: Context) {
        if (hooked) return
        val a = app as? Application ?: return
        hooked = true
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is NavRouter) live.add(Live(WeakReference(activity), tick.incrementAndGet()))
            }
            override fun onActivityResumed(activity: Activity) { find(activity)?.order = tick.incrementAndGet() }
            override fun onActivityDestroyed(activity: Activity) { live.removeAll { it.ref.get().let { r -> r == null || r === activity } } }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    private fun find(a: Activity) = live.firstOrNull { it.ref.get() === a }

    private fun screens(): List<Pair<NavScreen, Activity>> = live.mapNotNull { l ->
        val a = l.ref.get()?.takeIf { !it.isFinishing } ?: return@mapNotNull null
        NavScreen(a.javaClass.name, keyOf(a.intent), a.taskId, l.order, System.identityHashCode(a)) to a
    }

    private fun keyOf(i: Intent?): String = key(i?.dataString, i?.extraString("id"), i?.extraString("pid"))

    private fun Intent.extraString(name: String): String? = runCatching { getStringExtra(name) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun appTasks(ctx: Context): Map<Int, ActivityManager.AppTask> {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyMap()
        return runCatching {
            am.appTasks.mapNotNull { t ->
                val info = runCatching { t.taskInfo }.getOrNull() ?: return@mapNotNull null
                val id = if (Build.VERSION.SDK_INT >= 29) info.taskId else info.persistentId
                // a task only in Recents (not running) cannot take a screen on top
                val running = if (Build.VERSION.SDK_INT >= 29) info.isRunning else info.id >= 0
                if (!running) return@mapNotNull null
                id to t
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun rootOf(t: ActivityManager.AppTask): String =
        runCatching { t.taskInfo?.baseIntent?.component?.className }.getOrNull().orEmpty()

    // ---------- intents ----------

    /** Intent that opens the task's root screen as it was left (the same intent the launcher sends). */
    fun rootIntent(ctx: Context, home: String): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(ctx.packageName, home)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** Lumen's main task as the user left it (from bots, Share and document tasks). */
    fun home(ctx: Context) = ctx.startActivity(rootIntent(ctx, HOME))

    /** PendingIntent for a notification: brings back the screen of [target] or opens it in its task. */
    fun pending(ctx: Context, code: Int, target: Intent): PendingIntent = PendingIntent.getActivity(ctx, code,
        Intent(ctx, NavRouter::class.java).putExtra(EXTRA_TARGET, Intent(target).setFlags(0))
            .setAction("lumen.nav.$code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Suppress("DEPRECATION")
    internal fun targetOf(i: Intent): Intent? {
        val t: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
        else i.getParcelableExtra(EXTRA_TARGET)
        return t?.takeIf { it.component?.packageName == i.component?.packageName }
    }

    /** Opens [target] (an intent of one of our screens) where it belongs; see [plan]. */
    fun show(from: Context, target: Intent) {
        val cls = target.component?.className ?: return from.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val clean = Intent(target).setFlags(0)
        val tasks = appTasks(from)
        val scr = screens()
        val lastTask = scr.maxByOrNull { it.first.order }?.first?.task
        val p = plan(scr.map { it.first }, cls, keyOf(target), target.getIntExtra(EXTRA_INST, 0),
            tasks.mapValues { rootOf(it.value) }, lastTask)
        L.d("nav", "${cls.substringAfterLast('.')} → $p")
        try {
            when (p) {
                is NavPlan.Front -> {
                    val t = tasks[p.task]
                    if (t == null) return fresh(from, cls, clean)
                    // a task root comes back as it was left; a screen on top still gets the intent (onNewIntent)
                    if (cls == homeOf(cls) && keyOf(target) == NO_KEY) t.moveToFront()
                    else t.startActivity(from, clean.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), null)
                }
                is NavPlan.Reorder -> {
                    val t = tasks[p.task] ?: return fresh(from, cls, clean)
                    if (Build.VERSION.SDK_INT >= 31) {
                        t.startActivity(from, clean.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP), null)
                    } else {
                        // older systems ignore REORDER_TO_FRONT inside a given task: close the screens above
                        scr.filter { it.first.inst in p.above }.forEach { it.second.finish() }
                        t.moveToFront()
                    }
                }
                is NavPlan.Push -> tasks[p.task]?.startActivity(from, clean, null) ?: fresh(from, cls, clean)
                NavPlan.Fresh -> fresh(from, cls, clean)
            }
        } catch (e: Exception) {
            L.w("nav", "open ${cls.substringAfterLast('.')}: ${e.message}")
            from.startActivity(clean.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun fresh(from: Context, cls: String, target: Intent) {
        val home = homeOf(cls) ?: HOME
        if (cls == home) from.startActivity(rootIntent(from, home).putExtras(target))
        else from.startActivities(arrayOf(rootIntent(from, home), target))
    }
}

/** Invisible stop between a notification and the screen it belongs to (see [Nav.show]). */
class NavRouter : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lumen.init(this)
        if (savedInstanceState == null) Nav.targetOf(intent)?.let { Nav.show(this, it) }
        finish()
        overridePendingTransition(0, 0)
    }
}
