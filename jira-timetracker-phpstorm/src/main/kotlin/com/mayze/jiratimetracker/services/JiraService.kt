package com.mayze.jiratimetracker.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.mayze.jiratimetracker.jira.JiraApi
import com.mayze.jiratimetracker.jira.JiraAuth
import com.mayze.jiratimetracker.settings.JiraProfilesState

@Service(Service.Level.APP)
class JiraService {
    fun apiFromSettings(): JiraApi {
        val state = service<JiraProfilesState>()
        val profile = state.activeProfile()
            ?: error("No active Jira profile. Open the Jira panel and add a profile.")
        val token = state.getToken(profile.id)
        require(profile.baseUrl.isNotBlank()) { "Base URL is empty in profile \"${profile.name}\"" }
        if (profile.isCloud) require(profile.emailOrUsername.isNotBlank()) { "Email/Username is empty in profile \"${profile.name}\"" }
        require(token.isNotBlank()) { "Token is empty in profile \"${profile.name}\"" }
        return JiraApi(JiraAuth(baseUrl = profile.baseUrl, usernameOrEmail = profile.emailOrUsername, tokenOrPassword = token, isCloud = profile.isCloud))
    }

    fun activeProfileName(): String? = service<JiraProfilesState>().activeProfile()?.name
}
