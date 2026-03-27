package com.mayze.jiratimetracker.ui

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.mayze.jiratimetracker.jira.JiraIssue
import java.awt.Color
import java.awt.Component
import javax.swing.JList
import javax.swing.ListCellRenderer

class JiraIssueRenderer : ListCellRenderer<JiraIssue> {
    override fun getListCellRendererComponent(
        list: JList<out JiraIssue>, value: JiraIssue?,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val c = SimpleColoredComponent()
        c.ipad = java.awt.Insets(4, 8, 4, 8)
        c.background = if (isSelected) list.selectionBackground else list.background
        c.foreground  = if (isSelected) list.selectionForeground else list.foreground
        c.isOpaque = true

        if (value != null) {
            // Priority indicator
            val prioritySymbol = when (value.priority?.lowercase()) {
                "highest", "critical" -> "⬆⬆"
                "high"                -> "⬆"
                "medium"              -> "▶"
                "low"                 -> "⬇"
                "lowest"              -> "⬇⬇"
                else                  -> null
            }
            val priorityColor = when (value.priority?.lowercase()) {
                "highest", "critical" -> JBColor(Color(0xC62828), Color(0xEF5350))
                "high"                -> JBColor(Color(0xE65100), Color(0xFF7043))
                "medium"              -> JBColor(Color(0xF9A825), Color(0xF9A825))
                "low"                 -> JBColor(Color(0x1565C0), Color(0x42A5F5))
                "lowest"              -> JBColor(Color(0x555555), Color(0x888888))
                else                  -> null
            }
            if (prioritySymbol != null && priorityColor != null) {
                c.append("$prioritySymbol ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, priorityColor))
            }

            c.append(value.key, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            c.append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Status badge
            if (!value.status.isNullOrBlank()) {
                val badgeColor = when (value.statusCategory) {
                    "done"          -> JBColor(Color(0x1B7F3A), Color(0x2E7D32))
                    "indeterminate" -> JBColor(Color(0x0052CC), Color(0x1565C0))
                    else            -> JBColor(Color(0x888888), Color(0x666666))
                }
                c.append("[${value.status}]",
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, badgeColor))
                c.append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }

            c.append(value.summary, SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // No-estimate warning
            if (value.originalEstimateSeconds == null) {
                c.append("  ⚠", SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_BOLD,
                    JBColor(Color(0xF9A825), Color(0xF9A825))
                ))
            }
        }
        return c
    }
}
