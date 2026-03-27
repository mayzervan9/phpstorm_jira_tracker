package com.mayze.jiratimetracker.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.mayze.jiratimetracker.services.TimeTrackerService

class ToggleTrackingAction : AnAction("Jira Start"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        project.service<TimeTrackerService>().toggleFromAnywhere()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = true
        val tracker = project.service<TimeTrackerService>()
        e.presentation.text = if (tracker.isRunning()) "Jira Stop" else "Jira Start"
    }
}

