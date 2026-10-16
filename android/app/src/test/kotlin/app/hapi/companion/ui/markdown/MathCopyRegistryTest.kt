package app.hapi.companion.ui.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MathCopyRegistryTest {
    @Test fun `selection restores exact multiline TeX between normal paragraphs`() {
        val registry = MathCopyRegistry("Alpha\n\n\\[\na + b\n\\]\n\nOmega")
        val source = "\\[\na + b\n\\]"
        val token = registry.tokenFor(source)!!
        assertEquals("Alpha\n$source\nOmega", registry.decode("Alpha\n$token\nOmega"))
        assertEquals(source, registry.decode(token))
    }

    @Test fun `existing private use text is never mistaken for a formula`() {
        val original = "Alpha \uE000 \uE001 \uE002 Omega"
        val registry = MathCopyRegistry(original)
        val token = registry.tokenFor("\\(x_i\\)")!!
        assertNotEquals("\uE000", token)
        assertEquals(original, registry.decode(original))
        assertEquals("$original \\(x_i\\)", registry.decode("$original $token"))
    }

    @Test fun `different formulas retain order and matching sources reuse their token`() {
        val registry = MathCopyRegistry("body")
        val one = registry.tokenFor("\$x\$")!!
        val two = registry.tokenFor("\$y\$")!!
        assertEquals(one, registry.tokenFor("\$x\$"))
        assertNotEquals(one, two)
        assertEquals("\$y\$ then \$x\$ then \$y\$", registry.decode("$two then $one then $two"))
    }
}
