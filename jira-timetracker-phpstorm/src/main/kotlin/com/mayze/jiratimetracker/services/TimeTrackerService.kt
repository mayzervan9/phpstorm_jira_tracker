package com.mayze.jiratimetracker.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.mayze.jiratimetracker.jira.JiraApi
import com.mayze.jiratimetracker.jira.JiraWorklog
import com.mayze.jiratimetracker.settings.JiraProfilesState
import com.mayze.jiratimetracker.util.formatSecondsShort
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class TimeTrackerService(private val project: Project) {
    private val todayByIssueSeconds = LinkedHashMap<String, Int>()
    private var currentIssueKey: String? = null
    private var activeIssueKey: String? = null
    private var startedAtLocal: OffsetDateTime? = null
    private var lastFlushAt: OffsetDateTime? = null
    private val accumulatedSeconds = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    private val flushing = AtomicBoolean(false)
    private var scheduled: ScheduledFuture<*>? = null
    private var uiPulseScheduled: ScheduledFuture<*>? = null

    // Current user identity (cached after first fetch)
    private var currentUserAccountId: String? = null
    private var currentUserKey: String? = null
    private var currentUserName: String? = null
    private var currentUserEmail: String? = null
    private var currentUserDisplayName: String? = null

    // Active worklog state
    private var activeWorklogId: String? = null
    private var activeWorklogStarted: OffsetDateTime? = null
    private var activeWorklogBaseSeconds: Int = 0

    fun isRunning(): Boolean = running.get()
    fun getCurrentIssueKey(): String? = currentIssueKey
    fun getAccumulatedSeconds(): Int = accumulatedSeconds.get()
    fun getActiveIssueKey(): String? = activeIssueKey
    fun getSnapshot(): TrackingSnapshot = snapshot()

    fun setActiveIssue(issueKey: String?) {
        activeIssueKey = issueKey
        publish()
    }

    fun toggleFromAnywhere() {
        if (isRunning()) stop()
        else {
            val issue = activeIssueKey
            if (!issue.isNullOrBlank()) start(issue)
            else notify("No issue selected", "Select an issue in the Jira panel first.")
        }
    }

    fun start(issueKey: String) {
        if (running.get()) stop()

        currentIssueKey = issueKey
        activeIssueKey = issueKey
        val now = OffsetDateTime.now(ZoneId.systemDefault())
        startedAtLocal = now
        lastFlushAt = now
        accumulatedSeconds.set(0)
        running.set(true)

        val intervalMin = (service<JiraProfilesState>().activeProfile()?.updateIntervalMinutes ?: 5).coerceIn(1, 60)

        AppExecutorUtil.getAppScheduledExecutorService().execute {
            try {
                val me = service<JiraService>().apiFromSettings().getMyself()
                currentUserAccountId = me.accountId
                currentUserKey = me.key
                currentUserName = me.name
                currentUserEmail = me.email
                currentUserDisplayName = me.displayName
            } catch (_: Throwable) {}

            try {
                val jira = service<JiraService>().apiFromSettings()
                val linked = findTodayWorklogForCurrentUser(jira, issueKey)
                activeWorklogId = linked?.id
                activeWorklogStarted = linked?.started ?: now
                activeWorklogBaseSeconds = linked?.timeSpentSeconds ?: 0
                if (activeWorklogBaseSeconds > 0) todayByIssueSeconds[issueKey] = activeWorklogBaseSeconds
            } catch (_: Throwable) {
                activeWorklogId = null
                activeWorklogStarted = now
                activeWorklogBaseSeconds = 0
            }
            publish()
        }

        scheduled = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { flushTick() }, intervalMin.toLong(), intervalMin.toLong(), TimeUnit.MINUTES
        )
        uiPulseScheduled = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { publish() }, 0, 1, TimeUnit.SECONDS
        )

        notify("Tracking started", issueKey)
        publish()
        project.messageBus.syncPublisher(TrackingTopics.TRACKING_STARTED).onTrackingStarted(issueKey)
    }

    fun stop() {
        if (!running.get()) return
        scheduled?.cancel(false)
        scheduled = null
        uiPulseScheduled?.cancel(false)
        uiPulseScheduled = null
        running.set(false)
        val stoppedIssue = currentIssueKey.orEmpty()
        notify("Tracking stopped", "${stoppedIssue} — ${formatSecondsShort(accumulatedSeconds.get())} this session")
        currentIssueKey = null
        startedAtLocal = null
        lastFlushAt = null
        currentUserAccountId = null; currentUserKey = null; currentUserName = null
        currentUserEmail = null; currentUserDisplayName = null
        activeWorklogId = null; activeWorklogStarted = null; activeWorklogBaseSeconds = 0
        accumulatedSeconds.set(0)
        publish()
        if (stoppedIssue.isNotBlank()) {
            project.messageBus.syncPublisher(TrackingTopics.TRACKING_STOPPED).onTrackingStopped(stoppedIssue)
        }
    }

    private fun flushTick() {
        if (!running.get()) return
        // Guard against overlapping flushes if Jira is slow
        if (!flushing.compareAndSet(false, true)) return
        try {
            val issue = currentIssueKey ?: return
            val intervalMin = (service<JiraProfilesState>().activeProfile()?.updateIntervalMinutes ?: 5).coerceIn(1, 60)
            val seconds = intervalMin * 60
            accumulatedSeconds.addAndGet(seconds)

            val jira = service<JiraService>().apiFromSettings()
            val started = OffsetDateTime.now(ZoneId.systemDefault())

            if (currentUserAccountId.isNullOrBlank() && currentUserDisplayName.isNullOrBlank()) {
                val me = jira.getMyself()
                currentUserAccountId = me.accountId; currentUserKey = me.key
                currentUserName = me.name; currentUserEmail = me.email
                currentUserDisplayName = me.displayName
            }

            val totalSeconds = activeWorklogBaseSeconds + accumulatedSeconds.get()

            if (!activeWorklogId.isNullOrBlank()) {
                jira.updateWorklog(
                    issueKey = issue,
                    worklogId = activeWorklogId!!,
                    started = activeWorklogStarted ?: started,
                    timeSpentSeconds = totalSeconds,
                    comment = null
                )
            } else {
                val newId = jira.addWorklog(
                    issueKey = issue,
                    started = activeWorklogStarted ?: started,
                    timeSpentSeconds = totalSeconds,
                    comment = null
                )
                activeWorklogId = newId.ifBlank { null }
                if (activeWorklogStarted == null) activeWorklogStarted = started
                if (activeWorklogId.isNullOrBlank()) {
                    val linked = findTodayWorklogForCurrentUser(jira, issue)
                    activeWorklogId = linked?.id
                    if (activeWorklogStarted == null) activeWorklogStarted = linked?.started ?: started
                    activeWorklogBaseSeconds = linked?.timeSpentSeconds ?: activeWorklogBaseSeconds
                }
            }
            todayByIssueSeconds[issue] = (todayByIssueSeconds[issue] ?: 0) + seconds
            lastFlushAt = started
            publish()
        } catch (t: Throwable) {
            notify("Worklog error", t.message ?: "unknown error")
        } finally {
            flushing.set(false)
        }
    }

    private fun findTodayWorklogForCurrentUser(jira: JiraApi, issueKey: String): JiraWorklog? {
        val today = LocalDate.now(ZoneId.systemDefault())
        val worklogs = jira.getWorklogs(issueKey, maxResults = 500)
        val todayLogs = worklogs.filter { it.started?.toLocalDate() == today }

        // Match by any known identity field
        val mine = todayLogs.filter { wl ->
            (!currentUserAccountId.isNullOrBlank() && wl.authorAccountId == currentUserAccountId) ||
            (!currentUserKey.isNullOrBlank() && wl.authorKey == currentUserKey) ||
            (!currentUserName.isNullOrBlank() && wl.authorName == currentUserName) ||
            (!currentUserEmail.isNullOrBlank() && wl.authorEmail == currentUserEmail) ||
            (!currentUserDisplayName.isNullOrBlank() && wl.authorDisplayName == currentUserDisplayName)
        }
        if (mine.isNotEmpty()) return mine.maxByOrNull { it.started ?: OffsetDateTime.MIN }

        // Only fall back to "single record" if we have NO identity info at all
        if (currentUserAccountId.isNullOrBlank() && currentUserKey.isNullOrBlank() &&
            currentUserName.isNullOrBlank() && currentUserEmail.isNullOrBlank() &&
            currentUserDisplayName.isNullOrBlank() && todayLogs.size == 1
        ) return todayLogs.first()

        return null
    }

    private fun notify(title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("YeDu Jira Tracker")
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun snapshot(): TrackingSnapshot {
        val issue = currentIssueKey
        val liveExtra = if (running.get()) {
            val anchor = lastFlushAt ?: startedAtLocal
            if (anchor != null) {
                (OffsetDateTime.now(ZoneId.systemDefault()).toEpochSecond() - anchor.toEpochSecond())
                    .toInt().coerceAtLeast(0)
            } else 0
        } else 0

        val currentTracked = if (!issue.isNullOrBlank()) {
            (todayByIssueSeconds[issue] ?: 0) + if (running.get()) liveExtra else 0
        } else 0

        val todayMap = LinkedHashMap(todayByIssueSeconds)
        if (!issue.isNullOrBlank() && running.get()) {
            todayMap[issue] = (todayMap[issue] ?: 0) + liveExtra
        }

        return TrackingSnapshot(
            running = running.get(),
            activeIssueKey = activeIssueKey ?: currentIssueKey,
            currentIssueTrackedSeconds = currentTracked,
            todayByIssueSeconds = todayMap
        )
    }

    private fun publish() {
        project.messageBus.syncPublisher(TrackingTopics.TRACKING_UPDATES).onTrackingUpdated(snapshot())
    }
}
