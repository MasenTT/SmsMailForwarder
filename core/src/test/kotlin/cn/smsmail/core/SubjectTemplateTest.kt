package cn.smsmail.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SubjectTemplateTest {
    @Test fun expandsAllSupportedMessageVariables() {
        val values = mapOf(
            "{来源号码}" to "10690000",
            "{接收时间}" to "09-23 19:14:38",
            "{消息时间}" to "09-23 19:14:36",
            "{SIM信息}" to "订阅 2",
            "{匹配规则}" to "验证码",
            "{消息类型}" to "短信",
            "{彩信主题}" to "账单"
        )
        assertEquals(
            "短信 10690000 · 验证码 · 账单",
            SubjectTemplate.render("{消息类型} {来源号码} · {匹配规则} · {彩信主题}", values)
        )
        assertEquals("订阅 2", SubjectTemplate.render("{SIM信息}", values))
    }

    @Test fun emptyVariablesBecomeEmptyAndUnknownTokensStayLiteral() {
        assertEquals("验证码 ·", SubjectTemplate.render("验证码 · {彩信主题}", mapOf("{彩信主题}" to "")))
        assertEquals("", SubjectTemplate.render("{消息内容}", mapOf("{消息内容}" to "")))
        assertEquals("{未知字段}", SubjectTemplate.render("{未知字段}", emptyMap()))
        assertEquals("", SubjectTemplate.render("  ", emptyMap()))
    }

    @Test fun messageContentVariableFlattensLineBreaksAndRepeatedWhitespace() {
        assertEquals(
            "验证码：123456 已到账 50元",
            SubjectTemplate.render("{消息内容}", mapOf("{消息内容}" to "验证码：123456\r\n已到账    50元"))
        )
    }

    @Test fun messageContentVariableTruncatesAtFiftyUnicodeCodePoints() {
        val content = "😀" + "账".repeat(60)
        val rendered = SubjectTemplate.render("{消息内容}", mapOf("{消息内容}" to content))

        assertEquals("😀" + "账".repeat(48) + "…", rendered)
        assertEquals(50, rendered.codePointCount(0, rendered.length))
    }
}
