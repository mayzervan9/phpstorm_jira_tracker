package com.mayze.jiratimetracker.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.mayze.jiratimetracker.jira.JiraActivity
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.time.format.DateTimeFormatter
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class ActivityListCellRenderer : ListCellRenderer<JiraActivity> {
    override fun getListCellRendererComponent(
        list: JList<out JiraActivity>, value: JiraActivity?,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val a = value ?: return JPanel()
        return ActivityPanel(a, isSelected, list)
    }
}

private class ActivityPanel(
    a: JiraActivity, isSelected: Boolean, list: JList<*>
) : JPanel(BorderLayout(8, 0)) {
    init {
        isOpaque = true
        background = if (isSelected) list.selectionBackground
        else JBColor(Color(0xF8F8F8), Color(0x2B2D30))
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(Color(0xE8E8E8), Color(0x3C3F41)), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 10, 6, 10)
        )

        val typeIcon = when (a.type) {
            "comment"  -> "💬"
            "status"   -> "🔄"
            "assignee" -> "👤"
            "worklog"  -> "⏱"
            else       -> "📝"
        }
        val typeColor = when (a.type) {
            "comment"  -> JBColor(Color(0x0052CC), Color(0x42A5F5))
            "status"   -> JBColor(Color(0x1B7F3A), Color(0x4CAF50))
            "assignee" -> JBColor(Color(0x6A1B9A), Color(0xAB47BC))
            else       -> JBColor(Color(0x888888), Color(0x888888))
        }

        val iconLabel = JLabel(typeIcon).apply { preferredSize = Dimension(24, 24); font = font.deriveFont(16f) }

        val time = a.timestamp?.toLocalDateTime()?.format(DateTimeFormatter.ofPattern("dd MMM HH:mm")) ?: ""
        val fg = if (isSelected) list.selectionForeground else JBColor(Color(0x1A1A1A), Color(0xDDDDDD))
        val dimFg = if (isSelected) list.selectionForeground else JBColor(Color(0x888888), Color(0x888888))

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JLabel(a.issueKey).apply { font = font.deriveFont(Font.BOLD, 12f); foreground = typeColor })
            add(JLabel(a.authorName).apply { font = font.deriveFont(Font.PLAIN, 11f); foreground = fg })
            add(JLabel(time).apply { font = font.deriveFont(Font.PLAIN, 10f); foreground = dimFg })
        }

        val detail = JLabel(a.detail).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = fg
            border = JBUI.Borders.emptyLeft(2)
        }

        val content = JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(detail, BorderLayout.CENTER)
        }

        add(iconLabel, BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
    }
}
