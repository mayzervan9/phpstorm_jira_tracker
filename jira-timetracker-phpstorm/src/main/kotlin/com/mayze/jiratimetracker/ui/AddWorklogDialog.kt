package com.mayze.jiratimetracker.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Dialog for manually adding a worklog entry.
 * Time format: "1h 30m", "90m", "2h", "3600" (seconds)
 * Date format: "2026-03-27" or empty = today
 */
class AddWorklogDialog(project: Project?, issueKey: String) : DialogWrapper(project, true) {

    private val timeField = JBTextField("1h").apply { preferredSize = Dimension(120, 28) }
    private val dateField = JBTextField(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)).apply {
        preferredSize = Dimension(120, 28)
    }
    private val commentField = JBTextField().apply { preferredSize = Dimension(280, 28) }

    var timeSpentSeconds: Int = 0
        private set
    var started: OffsetDateTime = OffsetDateTime.now()
        private set
    var comment: String = ""
        private set

    init {
        title = "Add Worklog — $issueKey"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Time spent:", timeField, 1, false)
            .addLabeledComponent("Date (yyyy-MM-dd):", dateField, 1, false)
            .addLabeledComponent("Comment (optional):", commentField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(4)
            preferredSize = Dimension(360, 180)
            add(JLabel("<html><small>Examples: <b>1h 30m</b>, <b>90m</b>, <b>2h</b>, <b>3600</b></small></html>"),
                BorderLayout.NORTH)
            add(form, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        val secs = parseTime(timeField.text.trim())
        if (secs <= 0) return ValidationInfo("Invalid time format. Use: 1h 30m, 90m, 2h, 3600", timeField)
        try {
            LocalDate.parse(dateField.text.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            return ValidationInfo("Invalid date. Use yyyy-MM-dd", dateField)
        }
        return null
    }

    override fun doOKAction() {
        timeSpentSeconds = parseTime(timeField.text.trim())
        val date = LocalDate.parse(dateField.text.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
        started = OffsetDateTime.of(date, LocalTime.of(9, 0), ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()))
        comment = commentField.text.trim()
        super.doOKAction()
    }

    companion object {
        fun parseTime(input: String): Int {
            if (input.isBlank()) return 0
            // pure seconds
            input.toIntOrNull()?.let { return it }
            var total = 0
            val hMatch = Regex("""(\d+)\s*h""").find(input)
            val mMatch = Regex("""(\d+)\s*m""").find(input)
            val sMatch = Regex("""(\d+)\s*s""").find(input)
            hMatch?.groupValues?.get(1)?.toIntOrNull()?.let { total += it * 3600 }
            mMatch?.groupValues?.get(1)?.toIntOrNull()?.let { total += it * 60 }
            sMatch?.groupValues?.get(1)?.toIntOrNull()?.let { total += it }
            return total
        }
    }
}
