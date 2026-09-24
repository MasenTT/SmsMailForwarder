package cn.smsmail.core

import org.junit.Assert.*
import org.junit.Test

class RoutingTest {
    @Test fun firstMatchingRuleWinsAndDeduplicatesItsRecipients() {
        val result = Router.route("业务告警：余额不足", listOf(
            Rule("1", "告警", "告警", listOf("a@example.com", "b@example.com", "A@example.com")),
            Rule("2", "余额", "余额", listOf("A@example.com"))), listOf("default@example.com"))
        assertEquals(listOf("a@example.com", "b@example.com"), result.recipients)
        assertEquals(listOf("告警"), result.ruleNames)
        assertEquals("1", result.matchedRule?.id)
    }

    @Test fun disabledRulesAreSkippedBeforeFirstMatch() {
        val result = Router.route("验证码123456", listOf(
            Rule("disabled", "已关闭", ".*", listOf("off@example.com"), false),
            Rule("first", "验证码", "验证码", listOf("otp@example.com")),
            Rule("later", "所有", ".*", listOf("all@example.com"))), listOf("default@example.com"))
        assertEquals(listOf("otp@example.com"), result.recipients)
        assertEquals("first", result.matchedRule?.id)
        assertEquals(listOf("验证码"), result.ruleNames)
    }
    @Test fun fallbackAndDisabled() {
        val result = Router.route("验证码123456", listOf(Rule("1", "关闭", ".*", listOf("x@example.com"), false)), listOf("default@example.com"))
        assertEquals(listOf("default@example.com"), result.recipients)
        assertTrue(result.ruleNames.isEmpty())
    }
    @Test fun emptyRulesUseDefaults() { assertEquals(listOf("a@example.com"), Router.route("text", emptyList(), listOf("a@example.com")).recipients) }
    @Test fun multilineAndAnchors() {
        assertTrue(Router.matches("(?s)^通知.*完成$", "通知\n业务完成"))
        assertFalse(Router.matches("^通知$", "业务通知"))
        assertTrue(Router.matches("通知", "业务通知"))
    }
    @Test fun invalidAndUnsupportedRegex() {
        assertNotNull(Router.regexError("["))
        assertNotNull(Router.regexError("(?<=a)b"))
        assertNotNull(Router.regexError(""))
        assertNull(Router.regexError("验证码[0-9]+"))
    }
    @Test fun parseRecipients() {
        assertEquals(listOf("a@example.com", "b@example.com"), Addresses.parse("a@example.com；b@example.com\nA@example.com"))
    }
    @Test fun invalidAddressesRejected() {
        listOf("", "x", "a@", "name <a@example.com>", "a@example.com\r\nBcc:x").forEach {
            assertThrows(IllegalArgumentException::class.java) { Addresses.parse(it) }
        }
    }
    @Test fun secretsAreNotExposedInConfigString() {
        assertFalse(MailConfig("smtp.qq.com", 465, "SSL", "private@qq.com", "secret-token").toString().contains("secret-token"))
        assertFalse(MailConfig("smtp.qq.com", 465, "SSL", "private@qq.com", "secret-token").toString().contains("private@qq.com"))
        assertNotNull(Router.regexError("a".repeat(4097)))
    }
    @Test fun configValidation() {
        assertNull(MailConfig("smtp.qq.com", 465, "SSL", "a@qq.com", "code").error())
        assertNotNull(MailConfig("bad host", 465, "SSL", "a@qq.com", "code").error())
        assertNotNull(MailConfig("smtp.qq.com", 0, "SSL", "a@qq.com", "code").error())
        assertNotNull(MailConfig("smtp.qq.com", 465, "PLAIN", "a@qq.com", "code").error())
        assertNotNull(MailConfig("smtp.qq.com", 465, "SSL", "a@qq.com", "").error())
        assertNotNull(MailConfig("smtp.qq.com", 465, "SSL", "bad", "code").error())
    }
    @Test fun retriesStopAfterFiveAttempts() {
        assertEquals(DeliveryState.PENDING, DeliveryPolicy.afterFailure(FailureKind.TRANSIENT, 1))
        assertEquals(DeliveryState.FAILED, DeliveryPolicy.afterFailure(FailureKind.TRANSIENT, 5))
        assertEquals(DeliveryState.CONFIG_ERROR, DeliveryPolicy.afterFailure(FailureKind.CONFIG, 1))
        assertEquals(DeliveryState.UNCERTAIN, DeliveryPolicy.afterFailure(FailureKind.UNCERTAIN, 1))
        assertEquals(DeliveryState.FAILED, DeliveryPolicy.afterFailure(FailureKind.PERMANENT, 1))
    }
    @Test fun stableFingerprintSeparatesDifferentEvents() {
        assertEquals(Fingerprints.sms("10086", 123, "sim1", "通知"), Fingerprints.sms("10086", 123, "sim1", "通知"))
        assertNotEquals(Fingerprints.sms("10086", 123, "sim1", "通知"), Fingerprints.sms("10086", 124, "sim1", "通知"))
        assertNotEquals(Fingerprints.sms("10086", 123, "sim1", "通知"), Fingerprints.sms("10086", 123, "sim2", "通知"))
    }

    @Test fun quickRulePresetsMatchExpectedMessages() {
        assertTrue(Router.matches(RulePresets.expression(RuleType.ALL), "任意短信\n都匹配"))
        assertTrue(Router.matches(RulePresets.expression(RuleType.OTP), "您的验证码为 123456"))
        assertTrue(Router.matches(RulePresets.expression(RuleType.OTP), "Your OTP is 123456"))
        assertFalse(Router.matches(RulePresets.expression(RuleType.OTP), "明天 123456 号订单发货"))
        assertTrue(Router.matches(RulePresets.expression(RuleType.AMOUNT), "支付成功 128.50 元"))
        assertTrue(Router.matches(RulePresets.expression(RuleType.AMOUNT), "余额人民币 1,234.56"))
        assertTrue(Router.matches(RulePresets.expression(RuleType.AMOUNT), "退款 ￥20"))
        assertFalse(Router.matches(RulePresets.expression(RuleType.AMOUNT), "金额待确认"))
        assertFalse(Router.matches(RulePresets.expression(RuleType.AMOUNT), "联系电话 13800138000"))
    }

    @Test fun customRuleKeepsUserExpression() {
        assertEquals("告警|故障", RulePresets.expression(RuleType.CUSTOM, "告警|故障"))
        assertEquals("", RulePresets.expression(RuleType.CUSTOM, null))
        assertNull(Router.regexError(RulePresets.expression(RuleType.ALL)))
        assertNull(Router.regexError(RulePresets.expression(RuleType.OTP)))
        assertNull(Router.regexError(RulePresets.expression(RuleType.AMOUNT)))
    }

    @Test fun contactValuesValidateAndNormalize() {
        val input = ContactValues.validate(" 张三 ", "Alice@Example.com ", "负责人")
        assertEquals("张三", input.name)
        assertEquals("Alice@Example.com", input.email)
        assertEquals("alice@example.com", input.normalizedEmail)
        assertThrows(IllegalArgumentException::class.java) { ContactValues.validate("", "a@example.com", "") }
        assertThrows(IllegalArgumentException::class.java) { ContactValues.validate("a", "a@example.com,b@example.com", "") }
        assertThrows(IllegalArgumentException::class.java) { ContactValues.validate("a", "a@example.com", "x".repeat(201)) }
    }
}
