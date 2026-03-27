package com.mayze.jiratimetracker.settings

import java.util.UUID

data class JiraProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var baseUrl: String = "",
    var emailOrUsername: String = "",
    var isCloud: Boolean = true,
    var updateIntervalMinutes: Int = 5
) {
    override fun toString(): String = name.ifBlank { baseUrl }
}
