package com.mayze.jiratimetracker.jira

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class JiraApi(private val auth: JiraAuth) {
    private val base = auth.baseUrl.trim().trimEnd('/')

    private fun api(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        // Jira Cloud & modern Server/DC both support /rest/api/3 for most operations,
        // but some Server/DC versions still use /rest/api/2. For MVP we try v3 first and fallback for key endpoints.
        return "$base$p"
    }

    fun testConnection(): String {
        // Cloud: /rest/api/3/myself ; Server: /rest/api/2/myself
        val v3 = tryGetJson("$base/rest/api/3/myself")
        if (v3.first == 200) {
            return v3.second.optString("displayName", "OK")
        }
        val v2 = tryGetJson("$base/rest/api/2/myself")
        if (v2.first == 200) {
            return v2.second.optString("displayName", "OK")
        }
        throw RuntimeException("Jira connection failed: HTTP ${v3.first} / ${v2.first}")
    }

    fun getMyself(): JiraUser {
        val (c3, j3) = tryGetJson("$base/rest/api/3/myself")
        if (c3 == 200) {
            return JiraUser(
                accountId = j3.optString("accountId").ifBlank { null },
                key = j3.optString("key").ifBlank { null },
                name = j3.optString("name").ifBlank { null },
                email = j3.optString("emailAddress").ifBlank { null },
                displayName = j3.optString("displayName").ifBlank { null }
            )
        }
        val (c2, j2) = tryGetJson("$base/rest/api/2/myself")
        if (c2 == 200) {
            return JiraUser(
                accountId = j2.optString("accountId").ifBlank { null },
                key = j2.optString("key").ifBlank { null },
                name = j2.optString("name").ifBlank { null },
                email = j2.optString("emailAddress").ifBlank { null },
                displayName = j2.optString("displayName").ifBlank { null }
            )
        }
        throw RuntimeException("Myself load failed: HTTP $c3 / $c2")
    }

    fun getProjects(): List<JiraProject> {
        val (code, json) = tryGetJson("$base/rest/api/3/project/search")
        if (code != 200) {
            // fallback old endpoint
            val (c2, arr) = tryGetJsonArray("$base/rest/api/2/project")
            if (c2 != 200) throw RuntimeException("Projects load failed: HTTP $code / $c2")
            return arr.map {
                JiraProject(
                    id = it.optString("id"),
                    key = it.optString("key"),
                    name = it.optString("name")
                )
            }
        }
        val values = json.optJSONArray("values") ?: JSONArray()
        return values.map {
            JiraProject(
                id = it.optString("id"),
                key = it.optString("key"),
                name = it.optString("name")
            )
        }
    }

    fun searchMyIssues(
        projectKey: String,
        maxResults: Int = 50,
        statuses: List<String> = emptyList(),
        onlyWithoutEstimate: Boolean = false,
        scope: String = "My issues"
    ): List<JiraIssue> {
        var jql = when (scope) {
            "Involved" -> "(assignee = currentUser() OR reporter = currentUser() OR watcher = currentUser()) AND project = $projectKey"
            "All project" -> "project = $projectKey"
            else -> "assignee = currentUser() AND project = $projectKey"
        }
        if (statuses.isNotEmpty()) {
            val statusList = statuses.joinToString(",") { "\"$it\"" }
            jql += " AND status in ($statusList)"
        }
        if (onlyWithoutEstimate) jql += " AND originalEstimate is EMPTY"
        jql += " ORDER BY created DESC"

        val body = JSONObject()
            .put("jql", jql)
            .put("maxResults", maxResults)
            .put(
                "fields",
                JSONArray()
                    .put("summary").put("created").put("description")
                    .put("status").put("timetracking").put("attachment").put("priority")
            )

        // 1) Newer Jira Cloud endpoint
        val (codeV3JqlPost, jsonV3JqlPost) = tryPostJson("$base/rest/api/3/search/jql", body)
        if (codeV3JqlPost == 200) return parseIssues(jsonV3JqlPost.optJSONArray("issues") ?: JSONArray())

        // 2) Modern Jira Cloud/Server endpoint
        val (codeV3Post, jsonV3Post) = tryPostJson("$base/rest/api/3/search", body)
        if (codeV3Post == 200) return parseIssues(jsonV3Post.optJSONArray("issues") ?: JSONArray())

        // 3) Legacy fallback endpoint
        val (codeV2Post, jsonV2Post) = tryPostJson("$base/rest/api/2/search", body)
        if (codeV2Post == 200) return parseIssues(jsonV2Post.optJSONArray("issues") ?: JSONArray())

        // 4) GET fallbacks
        val encodedJql = URLEncoder.encode(jql, Charsets.UTF_8)
        val fields = URLEncoder.encode("summary,created,description", Charsets.UTF_8)
        val getV3JqlUrl = "$base/rest/api/3/search/jql?jql=$encodedJql&maxResults=$maxResults&fields=$fields"
        val (codeV3JqlGet, jsonV3JqlGet) = tryGetJson(getV3JqlUrl)
        if (codeV3JqlGet == 200) return parseIssues(jsonV3JqlGet.optJSONArray("issues") ?: JSONArray())

        val getV3Url = "$base/rest/api/3/search?jql=$encodedJql&maxResults=$maxResults&fields=$fields"
        val (codeV3Get, jsonV3Get) = tryGetJson(getV3Url)
        if (codeV3Get == 200) return parseIssues(jsonV3Get.optJSONArray("issues") ?: JSONArray())

        val getV2Url = "$base/rest/api/2/search?jql=$encodedJql&maxResults=$maxResults&fields=$fields"
        val (codeV2Get, jsonV2Get) = tryGetJson(getV2Url)
        if (codeV2Get == 200) return parseIssues(jsonV2Get.optJSONArray("issues") ?: JSONArray())

        throw RuntimeException(
            "Issue search failed (v3/jql POST=$codeV3JqlPost, v3 POST=$codeV3Post, v2 POST=$codeV2Post, v3/jql GET=$codeV3JqlGet, v3 GET=$codeV3Get, v2 GET=$codeV2Get)"
        )
    }

    fun getIssueDetails(issueKey: String): JiraIssue {
        val fields = "summary,created,description,status,timetracking,attachment,priority,subtasks,issuelinks,parent"
        val (code, json) = tryGetJson("$base/rest/api/3/issue/$issueKey?fields=$fields")
        if (code != 200) {
            val (c2, j2) = tryGetJson("$base/rest/api/2/issue/$issueKey?fields=$fields")
            if (c2 != 200) throw RuntimeException("Issue load failed: HTTP $code / $c2")
            return parseIssue(j2)
        }
        return parseIssue(json)
    }

    /** Returns ALL fields as raw key-value pairs for the Fields tab */
    fun getAllIssueFields(issueKey: String): List<Pair<String, String>> {
        val (code, json) = tryGetJson("$base/rest/api/3/issue/$issueKey")
        val raw = if (code == 200) json else {
            val (c2, j2) = tryGetJson("$base/rest/api/2/issue/$issueKey")
            if (c2 != 200) return listOf("Error" to "HTTP $code / $c2")
            j2
        }
        val fields = raw.optJSONObject("fields") ?: return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        for (key in fields.keys().asSequence().sorted()) {
            val v = fields.opt(key) ?: continue
            val display = fieldToDisplay(v)
            if (display.isNotBlank()) result.add(key to display)
        }
        return result
    }

    private fun fieldToDisplay(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        is JSONObject -> {
            // Common patterns: {name:"X"}, {displayName:"X"}, {value:"X"}, {key:"X"}
            v.optString("name").ifBlank { null }
                ?: v.optString("displayName").ifBlank { null }
                ?: v.optString("value").ifBlank { null }
                ?: v.optString("key").ifBlank { null }
                ?: v.optString("emailAddress").ifBlank { null }
                ?: jiraRichTextToPlain(v).ifBlank { v.toString().take(200) }
        }
        is JSONArray -> {
            (0 until v.length()).mapNotNull { i ->
                val item = v.opt(i)
                fieldToDisplay(item).ifBlank { null }
            }.joinToString(", ").ifBlank { "" }
        }
        else -> v.toString().take(200)
    }

    /** Returns description as HTML (from ADF or plain text) */
    fun getIssueDescriptionHtml(issueKey: String): String {
        val (code, json) = tryGetJson("$base/rest/api/3/issue/$issueKey?fields=description,attachment")
        val raw = if (code == 200) json else {
            val (c2, j2) = tryGetJson("$base/rest/api/2/issue/$issueKey?fields=description,attachment")
            if (c2 != 200) return "<p>Error loading description</p>"
            j2
        }
        val fields = raw.optJSONObject("fields") ?: return ""
        val desc = fields.opt("description")
        val html = if (desc is JSONObject) adfToHtml(desc) else (desc?.toString() ?: "").replace("\n", "<br/>")

        val attachments = fields.optJSONArray("attachment")
        val attHtml = if (attachments != null && attachments.length() > 0) {
            val sb = StringBuilder("<hr/><b>Attachments:</b><br/>")
            for (i in 0 until attachments.length()) {
                val a = attachments.optJSONObject(i) ?: continue
                val name = a.optString("filename")
                val url = a.optString("content")
                val mime = a.optString("mimeType")
                val thumb = a.optString("thumbnail").ifBlank { null }
                if (mime.startsWith("image/") && thumb != null) {
                    sb.append("<p><b>$name</b><br/><a href='$url'>$url</a></p>")
                } else {
                    sb.append("<p><a href='$url'>$name</a> [$mime]</p>")
                }
            }
            sb.toString()
        } else ""

        return "<html><body style='font-family:sans-serif;font-size:12px;padding:8px'>$html$attHtml</body></html>"
    }

    private fun adfToHtml(node: JSONObject): String {
        val sb = StringBuilder()
        val type = node.optString("type")
        val text = node.optString("text")
        val content = node.optJSONArray("content")

        when (type) {
            "doc" -> content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }
            "paragraph" -> { sb.append("<p>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</p>") }
            "heading" -> { val lvl = node.optInt("attrs.level", 3).coerceIn(1,6); sb.append("<h$lvl>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</h$lvl>") }
            "text" -> {
                var t = text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                val marks = node.optJSONArray("marks")
                if (marks != null) for (i in 0 until marks.length()) {
                    when (marks.getJSONObject(i).optString("type")) {
                        "strong" -> t = "<b>$t</b>"
                        "em" -> t = "<i>$t</i>"
                        "code" -> t = "<code>$t</code>"
                        "link" -> { val href = marks.getJSONObject(i).optJSONObject("attrs")?.optString("href") ?: ""; t = "<a href='$href'>$t</a>" }
                    }
                }
                sb.append(t)
            }
            "hardBreak" -> sb.append("<br/>")
            "bulletList" -> { sb.append("<ul>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</ul>") }
            "orderedList" -> { sb.append("<ol>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</ol>") }
            "listItem" -> { sb.append("<li>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</li>") }
            "codeBlock" -> { sb.append("<pre>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</pre>") }
            "blockquote" -> { sb.append("<blockquote>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</blockquote>") }
            "rule" -> sb.append("<hr/>")
            "mediaGroup", "mediaSingle" -> content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }
            "media" -> { val id = node.optJSONObject("attrs")?.optString("id") ?: ""; sb.append("<p>[media: $id]</p>") }
            "inlineCard" -> { val url = node.optJSONObject("attrs")?.optString("url") ?: ""; sb.append("<a href='$url'>$url</a>") }
            "table" -> { sb.append("<table border='1' cellpadding='4'>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</table>") }
            "tableRow" -> { sb.append("<tr>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</tr>") }
            "tableHeader" -> { sb.append("<th>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</th>") }
            "tableCell" -> { sb.append("<td>"); content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }; sb.append("</td>") }
            else -> content?.let { for (i in 0 until it.length()) sb.append(adfToHtml(it.getJSONObject(i))) }
        }
        return sb.toString()
    }

    /** Returns available transitions for an issue */
    fun getTransitions(issueKey: String): List<JiraTransition> {
        val (code, json) = tryGetJson("$base/rest/api/3/issue/$issueKey/transitions")
        val arr = if (code == 200) json.optJSONArray("transitions")
        else {
            val (c2, j2) = tryGetJson("$base/rest/api/2/issue/$issueKey/transitions")
            if (c2 != 200) return emptyList()
            j2.optJSONArray("transitions")
        } ?: return emptyList()

        return arr.map { t ->
            val toStatus = t.optJSONObject("to")
            val cat = toStatus?.optJSONObject("statusCategory")?.optString("key")
            JiraTransition(
                id = t.optString("id"),
                name = t.optString("name"),
                toStatusCategory = cat
            )
        }
    }

    /** Performs a transition on an issue */
    fun doTransition(issueKey: String, transitionId: String) {
        val payload = JSONObject().put("transition", JSONObject().put("id", transitionId))
        val (code, _) = tryPostJson("$base/rest/api/3/issue/$issueKey/transitions", payload)
        if (code == 204) return
        val (c2, _) = tryPostJson("$base/rest/api/2/issue/$issueKey/transitions", payload)
        if (c2 != 204) throw RuntimeException("Transition failed: HTTP $code / $c2")
    }

    /** Sets originalEstimate and optionally remainingEstimate */
    fun setEstimate(issueKey: String, originalEstimateSeconds: Int, remainingEstimateSeconds: Int? = null) {
        val origStr = formatJiraDuration(originalEstimateSeconds)
        val tt = JSONObject().put("originalEstimate", origStr)
        if (remainingEstimateSeconds != null) tt.put("remainingEstimate", formatJiraDuration(remainingEstimateSeconds))
        val payload = JSONObject().put("fields", JSONObject().put("timetracking", tt))
        val (code, _) = tryPutJson("$base/rest/api/3/issue/$issueKey", payload)
        if (code in 200..204) return
        val (c2, _) = tryPutJson("$base/rest/api/2/issue/$issueKey", payload)
        if (c2 !in 200..204) {
            // Fallback: try with seconds format
            val tt2 = JSONObject().put("originalEstimate", "${originalEstimateSeconds}s")
            if (remainingEstimateSeconds != null) tt2.put("remainingEstimate", "${remainingEstimateSeconds}s")
            val payload2 = JSONObject().put("fields", JSONObject().put("timetracking", tt2))
            val (c3, _) = tryPutJson("$base/rest/api/3/issue/$issueKey", payload2)
            if (c3 in 200..204) return
            val (c4, _) = tryPutJson("$base/rest/api/2/issue/$issueKey", payload2)
            if (c4 !in 200..204) throw RuntimeException("Set estimate failed: HTTP $code / $c2 / $c3 / $c4")
        }
    }

    private fun formatJiraDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 -> "${m}m"
            else -> "${seconds}s"
        }
    }

    /** Returns all statuses available in the project */
    fun getProjectStatuses(projectKey: String): List<String> {
        val (code, text) = requestText("GET", "$base/rest/api/3/project/$projectKey/statuses", null)
        val raw = if (code == 200) text else {
            val (c2, t2) = requestText("GET", "$base/rest/api/2/project/$projectKey/statuses", null)
            if (c2 != 200) return emptyList()
            t2
        }
        val statuses = mutableSetOf<String>()
        val types = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }
        for (i in 0 until types.length()) {
            val t = types.optJSONObject(i) ?: continue
            val ss = t.optJSONArray("statuses") ?: continue
            for (j in 0 until ss.length()) {
                val s = ss.optJSONObject(j)?.optString("name") ?: continue
                if (s.isNotBlank()) statuses.add(s)
            }
        }
        return statuses.sorted()
    }

    fun getComments(issueKey: String, maxResults: Int = 100): List<JiraComment> {
        val (code, json) = tryGetJson("$base/rest/api/3/issue/$issueKey/comment?maxResults=$maxResults")
        if (code != 200) {
            val (c2, j2) = tryGetJson("$base/rest/api/2/issue/$issueKey/comment?maxResults=$maxResults")
            if (c2 != 200) throw RuntimeException("Comments load failed: HTTP $code / $c2")
            return parseComments(j2.optJSONArray("comments") ?: JSONArray())
        }
        return parseComments(json.optJSONArray("comments") ?: JSONArray())
    }

    fun addComment(issueKey: String, bodyText: String) {
        val payload = JSONObject().put("body", bodyText)
        val (code, _) = tryPostJson("$base/rest/api/3/issue/$issueKey/comment", payload)
        if (code == 201) return
        val (c2, _) = tryPostJson("$base/rest/api/2/issue/$issueKey/comment", payload)
        if (c2 != 201) throw RuntimeException("Add comment failed: HTTP $code / $c2")
    }

    fun getWorklogs(issueKey: String, maxResults: Int = 200): List<JiraWorklog> {
        val (code, json) = tryGetJson("$base/rest/api/3/issue/$issueKey/worklog?maxResults=$maxResults")
        if (code != 200) {
            val (c2, j2) = tryGetJson("$base/rest/api/2/issue/$issueKey/worklog?maxResults=$maxResults")
            if (c2 != 200) throw RuntimeException("Worklogs load failed: HTTP $code / $c2")
            return parseWorklogs(j2.optJSONArray("worklogs") ?: JSONArray())
        }
        return parseWorklogs(json.optJSONArray("worklogs") ?: JSONArray())
    }

    fun addWorklog(issueKey: String, started: OffsetDateTime, timeSpentSeconds: Int, comment: String?): String {
        val jiraStarted = started.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
        val payload = JSONObject()
            .put("started", jiraStarted)
            .put("timeSpentSeconds", timeSpentSeconds)

        // v3 prefers Atlassian Document Format for rich-text fields.
        if (!comment.isNullOrBlank()) {
            payload.put("comment", adfText(comment))
        }

        val (codeV3, jV3) = tryPostJson("$base/rest/api/3/issue/$issueKey/worklog", payload)
        if (codeV3 == 201) return jV3.optString("id")

        // Retry without comment (some instances reject comment payload shape).
        if (!comment.isNullOrBlank()) {
            val payloadNoComment = JSONObject()
                .put("started", jiraStarted)
                .put("timeSpentSeconds", timeSpentSeconds)
            val (codeV3NoComment, jV3NoComment) = tryPostJson("$base/rest/api/3/issue/$issueKey/worklog", payloadNoComment)
            if (codeV3NoComment == 201) return jV3NoComment.optString("id")
        }

        // v2 usually accepts plain string comment.
        val payloadV2 = JSONObject()
            .put("started", jiraStarted)
            .put("timeSpentSeconds", timeSpentSeconds)
        if (!comment.isNullOrBlank()) payloadV2.put("comment", comment)
        val (codeV2, jV2) = tryPostJson("$base/rest/api/2/issue/$issueKey/worklog", payloadV2)
        if (codeV2 != 201) throw RuntimeException("Add worklog failed: HTTP $codeV3 / $codeV2")
        return jV2.optString("id")
    }

    fun updateWorklog(
        issueKey: String,
        worklogId: String,
        started: OffsetDateTime,
        timeSpentSeconds: Int,
        comment: String?
    ) {
        val jiraStarted = started.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
        val payloadV3 = JSONObject()
            .put("started", jiraStarted)
            .put("timeSpentSeconds", timeSpentSeconds)
        if (!comment.isNullOrBlank()) payloadV3.put("comment", adfText(comment))

        val (codeV3, _) = tryPutJson("$base/rest/api/3/issue/$issueKey/worklog/$worklogId", payloadV3)
        if (codeV3 == 200) return

        val payloadV2 = JSONObject()
            .put("started", jiraStarted)
            .put("timeSpentSeconds", timeSpentSeconds)
        if (!comment.isNullOrBlank()) payloadV2.put("comment", comment)

        val (codeV2, _) = tryPutJson("$base/rest/api/2/issue/$issueKey/worklog/$worklogId", payloadV2)
        if (codeV2 != 200) throw RuntimeException("Update worklog failed: HTTP $codeV3 / $codeV2")
    }

    private fun parseIssues(arr: JSONArray): List<JiraIssue> = arr.map { parseIssue(it) }

    private fun parseIssue(obj: JSONObject): JiraIssue {
        val fields = obj.optJSONObject("fields") ?: JSONObject()
        val statusObj = fields.optJSONObject("status")
        val statusName = statusObj?.optString("name")?.ifBlank { null }
        val statusCat  = statusObj?.optJSONObject("statusCategory")?.optString("key")?.ifBlank { null }

        val tt = fields.optJSONObject("timetracking")
        val origSec = tt?.optInt("originalEstimateSeconds", -1)?.takeIf { it >= 0 }
        val remSec  = tt?.optInt("remainingEstimateSeconds", -1)?.takeIf { it >= 0 }

        val priority = fields.optJSONObject("priority")?.optString("name")?.ifBlank { null }

        val parentKey = fields.optJSONObject("parent")?.optString("key")?.ifBlank { null }

        val attachments = mutableListOf<JiraAttachment>()
        val attArr = fields.optJSONArray("attachment")
        if (attArr != null) {
            for (i in 0 until attArr.length()) {
                val a = attArr.optJSONObject(i) ?: continue
                attachments.add(JiraAttachment(
                    id = a.optString("id"),
                    filename = a.optString("filename"),
                    mimeType = a.optString("mimeType"),
                    url = a.optString("content"),
                    thumbnailUrl = a.optString("thumbnail").ifBlank { null }
                ))
            }
        }

        val subtasks = mutableListOf<com.mayze.jiratimetracker.jira.JiraIssueRef>()
        val subArr = fields.optJSONArray("subtasks")
        if (subArr != null) {
            for (i in 0 until subArr.length()) {
                val s = subArr.optJSONObject(i) ?: continue
                val sf = s.optJSONObject("fields") ?: JSONObject()
                val ss = sf.optJSONObject("status")
                subtasks.add(com.mayze.jiratimetracker.jira.JiraIssueRef(
                    key = s.optString("key"),
                    summary = sf.optString("summary"),
                    status = ss?.optString("name")?.ifBlank { null },
                    statusCategory = ss?.optJSONObject("statusCategory")?.optString("key")?.ifBlank { null }
                ))
            }
        }

        val links = mutableListOf<com.mayze.jiratimetracker.jira.JiraIssueLink>()
        val linkArr = fields.optJSONArray("issuelinks")
        if (linkArr != null) {
            for (i in 0 until linkArr.length()) {
                val l = linkArr.optJSONObject(i) ?: continue
                val typeObj = l.optJSONObject("type")
                val inwardIssue  = l.optJSONObject("inwardIssue")
                val outwardIssue = l.optJSONObject("outwardIssue")
                val (dir, linkedIssueObj, typeName) = when {
                    inwardIssue  != null -> Triple("inward",  inwardIssue,  typeObj?.optString("inward")  ?: "")
                    outwardIssue != null -> Triple("outward", outwardIssue, typeObj?.optString("outward") ?: "")
                    else -> continue
                }
                val lf = linkedIssueObj.optJSONObject("fields") ?: JSONObject()
                val ls = lf.optJSONObject("status")
                links.add(com.mayze.jiratimetracker.jira.JiraIssueLink(
                    type = typeName,
                    direction = dir,
                    issueKey = linkedIssueObj.optString("key"),
                    summary = lf.optString("summary"),
                    status = ls?.optString("name")?.ifBlank { null },
                    statusCategory = ls?.optJSONObject("statusCategory")?.optString("key")?.ifBlank { null }
                ))
            }
        }

        return JiraIssue(
            id = obj.optString("id"),
            key = obj.optString("key"),
            summary = fields.optString("summary"),
            created = fields.optString("created").toOffsetDateTimeOrNull(),
            description = jiraRichTextToPlain(fields.opt("description")),
            status = statusName,
            statusCategory = statusCat,
            originalEstimateSeconds = origSec,
            remainingEstimateSeconds = remSec,
            attachmentUrls = attachments,
            priority = priority,
            subtasks = subtasks,
            linkedIssues = links,
            parentKey = parentKey
        )
    }

    private fun parseComments(arr: JSONArray): List<JiraComment> = arr.map { c ->
        val author = c.optJSONObject("author")
        JiraComment(
            id = c.optString("id"),
            authorAccountId = author?.optString("accountId"),
            authorKey = author?.optString("key"),
            authorName = author?.optString("name"),
            authorEmail = author?.optString("emailAddress"),
            authorDisplayName = author?.optString("displayName"),
            created = c.optString("created").toOffsetDateTimeOrNull(),
            body = jiraRichTextToPlain(c.opt("body"))
        )
    }

    private fun parseWorklogs(arr: JSONArray): List<JiraWorklog> = arr.map { w ->
        val author = w.optJSONObject("author")
        JiraWorklog(
            id = w.optString("id"),
            authorAccountId = author?.optString("accountId"),
            authorKey = author?.optString("key"),
            authorName = author?.optString("name"),
            authorEmail = author?.optString("emailAddress"),
            authorDisplayName = author?.optString("displayName"),
            started = w.optString("started").toOffsetDateTimeOrNull(),
            timeSpentSeconds = w.optInt("timeSpentSeconds", 0),
            comment = jiraRichTextToPlain(w.opt("comment"))
        )
    }

    private fun tryGetJson(url: String): Pair<Int, JSONObject> {
        val (code, text) = requestText("GET", url, null)
        return Pair(code, text.toJsonObjectOrEmpty())
    }

    private fun tryGetJsonArray(url: String): Pair<Int, JSONArray> {
        val (code, text) = requestText("GET", url, null)
        return Pair(code, text.toJsonArrayOrEmpty())
    }

    private fun tryPostJson(url: String, body: JSONObject): Pair<Int, JSONObject> {
        val (code, text) = requestText("POST", url, body.toString())
        return Pair(code, text.toJsonObjectOrEmpty())
    }

    private fun tryPutJson(url: String, body: JSONObject): Pair<Int, JSONObject> {
        val (code, text) = requestText("PUT", url, body.toString())
        return Pair(code, text.toJsonObjectOrEmpty())
    }

    private fun requestText(method: String, url: String, body: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", auth.authHeaderValue())
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JiraTimeTrackerPlugin")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.getResponseCodeSafe()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            Pair(code, text)
        } catch (t: Throwable) {
            Pair(-1, t.message.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}

private fun HttpURLConnection?.getResponseCodeSafe(): Int = try {
    this?.responseCode ?: -1
} catch (_: Throwable) {
    -1
}

private fun String.toJsonObjectOrEmpty(): JSONObject = try {
    if (isBlank()) JSONObject() else JSONObject(this)
} catch (_: Throwable) {
    JSONObject()
}

private fun String.toJsonArrayOrEmpty(): JSONArray = try {
    if (isBlank()) JSONArray() else JSONArray(this)
} catch (_: Throwable) {
    JSONArray()
}

private fun String.toOffsetDateTimeOrNull(): OffsetDateTime? = try {
    if (isBlank()) null else OffsetDateTime.parse(this)
} catch (_: Throwable) {
    try {
        // Common Jira pattern: 2026-03-27T04:12:00.000+0000
        OffsetDateTime.parse(this, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
    } catch (_: Throwable) {
        try {
            // Rare fallback without timezone
            LocalDateTime.parse(this, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"))
                .atOffset(ZoneOffset.UTC)
        } catch (_: Throwable) {
            null
        }
    }
}

private inline fun <reified T> JSONArray.map(transform: (JSONObject) -> T): List<T> {
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        out.add(transform(obj))
    }
    return out
}

private fun adfText(text: String): JSONObject {
    return JSONObject()
        .put("type", "doc")
        .put("version", 1)
        .put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "paragraph")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", text)
                        )
                    )
            )
        )
}

private fun jiraRichTextToPlain(value: Any?): String {
    if (value == null) return ""
    if (value is String) return value
    if (value !is JSONObject) return value.toString()

    val sb = StringBuilder()
    fun walk(node: Any?) {
        when (node) {
            is JSONObject -> {
                val type = node.optString("type")
                val text = node.optString("text")
                if (type == "text" && text.isNotBlank()) sb.append(text)
                val content = node.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) walk(content.opt(i))
                    if (type in setOf("paragraph", "heading", "bulletList", "orderedList", "listItem")) {
                        if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) walk(node.opt(i))
            }
            is String -> if (node.isNotBlank()) sb.append(node)
        }
    }
    walk(value)
    return sb.toString().trim().ifBlank { value.toString() }
}

