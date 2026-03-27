package com.mayze.jiratimetracker.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.mayze.jiratimetracker.util.formatSecondsShort
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** One entry in the history list — either a day row or a week separator */
sealed class HistoryEntry {
    data class DayEntry(
        val date: LocalDate,
        val totalSeconds: Int,
        val issues: List<Pair<String, Int>> // issueKey -> seconds
    ) : HistoryEntry()

    data class WeekSeparator(val weekTotal: Int) : HistoryEntry()
    data class GrandTotal(val total: Int, val days: Int) : HistoryEntry()
}

class HistoryListCellRenderer : ListCellRenderer<HistoryEntry> {
    override fun getListCellRendererComponent(
        list: JList<out HistoryEntry>, value: HistoryEntry?,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        return when (value) {
            is HistoryEntry.DayEntry -> DayPanel(value, isSelected, list)
            is HistoryEntry.WeekSeparator -> SeparatorPanel("Week total: ${formatSecondsShort(value.weekTotal)}")
            is HistoryEntry.GrandTotal -> GrandTotalPanel(value)
            else -> JPanel()
        }
    }
}

private class DayPanel(entry: HistoryEntry.DayEntry, isSelected: Boolean, list: JList<*>) : JPanel(BorderLayout(8, 0)) {
    init {
        val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, EEE")
        isOpaque = true
        background = if (isSelected) list.selectionBackground
        else JBColor(Color(0xF8F8F8), Color(0x2B2D30))
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(Color(0xE8E8E8), Color(0x3C3F41)), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 12, 8, 12)
        )

        // Left: date
        val dateLabel = JLabel(entry.date.format(dateFmt)).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = if (isSelected) list.selectionForeground
            else JBColor(Color(0x1A1A1A), Color(0xDDDDDD))
            preferredSize = Dimension(180, 20)
        }

        // Center: time + bar
        val timeStr = formatSecondsShort(entry.totalSeconds)
        val barLen = (entry.totalSeconds / 1800).coerceIn(1, 20)
        val barPanel = BarPanel(barLen, entry.totalSeconds >= 28800) // 8h = green
        val timeLabel = JLabel(timeStr).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = when {
                entry.totalSeconds >= 28800 -> JBColor(Color(0x1B7F3A), Color(0x4CAF50))
                entry.totalSeconds >= 14400 -> JBColor(Color(0x0052CC), Color(0x42A5F5))
                else -> JBColor(Color(0xF9A825), Color(0xF9A825))
            }
            preferredSize = Dimension(60, 20)
        }

        val centerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(timeLabel)
            add(barPanel)
        }

        // Right: issue breakdown
        val issuesText = entry.issues.joinToString("  ") { "${it.first} ${formatSecondsShort(it.second)}" }
        val issuesLabel = JLabel(issuesText).apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = if (isSelected) list.selectionForeground
            else JBColor(Color(0x888888), Color(0x888888))
        }

        add(dateLabel, BorderLayout.WEST)
        add(centerPanel, BorderLayout.CENTER)
        add(issuesLabel, BorderLayout.EAST)
    }
}

private class BarPanel(private val blocks: Int, private val isGood: Boolean) : JPanel() {
    init {
        preferredSize = Dimension(blocks * 8 + 4, 14)
        minimumSize = preferredSize
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val color = if (isGood) Color(0x1B7F3A) else Color(0x0052CC)
        g2.color = color
        g2.fillRoundRect(0, 2, blocks * 8, 10, 4, 4)
    }
}

private class SeparatorPanel(text: String) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 12)
        add(JLabel(text).apply {
            font = font.deriveFont(Font.ITALIC, 11f)
            foreground = JBColor(Color(0x888888), Color(0x666666))
            horizontalAlignment = JLabel.CENTER
        }, BorderLayout.CENTER)
    }
}

private class GrandTotalPanel(entry: HistoryEntry.GrandTotal) : JPanel(BorderLayout()) {
    init {
        isOpaque = true
        background = JBColor(Color(0xE8F5E9), Color(0x1B3A1B))
        border = JBUI.Borders.empty(10, 12)
        add(JLabel("Total: ${formatSecondsShort(entry.total)}  (${entry.days} days)").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = JBColor(Color(0x1B7F3A), Color(0x4CAF50))
            horizontalAlignment = JLabel.CENTER
        }, BorderLayout.CENTER)
    }
}
