package app.hapi.companion.ui.markdown

import android.graphics.Canvas
import android.graphics.Paint
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.awt.AndroidGraphics2D

/** Math placeholders use the icon's intrinsic size, including its TeX insets. */
internal class MathDrawable(private val icon: TeXIcon) {
    val intrinsicWidth: Int get() = icon.iconWidth
    val intrinsicHeight: Int get() = icon.iconHeight
    private val graphics = FilledMathGraphics()

    fun draw(canvas: Canvas) {
        val save = canvas.save()
        try {
            graphics.setCanvas(canvas)
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            canvas.restoreToCount(save)
        }
    }
}

/** JLatexMath Android 0.2.0 shares a Paint between shapes and drawChars, but
 * drawChars never resets STROKE after a frame/line. Text must always be filled;
 * keep its Paint separate while retaining the library's fonts/colors/transforms. */
internal class FilledMathGraphics : AndroidGraphics2D() {
    private lateinit var textCanvas: Canvas
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun setCanvas(canvas: Canvas) {
        textCanvas = canvas
        super.setCanvas(canvas)
    }

    override fun drawChars(chars: CharArray, offset: Int, length: Int, x: Int, y: Int) {
        font?.let {
            textPaint.typeface = it.typeface()
            textPaint.textSize = it.size()
        }
        textPaint.color = color.colorInt
        textCanvas.drawText(chars, offset, length, x.toFloat(), y.toFloat(), textPaint)
    }
}
