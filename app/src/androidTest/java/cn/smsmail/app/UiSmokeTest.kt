package cn.smsmail.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import kotlinx.coroutines.runBlocking
import cn.smsmail.core.MailConfig
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class UiSmokeTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECEIVE_SMS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun reset() {
        val app = ApplicationProvider.getApplicationContext<MailApp>()
        app.settings.enabled = false
        androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
        app.database.clearAllTables()
        app.getSharedPreferences("private_settings", 0).edit().clear().commit()
        app.settings.changed()
        compose.waitForIdle()
        compose.enableAccessibilityChecks()
    }
    @Test fun navigateAndValidateRuleEditor() {
        val app = ApplicationProvider.getApplicationContext<MailApp>()
        runBlocking { app.repository.saveContact(null, "测试收件人", "a@example.com", "规则测试") }
        compose.waitUntil(5000) { runBlocking { app.database.dao().contacts().isNotEmpty() } }
        compose.onNodeWithText("规则", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("新增规则").performClick()
        compose.onNodeWithText("规则名称").performTextInput("测试告警")
        compose.onNodeWithText("短信正文正则").performTextInput("告警")
        compose.onNode(hasClickAction() and hasAnyDescendant(hasText("选择收件联系人")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5000) { compose.onAllNodesWithText("a@example.com", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("a@example.com", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("确认选择（1）").performClick()
        compose.onNodeWithText("输入样例短信").performTextInput("业务告警")
        compose.onNode(hasClickAction() and hasAnyDescendant(hasText("测试规则")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("✓ 匹配成功，将使用所选联系人").assertExists()
        compose.onNodeWithText("保存规则").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测试告警").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("启用规则 测试告警").performClick()
        compose.onNodeWithContentDescription("启用规则 测试告警").assertIsOff()
        compose.onNodeWithText("编辑与测试").performClick()
        compose.onNodeWithText("规则名称").performTextReplacement("更新告警")
        compose.onNodeWithText("保存规则").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("更新告警").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("创建第一条规则").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("记录", useUnmergedTree = true).performClick()
        compose.onNodeWithText("暂无记录").assertExists()
    }
    @Test fun configureMailAndDefaultRecipients() {
        val app = ApplicationProvider.getApplicationContext<MailApp>()
        runBlocking { app.repository.saveContact(null, "默认收件人", "a@example.com", "默认") }
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("发件邮箱").performClick()
        compose.onNodeWithText("保存发件配置").performScrollTo().performClick()
        compose.onNodeWithText("请填写有效的发件邮箱").assertExists()
        compose.onNodeWithText("163 邮箱").performScrollTo().performClick()
        compose.onNodeWithText("smtp.163.com").assertExists()
        compose.onNodeWithText("STARTTLS").performScrollTo().performClick()
        compose.onNodeWithText("587").assertExists()
        compose.onNodeWithText("SSL / TLS").performClick()
        compose.onNodeWithText("SMTP 服务器").performScrollTo().performTextReplacement("localhost")
        compose.onNodeWithText("端口").performTextReplacement("1")
        compose.onNodeWithText("发件邮箱地址").performTextInput("test@163.com")
        compose.onNodeWithText("SMTP 授权码").performTextInput("fake-test-only")
        compose.onNodeWithText("保存发件配置").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("发件配置已保存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("默认收件人").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("a@example.com", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("a@example.com", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("确认选择（1）").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("默认收件人（a@example.com）").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("发送测试邮件").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("a@example.com", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("a@example.com", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("确认选择（1）").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("连接失败，请检查网络、SMTP 地址、端口和 TLS 设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("知道了").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("清空已完成记录"))
        compose.onNodeWithText("清空已完成记录").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("清空已完成记录"))
        compose.onNodeWithText("清空已完成记录").performClick()
        compose.onNodeWithText("确认").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("打开系统应用设置"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("申请短信权限"))
        compose.onNodeWithText("申请短信权限").performClick()
        compose.onNodeWithText("短信权限：已开启").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("打开系统应用设置"))
        compose.onNodeWithText("打开系统应用设置").performClick()
    }
    @Test fun mmsPermissionsAndSystemDownloadDependencyAreExplained() {
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("彩信通知权限：", substring = true))
        compose.onNode(hasText("彩信通知权限：", substring = true)).assertExists()
        compose.onNode(hasText("短信库读取权限：", substring = true)).assertExists()
        compose.onNode(hasText("完整彩信由当前默认短信应用下载后读取。", substring = true)).assertExists()
        compose.onNode(hasText("此应用不会更改默认短信应用", substring = true)).assertExists()
    }
    @Test fun manageContactsAndQuickRulePresets() {
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("联系人管理").performClick()
        compose.onNodeWithText("新增联系人").performClick()
        compose.onNodeWithText("姓名").performTextInput("验证码联系人")
        compose.onNodeWithText("邮箱").performTextInput("otp@example.com")
        compose.onNodeWithText("备注（可选）").performTextInput("验证码")
        compose.onNode(hasClickAction() and hasAnyDescendant(hasText("保存联系人")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5000) { compose.onAllNodesWithText("otp@example.com").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("规则", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("新增规则").performClick()
        compose.onNodeWithText("规则名称").performTextInput("验证码快捷规则")
        compose.onNodeWithText("验证码").performClick()
        compose.onNode(hasClickAction() and hasAnyDescendant(hasText("选择收件联系人")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5000) { compose.onAllNodesWithText("otp@example.com", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("otp@example.com", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("确认选择（1）").performClick()
        compose.onNodeWithText("输入样例短信").performTextInput("您的验证码为 123456")
        compose.onNode(hasClickAction() and hasAnyDescendant(hasText("测试规则")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("✓ 匹配成功，将使用所选联系人").assertExists()
        compose.onNodeWithText("保存规则").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("验证码快捷规则").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("验证码 · 验证码、校验码、动态口令或 OTP").assertExists()
    }
    @Test fun detailsAndRetryOnlyFailedRecipient() {
        val app = ApplicationProvider.getApplicationContext<MailApp>()
        runBlocking {
            app.settings.saveMail(MailConfig("localhost", 1, "SSL", "sender@example.com", "test-only"))
            app.settings.defaults = "a@example.com"
            app.repository.setEnabled(true)
            app.database.dao().insertEvent(EventRow("ui-event", "ui-event", app.crypto.encrypt("10086"), app.crypto.encrypt("详情正文测试"), 1000L, 1000L, "SIM 1", "演示规则"))
            app.database.dao().insertDeliveries(listOf(DeliveryRow("ui-sent", "ui-event", "done@example.com", "SENT"), DeliveryRow("ui-failed", "ui-event", "retry@example.com", "FAILED")))
        }
        compose.onNodeWithText("记录", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("10086").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("10086").performClick()
        compose.onNodeWithText("查看短信正文").performClick()
        compose.onNodeWithText("详情正文测试").assertExists()
        compose.onNodeWithText("收起短信正文").performClick()
        compose.onNodeWithText("重试此收件人").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("重试此收件人").performClick()
        compose.onNodeWithText("确认").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("重试此收件人").fetchSemanticsNodes().isEmpty() }
        runBlocking { org.junit.Assert.assertEquals("SENT", app.database.dao().delivery("ui-sent")!!.state) }
        compose.onNodeWithContentDescription("关闭").performClick()
    }
    @Test fun preventEnablingBeforeConfiguration() {
        compose.onNodeWithContentDescription("短信转发总开关").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("操作未完成").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithContentDescription("短信转发总开关").assertIsOff()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("查看全部"))
        compose.onNodeWithText("查看全部").performClick()
        compose.onNodeWithText("暂无记录").assertExists()
    }
}
