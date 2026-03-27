package com.mayze.jiratimetracker.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.mayze.jiratimetracker.jira.JiraTransition
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class TransitionPickerDialog(
    project: Project?,
    private val issueKey: String,
    transitions: List<JiraTransition>,
    private val currentStatus: String? = null
) : DialogWrapper(project, true) {

    private val model = DefaultListModel<JiraTransition>().also { m ->
        // Put current status first
        val current = transitions.filter { it.name.equals(currentStatus, ignoreCase = true) }
        val rest = transitions.filter { !it.name.equals(currentStatus, ignoreCase = true) }
        current.forEach { m.addElement(it) }
        rest.forEach { m.addElement(it) }
    }
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = TransitionCellRenderer(currentStatus)
        selectedIndex = 0
    }

    val selectedTransition: JiraTransition?
        get() = list.selectedValue

    init {
        title = "Change Status — $issueKey"
        setOKButtonText("Apply")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val currentLabel = if (!currentStatus.isNullOrBlank())
            "Current status: $currentStatus"
        else
            "Only transitions allowed by your workflow are shown."

        val hint = JBLabel(currentLabel).apply { border = JBUI.Borders.emptyBottom(8) }

        return JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(360, 280)
            add(hint, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
    }

    override fun isOKActionEnabled(): Boolean = list.selectedValue != null

    private class TransitionCellRenderer(private val currentStatus: String?) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val t = value as? JiraTransition ?: return this
            border = JBUI.Borders.empty(6, 10)

            val dot = when (t.toStatusCategory) {
                "done"          -> "🟢"
                "indeterminate" -> "🔵"
                else            -> "⚪"
            }
            val isCurrent = !currentStatus.isNullOrBlank() && t.name.equals(currentStatus, ignoreCase = true)
            val marker = if (isCurrent) " (current)" else ""
            text = "$dot  ${t.name}$marker"
            font = if (isCurrent) font.deriveFont(Font.BOLD, 13f) else font.deriveFont(Font.PLAIN, 13f)

            if (!isSelected) {
                background = JBColor(Color(0xF8F8F8), Color(0x2B2D30))
                foreground = JBColor(Color(0x1A1A1A), Color(0xDDDDDD))
            }
            return this
        }
    }
}
