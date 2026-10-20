package app.hapi.companion.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.awt.BasicStroke
import ru.noties.jlatexmath.awt.Font
import ru.noties.jlatexmath.awt.geom.Line2D
import ru.noties.jlatexmath.awt.geom.Rectangle2D
import ru.noties.jlatexmath.awt.geom.RoundRectangle2D
import ru.noties.jlatexmath.awt.Color as MathColor

class MathDrawableTest {
    @Test fun framesAndLinesDoNotChangeSubsequentGlyphPixels() {
        val shapes: List<(FilledMathGraphics) -> Unit> = listOf(
            { it.draw(Rectangle2D.Float(-40f, -40f, 10f, 10f)) },
            { it.draw(Line2D.Float(-40f, -40f, -30f, -30f)) },
            { it.draw(RoundRectangle2D.Float(-40f, -40f, 10f, 10f, 2f, 2f)) },
            { it.drawArc(-40, -40, 10, 10, 0, 180) },
        )
        for (color in listOf(Color.BLACK, 0x99cc3344.toInt())) {
            for (typeface in listOf(Typeface.SERIF, Typeface.MONOSPACE)) {
                fun render(shape: ((FilledMathGraphics) -> Unit)?): Bitmap {
                    val bitmap = Bitmap.createBitmap(220, 140, Bitmap.Config.ARGB_8888)
                    val graphics = FilledMathGraphics()
                    graphics.setCanvas(Canvas(bitmap))
                    graphics.color = MathColor(color)
                    graphics.font = Font.createFont(typeface, 64f)
                    graphics.stroke = BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
                    shape?.invoke(graphics)
                    // Exercise substring offsets and a transformed canvas too.
                    graphics.translate(3.0, 4.0)
                    graphics.drawChars("_MW)_".toCharArray(), 1, 3, 10, 100)
                    return bitmap
                }
                val expected = pixels(render(null))
                assertTrue(expected.any { Color.alpha(it) > 0 })
                for ((index, shape) in shapes.withIndex()) {
                    assertArrayEquals("Shape $index changed filled glyphs", expected, pixels(render(shape)))
                }
            }
        }
    }

    @Test fun ordinaryMathKeepsItsFontsColorsInsetsAndCanvasTransform() {
        for (latex in listOf(
            "\\sum_{k=1}^{99}(X_k+Y_k)",
            "\\sqrt{\\alpha^2+\\beta^2}",
            "\\textcolor{red}{x}+\\textcolor{blue}{y}",
            "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",
        )) {
            val original = compile(latex)
            val fixed = MathDrawable(original.icon())
            assertEquals(original.intrinsicWidth, fixed.intrinsicWidth)
            assertEquals(original.intrinsicHeight, fixed.intrinsicHeight)
            val expected = Bitmap.createBitmap(fixed.intrinsicWidth + 40, fixed.intrinsicHeight + 40, Bitmap.Config.ARGB_8888)
            val actual = expected.copy(Bitmap.Config.ARGB_8888, true)
            val oldCanvas = Canvas(expected).apply { translate(10f, 12f) }
            val newCanvas = Canvas(actual).apply { translate(10f, 12f) }
            original.draw(oldCanvas)
            fixed.draw(newCanvas)
            assertArrayEquals(latex, pixels(expected), pixels(actual))
            // The cached icon can draw repeatedly, without retaining a transform.
            val repeated = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
            fixed.draw(Canvas(repeated).apply { translate(10f, 12f) })
            assertArrayEquals(latex, pixels(actual), pixels(repeated))
            // draw() must preserve the caller's save stack.
            assertEquals(1, newCanvas.saveCount)
        }
    }

    @Test fun boxedScreenshotEquationsDrawFilledAndRetainTheirFrame() {
        val formulas = listOf(
            "\\boxed{N\\overset d= Z+\\sum_{k=1}^{99}X_k,\\qquad Z\\sim\\operatorname{NegBin}(99,1/100)}",
            "\\boxed{G_N(z)=\\mathbb E[z^N]=\\left(\\frac{z}{100-99z}\\right)^{99}\\prod_{k=1}^{99}\\frac{kz}{100-(100-k)z}}",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((index, latex) in formulas.withIndex()) {
            val original = compile(latex)
            val fixed = MathDrawable(original.icon())
            val before = Bitmap.createBitmap(fixed.intrinsicWidth, fixed.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val after = before.copy(Bitmap.Config.ARGB_8888, true)
            original.draw(Canvas(before))
            fixed.draw(Canvas(after))
            assertTrue(pixels(after).any { Color.alpha(it) > 0 })
            assertFalse("Boxed glyphs still use the legacy stroke style", pixels(before).contentEquals(pixels(after)))
            // Border pixels outside the text area retain the original geometry.
            val inkColumns = (0 until before.width).filter { x ->
                (0 until before.height).any { y -> Color.alpha(before.getPixel(x, y)) > 0 }
            }
            for (x in listOf(inkColumns.first(), inkColumns.last())) for (y in 0 until after.height) {
                assertEquals(before.getPixel(x, y), after.getPixel(x, y))
            }
            for ((label, bitmap) in listOf("before" to before, "after" to after)) {
                File(context.filesDir, "boxed-$index-$label.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    private fun compile(latex: String) = JLatexMathDrawable.builder("\\displaystyle $latex")
        .textSize(48f).color(Color.BLACK).padding(1).build()

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
