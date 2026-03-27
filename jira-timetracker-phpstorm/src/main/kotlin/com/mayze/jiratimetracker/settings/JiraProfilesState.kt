package com.mayze.jiratimetracker.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "JiraProfiles",
    storages = [Storage("jira-profiles.xml")]
)
class JiraProfilesState : PersistentStateComponent<JiraProfilesData> {
    private var data = JiraProfilesData()

    override fun getState(): JiraProfilesData = data
    override fun loadState(state: JiraProfilesData) { data = state }

    val profiles: MutableList<JiraProfile> get() = data.profiles
    var activeProfileId: String? get() = data.activeProfileId.ifBlank { null }
        set(v) { data.activeProfileId = v ?: "" }

    fun activeProfile(): JiraProfile? = profiles.find { it.id == activeProfileId }

    fun addProfile(profile: JiraProfile) { profiles.add(profile) }

    fun removeProfile(id: String) {
        profiles.removeIf { it.id == id }
        clearToken(id)
        if (activeProfileId == id) activeProfileId = profiles.firstOrNull()?.id
    }

    fun getToken(profileId: String): String {
        val attrs = CredentialAttributes("com.mayze.jiratimetracker/profile/$profileId")
        return PasswordSafe.instance.getPassword(attrs).orEmpty()
    }

    fun saveToken(profileId: String, token: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val attrs = CredentialAttributes("com.mayze.jiratimetracker/profile/$profileId")
            PasswordSafe.instance.set(attrs, Credentials(profileId, token))
        }
    }

    private fun clearToken(profileId: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val attrs = CredentialAttributes("com.mayze.jiratimetracker/profile/$profileId")
            PasswordSafe.instance.set(attrs, null)
        }
    }
}

/** Bean for XML serialization — must have no-arg constructor and mutable fields */
class JiraProfilesData {
    var profiles: MutableList<JiraProfile> = mutableListOf()
    var activeProfileId: String = ""
}
