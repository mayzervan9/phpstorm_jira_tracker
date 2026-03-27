package com.mayze.jiratimetracker.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.mayze.jiratimetracker.util.formatSecondsShort
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class SetEstimateDialog(
    project: Project?,
    issueKey: String,
    currentOriginal: Int?
) : DialogWrapper(project, true) {

    private val originalField = JBTextField(
        if (currentOriginal != null) formatSecondsShort(currentOriginal) else ""
    ).apply { preferredSize = Dimension(120, 28) }

    var originalEstimateSeconds: Int = 0
        private set

    init {
        title = "Set Estimate — $issueKey"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Original estimate:", originalField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(4)
            preferredSize = Dimension(320, 130)
            add(JLabel("<html><small>Examples: <b>1h 30m</b>, <b>90m</b>, <b>2h</b></small></html>"),
                BorderLayout.NORTH)
            add(form, BorderLayout.CENTER)
        }
    }

    override fun doValidate(): ValidationInfo? {
        val secs = AddWorklogDialog.parseTime(originalField.text.trim())
        if (secs <= 0) return ValidationInfo("Invalid time format", originalField)
        return null
    }

    override fun doOKAction() {
        originalEstimateSeconds = AddWorklogDialog.parseTime(originalField.text.trim())
        super.doOKAction()
    }
}
