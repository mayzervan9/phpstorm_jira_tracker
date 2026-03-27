package com.mayze.jiratimetracker.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.ClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.mayze.jiratimetracker.services.TimeTrackerService
import com.mayze.jiratimetracker.services.TrackingListener
import com.mayze.jiratimetracker.services.TrackingSnapshot
import com.mayze.jiratimetracker.services.TrackingTopics
import com.mayze.jiratimetracker.util.formatSecondsShort
import java.awt.Color
import java.awt.event.MouseEvent
import javax.swing.JComponent

class JiraTrackingStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "jira.tracking.status.widget"
    override fun getDisplayName(): String = "Jira Tracking"
    override fun isAvailable(project: Project): Boolean = true
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    override fun isConfigurable(): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = JiraTrackingStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
}

private class JiraTrackingStatusBarWidget(private val project: Project) : CustomStatusBarWidget, TrackingListener {
    private val label = JBLabel()
    private var statusBar: StatusBar? = null
    private val connection: MessageBusConnection = project.messageBus.connect()

    init {
        label.isOpaque = true
        label.border = JBUI.Borders.empty(2, 6)
        label.toolTipText = "Click to Start/Stop Jira tracking"
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                project.service<TimeTrackerService>().toggleFromAnywhere()
                return true
            }
        }.installOn(label)
        connection.subscribe(TrackingTopics.TRACKING_UPDATES, this)
        render(project.service<TimeTrackerService>().getSnapshot())
    }

    override fun ID(): String = "jira.tracking.status.widget"
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) { this.statusBar = statusBar }
    override fun dispose() = connection.disconnect()

    override fun onTrackingUpdated(snapshot: TrackingSnapshot) {
        render(snapshot)
        statusBar?.updateWidget(ID())
    }

    private fun render(snapshot: TrackingSnapshot) {
        val issue = snapshot.activeIssueKey ?: "no issue"
        val current = formatSecondsShort(snapshot.currentIssueTrackedSeconds)
        val total = formatSecondsShort(snapshot.totalTodaySeconds)
        if (snapshot.running) {
            label.background = JBColor(Color(0x2E7D32), Color(0x2E7D32))
            label.foreground = Color.WHITE
            label.text = "⏺ Jira  $issue  $current  /  $total today"
        } else {
            label.background = JBColor(Color(0xF9A825), Color(0xF9A825))
            label.foreground = Color(0x1A1A1A)
            label.text = "⏸ Jira  $issue  $total today"
        }
    }
}
