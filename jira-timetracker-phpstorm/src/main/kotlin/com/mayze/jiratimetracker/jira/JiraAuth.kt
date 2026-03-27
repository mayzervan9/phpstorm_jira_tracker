package com.mayze.jiratimetracker.jira

import java.util.Base64

data class JiraAuth(
    val baseUrl: String,
    val usernameOrEmail: String,
    val tokenOrPassword: String
) {
    fun authHeaderValue(): String {
        val raw = "$usernameOrEmail:$tokenOrPassword"
        val enc = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return "Basic $enc"
    }
}

