/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Android AppFunctions (Android 16+): the on-device counterpart of MCP. System agents such as
 * Gemini (they hold EXECUTE_APP_FUNCTIONS) discover these functions and call them locally; nothing
 * leaves the phone through Lumen itself. Read-only: projects, report, collected data, videos, AI
 * insights. Off switch: Settings → General → "AI agents on the phone" (Settings.agentAccess).
 * The KSP compiler generates LumenAgentService (registered in the manifest) and assets/lumen_agent*.xml.
 */

/** A Lumen topic-analysis project. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AgentProject(
    /** Project ID, pass it to the other Lumen functions. */
    val id: String,
    /** Project name. */
    val name: String,
    /** The topic phrase the project analyses. */
    val topic: String,
    /** Topic domain or niche, may be empty. */
    val domain: String,
    /** Number of videos in the project. */
    val videoCount: Int,
    /** Number of videos whose details (comments, transcript) were collected. */
    val collectedCount: Int,
    /** Whether the project is archived. */
    val archived: Boolean,
)

/** A YouTube video found by a Lumen project. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AgentVideo(
    /** Video ID, pass it to getVideo. */
    val id: String,
    /** Video URL. */
    val url: String,
    /** Video title. */
    val title: String,
    /** Channel name. */
    val channel: String,
    /** View count, -1 if unknown. */
    val views: Long,
    /** Duration in seconds, -1 if unknown. */
    val durationSeconds: Long,
    /** Upload date as ISO day (yyyy-MM-dd), empty if unknown. */
    val uploaded: String,
    /** Whether it is a YouTube Short. */
    val isShort: Boolean,
)

/** Everything Lumen collected about one video. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AgentVideoDetails(
    /** Basic video data. */
    val video: AgentVideo,
    /** Video description. */
    val description: String,
    /** Video tags. */
    val tags: List<String>,
    /** Like count, -1 if unknown. */
    val likes: Long,
    /** Transcript of the speech (may be truncated), empty if not collected. */
    val transcript: String,
    /** The most liked comments with their like counts. */
    val topComments: List<String>,
    /** The latest AI summary of the video made in Lumen, empty if none. */
    val aiSummary: String,
)

/** A saved AI conclusion inside a Lumen project. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AgentInsight(
    /** Kind of the conclusion, e.g. project_summary, clusters, sentiment, video_summary. */
    val kind: String,
    /** What it is about: a video ID or "project". */
    val target: String,
    /** Model that produced it. */
    val model: String,
    /** Creation date as ISO day (yyyy-MM-dd). */
    val date: String,
    /** The conclusion text in Markdown (may be truncated). */
    val text: String,
)

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
    serviceName = "LumenAgentService",
    appFunctionXmlFileName = "lumen_agent",
)
abstract class LumenAgentServiceBase : AppFunctionService() {

    private fun ready() {
        Lumen.init(applicationContext)
        if (!Settings.agentAccess) throw AppFunctionDeniedException("Access for AI agents is turned off in Lumen settings")
        L.i("agent", "AppFunction call")
    }

    private fun projectOf(id: String): Project =
        Store.project(id) ?: throw AppFunctionElementNotFoundException("No Lumen project with ID $id; call listProjects")

    private fun Video.agent() = AgentVideo(
        id = id, url = url, title = title, channel = channel, views = views, durationSeconds = duration,
        uploaded = if (uploadMs > 0) Fmt.isoDay(uploadMs) else "", isShort = isShort,
    )

    /**
     * Lists the user's YouTube topic-analysis projects in Lumen.
     *
     * @return All projects with their IDs, topics and video counts, newest first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listProjects(): List<AgentProject> = withContext(Dispatchers.IO) {
        ready()
        Store.projects().map { p ->
            val vids = Store.videos(p.id).values
            AgentProject(p.id, p.name, p.phrase, p.domain, vids.size, vids.count { it.collected }, p.archived)
        }
    }

    /**
     * Gets the analytics report of a Lumen project: overview, AI summary, clusters, top channels and videos.
     *
     * @param projectId Project ID from listProjects.
     * @return The report in Markdown.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getProjectReport(projectId: String): String = withContext(Dispatchers.IO) {
        ready()
        projectOf(projectId)
        AgentCore.clip(Export.markdown(projectId), AgentCore.TEXT_DEFAULT)
    }

    /**
     * Gets everything a Lumen project collected as one Markdown document for an AI: channels, videos with
     * descriptions, transcripts and comments, saved AI conclusions and analysis data files.
     *
     * @param projectId Project ID from listProjects.
     * @param maxChars Maximum length of the answer in characters; default 60000.
     * @return The collected data in Markdown, truncated to maxChars.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getCollectedData(projectId: String, maxChars: Int?): String = withContext(Dispatchers.IO) {
        ready()
        projectOf(projectId)
        val max = AgentCore.limit(maxChars, AgentCore.TEXT_DEFAULT, AgentCore.TEXT_MAX)
        val sb = StringBuilder()
        Artifacts.markdown(Artifacts.source(projectId), Artifacts.Opts(extras = true), sb)
        AgentCore.clip(sb.toString(), max)
    }

    /**
     * Finds videos in a Lumen project by words in the title, tags, channel or description.
     *
     * @param projectId Project ID from listProjects.
     * @param query Words to look for; empty returns the most viewed videos.
     * @param limit Maximum number of videos, 1 to 100; default 20.
     * @return Matching videos, most relevant first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun findVideos(projectId: String, query: String?, limit: Int?): List<AgentVideo> = withContext(Dispatchers.IO) {
        ready()
        projectOf(projectId)
        AgentCore.findVideos(Store.videos(projectId).values, query, AgentCore.limit(limit, 20, 100)).map { it.agent() }
    }

    /**
     * Gets one video of a Lumen project with its description, tags, transcript, top comments and AI summary.
     *
     * @param projectId Project ID from listProjects.
     * @param videoId Video ID from findVideos.
     * @return The video details.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getVideo(projectId: String, videoId: String): AgentVideoDetails = withContext(Dispatchers.IO) {
        ready()
        projectOf(projectId)
        val v = Store.videos(projectId)[videoId]
            ?: throw AppFunctionElementNotFoundException("No video $videoId in project $projectId; call findVideos")
        AgentVideoDetails(
            video = v.agent(),
            description = AgentCore.clip(v.description, AgentCore.INSIGHT_MAX),
            tags = v.tags.toList(),
            likes = v.likes,
            transcript = AgentCore.clip(Store.transcript(projectId, videoId).orEmpty(), AgentCore.TRANSCRIPT_MAX),
            topComments = AgentCore.topComments(runCatching { Store.comments(projectId, videoId) }.getOrDefault(emptyList())),
            aiSummary = Store.latestAi(projectId, "video_summary", videoId)?.text?.trim().orEmpty(),
        )
    }

    /**
     * Gets the AI conclusions saved in a Lumen project (summaries, clusters, sentiment, audits), newest first.
     *
     * @param projectId Project ID from listProjects.
     * @param limit Maximum number of conclusions, 1 to 50; default 20.
     * @return The saved conclusions.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getAiInsights(projectId: String, limit: Int?): List<AgentInsight> = withContext(Dispatchers.IO) {
        ready()
        projectOf(projectId)
        Store.aiAll(projectId).sortedByDescending { it.at }.take(AgentCore.limit(limit, 20, 50)).map {
            AgentInsight(it.kind, it.target, it.model, Fmt.isoDay(it.at), AgentCore.clip(it.text.trim(), AgentCore.INSIGHT_MAX))
        }
    }
}
