package com.mayze.jiratimetracker.jira

import java.time.OffsetDateTime

data class JiraProject(
    val id: String,
    val key: String,
    val name: String
) {
    override fun toString(): String = "$key - $name"
}

data class JiraIssue(
    val id: String,
    val key: String,
    val summary: String,
    val created: OffsetDateTime?,
    val description: String?,
    val status: String? = null,
    val statusCategory: String? = null,   // "new" | "indeterminate" | "done"
    val originalEstimateSeconds: Int? = null,
    val remainingEstimateSeconds: Int? = null,
    val attachmentUrls: List<JiraAttachment> = emptyList(),
    val priority: String? = null,         // "Highest","High","Medium","Low","Lowest"
    val subtasks: List<JiraIssueRef> = emptyList(),
    val linkedIssues: List<JiraIssueLink> = emptyList(),
    val parentKey: String? = null
)

data class JiraIssueRef(
    val key: String,
    val summary: String,
    val status: String?,
    val statusCategory: String?
)

data class JiraIssueLink(
    val type: String,       // e.g. "is blocked by", "blocks", "relates to"
    val direction: String,  // "inward" | "outward"
    val issueKey: String,
    val summary: String,
    val status: String?,
    val statusCategory: String?
)

data class JiraAttachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val url: String,
    val thumbnailUrl: String?
)

data class JiraTransition(
    val id: String,
    val name: String,
    val toStatusCategory: String?   // "new" | "indeterminate" | "done"
)

data class JiraComment(
    val id: String,
    val authorAccountId: String?,
    val authorKey: String?,
    val authorName: String?,
    val authorEmail: String?,
    val authorDisplayName: String?,
    val created: OffsetDateTime?,
    val body: String
)

data class JiraWorklog(
    val id: String,
    val authorAccountId: String?,
    val authorKey: String?,
    val authorName: String?,
    val authorEmail: String?,
    val authorDisplayName: String?,
    val started: OffsetDateTime?,
    val timeSpentSeconds: Int,
    val comment: String?
)

data class JiraUser(
    val accountId: String?,
    val key: String?,
    val name: String?,
    val email: String?,
    val displayName: String?
)
