package com.mayze.jiratimetracker.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/** Circular avatar showing initials of the given name */
class AvatarLabel(name: String, private val inverted: Boolean = false) : JComponent() {

    private val initials: String
    private val bgColor: Color

    init {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        initials = when {
            parts.size >= 2 -> "${parts[0][0]}${parts[1][0]}".uppercase()
            parts.size == 1 && parts[0].isNotEmpty() -> parts[0][0].uppercaseChar().toString()
            else -> "?"
        }
        bgColor = PALETTE[Math.abs(name.hashCode()) % PALETTE.size]
        preferredSize = Dimension(32, 32)
        minimumSize = Dimension(32, 32)
        maximumSize = Dimension(32, 32)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val size = minOf(width, height)
        val x = (width - size) / 2
        val y = (height - size) / 2

        g2.color = if (inverted) Color(0xFFFFFF, false).also { } else bgColor
        g2.fillOval(x, y, size, size)

        g2.color = Color.WHITE
        g2.font = Font(font.name, Font.BOLD, (size * 0.38).toInt())
        val fm = g2.fontMetrics
        val tx = x + (size - fm.stringWidth(initials)) / 2
        val ty = y + (size + fm.ascent - fm.descent) / 2
        g2.drawString(initials, tx, ty)
    }

    companion object {
        private val PALETTE = arrayOf(
            Color(0x1565C0), Color(0x6A1B9A), Color(0xAD1457),
            Color(0xC62828), Color(0xE65100), Color(0x2E7D32),
            Color(0x00695C), Color(0x0277BD), Color(0x4527A0),
            Color(0x558B2F)
        )
    }
}
