package com.mayze.jiratimetracker.jira

import java.util.Base64

data class JiraAuth(
    val baseUrl: String,
    val usernameOrEmail: String,
    val tokenOrPassword: String,
    val isCloud: Boolean = true
) {
    fun authHeaderValue(): String {
        return if (isCloud) {
            // Jira Cloud: Basic auth with email:apiToken
            val raw = "$usernameOrEmail:$tokenOrPassword"
            val enc = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
            "Basic $enc"
        } else {
            // Jira Server/DC: try Bearer (PAT) first, fallback to Basic
            if (usernameOrEmail.isBlank()) {
                // PAT only — no username needed
                "Bearer $tokenOrPassword"
            } else {
                // Username + password/token — try Bearer, caller can retry with Basic
                "Bearer $tokenOrPassword"
            }
        }
    }

    fun basicAuthHeaderValue(): String {
        val raw = "$usernameOrEmail:$tokenOrPassword"
        val enc = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return "Basic $enc"
    }
}
