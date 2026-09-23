package cn.smsmail.app

import android.Manifest
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.smsmail.core.*
import kotlinx.coroutines.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SmsMailTheme { MailScreen(mailApp.repository) } }
    }
}

@Composable fun SmsMailTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFAFC6FF), background = Color(0xFF10141C), surface = Color(0xFF191E28), primaryContainer = Color(0xFF193366), secondaryContainer = Color(0xFF263956), surfaceVariant = Color(0xFF263140))
        else lightColorScheme(primary = Color(0xFF245DD8), background = Color(0xFFF4F6FA), surface = Color.White, secondaryContainer = Color(0xFFE7EEFF), primaryContainer = Color(0xFFE5EDFF), onPrimaryContainer = Color(0xFF173B70), surfaceVariant = Color(0xFFE8EDF5))
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)), content = content)
}

private const val SensitiveSmsPermission = "android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS"

@Suppress("DEPRECATION")
private fun smsAppOpsStatus(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "不适用"
    val op = AppOpsManager.permissionToOp(Manifest.permission.RECEIVE_SMS) ?: return "未知"
    val mode = runCatching {
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            op, context.applicationInfo.uid, context.packageName
        )
    }.getOrNull() ?: return "未知"
    return when (mode) {
        AppOpsManager.MODE_ALLOWED -> "允许"
        AppOpsManager.MODE_DEFAULT -> "默认"
        AppOpsManager.MODE_IGNORED -> "忽略"
        AppOpsManager.MODE_ERRORED -> "拒绝"
        AppOpsManager.MODE_FOREGROUND -> "仅前台"
        else -> "未知（$mode）"
    }
}

private fun sensitiveSmsPermissionStatus(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT < 36) return "不适用"
    val granted = runCatching { context.checkSelfPermission(SensitiveSmsPermission) == PackageManager.PERMISSION_GRANTED }.getOrNull()
        ?: return "未知"
    return if (granted) "已授予（系统/角色）" else "未授予（第三方应用通常不可申请）"
}

private fun defaultSmsRoleStatus(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "不适用"
    return runCatching {
        if (context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)) "是" else "否"
    }.getOrDefault("未知")
}

private fun installSourceStatus(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "系统未提供"
    return runCatching {
        val source = context.packageManager.getInstallSourceInfo(context.packageName)
        source.installingPackageName ?: source.initiatingPackageName ?: "未知"
    }.getOrDefault("未知")
}

private fun batteryOptimizationStatus(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "不适用"
    return runCatching {
        if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "已忽略" else "仍受限制"
    }.getOrDefault("未知")
}

fun buildSmsDiagnosticReport(context: android.content.Context, repo: Repository, permission: Boolean, diagnostic: SmsDiagnostic): String {
    val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull().orEmpty().ifBlank { "未设置或系统未提供" }
    return buildString {
        appendLine("短信转邮件诊断信息")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("系统构建：${Build.DISPLAY}")
        appendLine("安全补丁：${Build.VERSION.SECURITY_PATCH}")
        appendLine("应用目标 API：${context.applicationInfo.targetSdkVersion}")
        appendLine("短信权限实际状态：${if (permission) "已授予" else "未授予"}")
        appendLine("彩信通知权限：${if (repo.hasReceiveMmsPermission()) "已授予" else "未授予"}")
        appendLine("短信库读取权限：${if (repo.hasReadSmsPermission()) "已授予" else "未授予（系统可能限制）"}")
        appendLine("短信 AppOps：${smsAppOpsStatus(context)}")
        appendLine("系统敏感短信能力：${sensitiveSmsPermissionStatus(context)}")
        appendLine("转发开关：${if (repo.settings.enabled) "开启" else "关闭"}")
        appendLine("默认短信应用：$defaultSms")
        appendLine("默认短信角色：${defaultSmsRoleStatus(context)}")
        appendLine("安装来源：${installSourceStatus(context)}")
        appendLine("省电白名单：${batteryOptimizationStatus(context)}")
        if (diagnostic.receivedAt == 0L) {
            appendLine("最近广播：无记录")
        } else {
            appendLine("最近广播：${formatTime(diagnostic.receivedAt)}")
            appendLine("短信片段：${diagnostic.partCount}")
            appendLine("正文长度：${diagnostic.bodyLength}")
            appendLine("状态：${diagnostic.status}")
        }
        val mms = repo.settings.mmsDiagnostic()
        if (mms.receivedAt == 0L) {
            appendLine("最近彩信通知：无记录")
        } else {
            appendLine("最近彩信通知：${formatTime(mms.receivedAt)}")
            appendLine("彩信正文片段：${mms.textPartCount} · 附件：${mms.attachmentCount}")
            appendLine("彩信状态：${mms.status}")
        }
        appendLine("说明：此报告不包含短信正文、来源号码、邮箱地址或授权码。")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MailScreen(repo: Repository) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val rulesFlow = remember(repo) { repo.dao.observeRulesWithContacts() }
    val rules by rulesFlow.collectAsStateWithLifecycle(emptyList())
    val contacts by repo.contacts.collectAsStateWithLifecycle(emptyList())
    val defaultContacts by repo.defaultContacts.collectAsStateWithLifecycle(emptyList())
    val eventsFlow = remember(repo) { repo.dao.observeEvents() }
    val events by eventsFlow.collectAsStateWithLifecycle(emptyList())
    val revision by repo.settings.changes.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<RuleRow?>(null) }
    var editingRuleWithContacts by remember { mutableStateOf<RuleWithContacts?>(null) }
    var ruleDraftName by remember { mutableStateOf("") }
    var ruleDraftType by remember { mutableStateOf(RuleType.CUSTOM) }
    var ruleDraftExpression by remember { mutableStateOf("") }
    var ruleDraftSample by remember { mutableStateOf("") }
    var editingContact by remember { mutableStateOf<ContactRow?>(null) }
    var returnToPicker by remember { mutableStateOf<String?>(null) }
    var pendingPickerSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf(repo.hasSmsPermission()) }
    var mmsPermissions by remember { mutableStateOf(repo.hasMmsPermissions()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { permission = repo.hasSmsPermission(); mmsPermissions = repo.hasMmsPermissions(); repo.settings.changed() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
        notice = if (it) "短信权限已开启" else "未获得短信权限。可在系统应用设置中检查权限。"
    }
    val requestMmsPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mmsPermissions = repo.hasMmsPermissions()
        notice = if (mmsPermissions) "彩信权限已开启" else "彩信权限未完整开启。小米系统可能会限制未知来源应用的敏感权限，请查看系统应用设置。"
    }
    val action: (suspend () -> Unit) -> Unit = { block ->
        if (!busy) scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "操作失败" else "操作失败，请检查配置和存储空间" }
            finally { busy = false }
        }
    }
    LaunchedEffect(repo) {
        withContext(Dispatchers.IO) { if (repo.settings.enabled) repo.resume() }
    }
    val enabled = remember(revision) { repo.settings.enabled }
    val configured = remember(revision) { runCatching { repo.settings.mail() != null }.getOrDefault(false) }
    val defaultSummary = defaultContacts.joinToString("、") { "${it.name}（${it.email}）" }
    val lastError = remember(revision) { repo.settings.lastError }
    val smsDiagnostic = remember(revision) { repo.settings.smsDiagnostic() }
    val mmsDiagnostic = remember(revision) { repo.settings.mmsDiagnostic() }
    val active = enabled && permission
    val titles = listOf("首页", "规则", "记录", "设置")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.AccountTree, Icons.Outlined.History, Icons.Outlined.Settings)
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("短信 / 彩信转邮件", fontWeight = FontWeight.Bold); Text("让重要消息，及时抵达", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }) },
        bottomBar = { NavigationBar { titles.forEachIndexed { index, title -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], null) }, label = { Text(title) }) } } },
        floatingActionButton = { if (tab == 1) FloatingActionButton(onClick = { editing = null; editingRuleWithContacts = null; pendingPickerSelection = emptySet(); ruleDraftName = ""; ruleDraftType = RuleType.CUSTOM; ruleDraftExpression = ""; ruleDraftSample = ""; dialog = "rule" }) { Icon(Icons.Outlined.Add, "新增规则") } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (notice.isNotBlank()) item { Panel { Text(notice); TextButton(onClick = { notice = "" }) { Text("知道了") } } }
            when (tab) {
                0 -> {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Shield, null, modifier = Modifier.size(32.dp))
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(if (active) "消息转发已开启" else if (enabled) "短信权限待恢复" else "消息转发已暂停", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text(if (active && mmsPermissions) "新短信和彩信将按规则发送" else if (active) "短信转发中；彩信权限待开启" else "完成设置后，开启自动转发", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Switch(checked = enabled, enabled = !busy, onCheckedChange = { value -> action { repo.setEnabled(value) } }, modifier = Modifier.semanticsLabel("短信转发总开关"))
                                }
                                Text("全部命中 · 收件人去重 · 无匹配时默认转发", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    item {
                        val today = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val count = events.filter { it.event.receivedAt >= today }.sumOf { it.deliveries.count { d -> d.state == "SENT" } }
                        val pending = events.sumOf { it.deliveries.count { d -> d.state in listOf("PENDING", "SENDING", "CONFIG_ERROR") } }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Stat("今日已提交", count.toString(), Modifier.weight(1f)); Stat("待处理投递", pending.toString(), Modifier.weight(1f)) }
                    }
                    if (!permission || !configured || defaultContacts.isEmpty() || !mmsPermissions) item {
                        Panel {
                            Text("完成转发设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("只需配置一次，即可按规则转发新短信。")
                            TextButton(onClick = { requestPermission.launch(Manifest.permission.RECEIVE_SMS) }, enabled = !permission) { Text(if (permission) "✓ 短信接收权限已开启" else "1. 开启短信接收权限") }
                            if (!mmsPermissions) TextButton(onClick = { requestMmsPermissions.launch(arrayOf(Manifest.permission.RECEIVE_MMS, Manifest.permission.READ_SMS)) }) { Text("开启彩信转发权限（需系统允许）") }
                            TextButton(onClick = { dialog = "mail" }) { Text(if (configured) "✓ 发件邮箱已配置" else "2. 配置发件邮箱") }
                            TextButton(onClick = { dialog = "defaultPicker" }) { Text(if (defaultContacts.isNotEmpty()) "✓ 默认收件人已配置" else "3. 设置默认收件人") }
                        }
                    }
                    if (lastError.isNotBlank()) item { Panel { Text(lastError, color = MaterialTheme.colorScheme.error) } }
                    item { SectionTitle("最近转发", "查看全部") { tab = 2 } }
                    if (events.isEmpty()) item { EmptyCard("还没有转发记录", "开启后，新收到的短信将在这里显示。") }
                    items(events.take(3), key = { it.event.id }) { EventCard(it, repo) { selectedId = it.event.id } }
                    item { Text("后台发送受系统省电策略影响。验证码能否接收，取决于系统权限与设备。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                1 -> {
                    item { Text("转发规则", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("所有启用规则同时匹配，收件邮箱自动去重。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Panel { Text("默认去向", fontWeight = FontWeight.Bold); Text(defaultSummary.ifBlank { "尚未设置默认收件人" }); Text("没有匹配到规则的短信，将发送至这里。", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { dialog = "defaultPicker" }) { Text("管理默认收件人") } } }
                    if (rules.isEmpty()) item { EmptyCard("创建第一条规则", "例如：包含“告警”的短信 → 工作邮箱。点击右下角添加。") }
                    items(rules, key = { it.rule.id }) { item ->
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text(item.rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Switch(item.rule.enabled, onCheckedChange = { value -> action { repo.dao.saveRule(item.rule.copy(enabled = value)) } }, enabled = !busy, modifier = Modifier.semanticsLabel("启用规则 ${item.rule.name}")) }
                            Text("${ruleTypeLabel(item.rule.type)} · ${RulePresets.description(runCatching { RuleType.valueOf(item.rule.type) }.getOrDefault(RuleType.CUSTOM))}", color = MaterialTheme.colorScheme.primary)
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                            Text(item.contacts.joinToString("、") { "${it.name}（${it.email}）" }.ifBlank { item.rule.recipients })
                            Row { TextButton(onClick = { editingRuleWithContacts = item; pendingPickerSelection = item.contacts.map { it.id }.toSet(); ruleDraftName = item.rule.name; ruleDraftType = runCatching { RuleType.valueOf(item.rule.type) }.getOrDefault(RuleType.CUSTOM); ruleDraftExpression = item.rule.expression; ruleDraftSample = ""; dialog = "rule" }) { Text("编辑与测试") }; TextButton(onClick = { editing = item.rule; dialog = "deleteRule" }) { Text("删除", color = MaterialTheme.colorScheme.error) } }
                        }
                    }
                    item { Spacer(Modifier.height(64.dp)) }
                }
                2 -> {
                    item { Text("转发记录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("完成记录保留 7 天，未完成任务继续保留。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (events.isEmpty()) item { EmptyCard("暂无记录", "收到短信后，可在这里查看各收件人的投递结果。") }
                    items(events, key = { it.event.id }) { EventCard(it, repo) { selectedId = it.event.id } }
                }
                3 -> {
                    item { Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { Panel { Text("邮件与收件人", fontWeight = FontWeight.Bold); SettingButton("发件邮箱", if (configured) "已配置 · 查看或修改" else "QQ / 163 / 自定义 SMTP") { dialog = "mail" }; SettingButton("默认收件人", defaultSummary.ifBlank { "未设置" }) { dialog = "defaultPicker" }; SettingButton("联系人管理", if (contacts.isEmpty()) "新增姓名、邮箱和备注" else "已维护 ${contacts.size} 个联系人") { dialog = "contacts" }; SettingButton("发送测试邮件", "保存发件配置后，验证邮箱连接") { dialog = "test" } } }
                    item {
                        Panel {
                            Text("运行权限", fontWeight = FontWeight.Bold)
                            Text(if (permission) "短信权限：已开启" else "短信权限：未开启")
                            OutlinedButton(onClick = { requestPermission.launch(Manifest.permission.RECEIVE_SMS) }) { Text("申请短信权限") }
                            HorizontalDivider()
                            Text("彩信通知权限：${if (repo.hasReceiveMmsPermission()) "已开启" else "未开启"}")
                            Text("短信库读取权限：${if (repo.hasReadSmsPermission()) "已开启" else "未开启"}")
                            OutlinedButton(onClick = { requestMmsPermissions.launch(arrayOf(Manifest.permission.RECEIVE_MMS, Manifest.permission.READ_SMS)) }) { Text(if (mmsPermissions) "重新检查彩信权限" else "申请彩信转发权限") }
                            Text("完整彩信由当前默认短信应用下载后读取。此应用不会更改默认短信应用；若系统因安装来源限制敏感权限，请按小米系统的提示解除限制。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("打开系统应用设置") }
                            Text("允许后台运行与自启动。强行停止期间无法保证接收消息；重新打开后恢复未完成任务。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Panel {
                            Text("短信接收诊断", fontWeight = FontWeight.Bold)
                            if (smsDiagnostic.receivedAt == 0L) {
                                Text("还没有记录到系统短信广播", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("最近广播：${formatTime(smsDiagnostic.receivedAt)}")
                                Text("短信片段：${smsDiagnostic.partCount} · 正文长度：${smsDiagnostic.bodyLength}")
                                Text("状态：${smsDiagnostic.status}", style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                            Text("彩信读取", fontWeight = FontWeight.Bold)
                            if (mmsDiagnostic.receivedAt == 0L) {
                                Text("还没有记录到系统彩信通知", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("最近通知：${formatTime(mmsDiagnostic.receivedAt)}")
                                Text("正文片段：${mmsDiagnostic.textPartCount} · 附件：${mmsDiagnostic.attachmentCount}")
                                Text("状态：${mmsDiagnostic.status}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("这里只记录时间、片段数和长度，不保存诊断用的短信正文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {
                                val report = buildSmsDiagnosticReport(context, repo, permission, smsDiagnostic)
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("短信诊断信息", report))
                                notice = "诊断信息已复制，可粘贴发送给开发者"
                            }) { Text("复制诊断信息") }
                        }
                    }
                    item { Panel { Text("数据与隐私", fontWeight = FontWeight.Bold); Text("短信和彩信正文、来源号码、彩信主题与媒体附件、SMTP 授权码在本机加密保存；联系人邮箱和规则名称作为本地配置保存。邮件直接发送至你配置的邮箱，不经过其他服务器。卸载将删除本机记录和配置。", style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { dialog = "clear" }) { Text("清空已完成记录", color = MaterialTheme.colorScheme.error) }; Text("版本 0.2.2 · 彩信附件转发", style = MaterialTheme.typography.labelSmall) } }
                }
            }
        }
    }
    if (failure.isNotBlank()) AlertDialog(onDismissRequest = { failure = "" }, title = { Text("操作未完成") }, text = { Text(failure) }, confirmButton = { TextButton(onClick = { failure = "" }) { Text("知道了") } })
    when (dialog) {
        "mail" -> MailEditor(runCatching { repo.settings.mail() }.getOrNull(), busy, { dialog = null }) { config -> action { repo.saveMail(config); withContext(Dispatchers.Main) { dialog = null; notice = "发件配置已保存" } } }
        "defaultPicker" -> ContactPicker("默认收件人", contacts, (if (pendingPickerSelection.isNotEmpty()) pendingPickerSelection else defaultContacts.map { it.id }.toSet()), true, busy, { dialog = null }, { ids -> action { repo.saveDefaultContacts(ids.toList()); withContext(Dispatchers.Main) { pendingPickerSelection = emptySet(); dialog = null; notice = "默认收件人已保存" } } }, { returnToPicker = "defaultPicker"; pendingPickerSelection = it; editingContact = null; dialog = "contact" })
        "test" -> ContactPicker("发送测试邮件", contacts, pendingPickerSelection, false, busy, { dialog = null }, { ids -> action { val recipient = ids.singleOrNull()?.let { repo.dao.contact(it)?.email } ?: error("请先选择联系人"); val result = repo.testMail(recipient); withContext(Dispatchers.Main) { pendingPickerSelection = emptySet(); dialog = null; notice = result.detail } } }, { returnToPicker = "test"; pendingPickerSelection = it; editingContact = null; dialog = "contact" })
        "rule" -> RuleEditor(editingRuleWithContacts, contacts, ruleDraftName, { ruleDraftName = it }, ruleDraftType, { value -> ruleDraftType = value }, ruleDraftExpression, { ruleDraftExpression = it }, ruleDraftSample, { ruleDraftSample = it }, pendingPickerSelection, busy, { dialog = null }, { dialog = "rulePicker" }) { row, ids -> action { repo.saveRuleWithContacts(row, ids); withContext(Dispatchers.Main) { pendingPickerSelection = emptySet(); dialog = null } } }
        "rulePicker" -> ContactPicker("选择规则收件人", contacts, pendingPickerSelection, true, busy, { dialog = "rule" }, { ids -> pendingPickerSelection = ids; dialog = "rule" }, { returnToPicker = "rulePicker"; pendingPickerSelection = it; editingContact = null; dialog = "contact" })
        "contacts" -> ContactsManager(contacts, busy, { dialog = null }, { editingContact = it; dialog = "contact" }, { editingContact = it; dialog = "deleteContact" }, { editingContact = null; returnToPicker = null; dialog = "contact" })
        "contact" -> ContactEditor(editingContact, busy, { dialog = if (returnToPicker != null) returnToPicker else "contacts" }) { id, name, email, note -> action { val saved = repo.saveContact(id, name, email, note); withContext(Dispatchers.Main) { if (returnToPicker != null) { pendingPickerSelection = pendingPickerSelection + saved.id; val next = returnToPicker; returnToPicker = null; dialog = next } else { dialog = "contacts" }; editingContact = null; notice = "联系人已保存" } } }
        "deleteContact" -> Confirm("删除联系人？", "删除后不会影响已经排队的短信。", { dialog = "contacts" }) { action { editingContact?.let { repo.deleteContact(it.id) }; withContext(Dispatchers.Main) { editingContact = null; dialog = "contacts" } } }
        "deleteRule" -> Confirm("删除规则？", "删除后只影响新收到的短信，已排队任务保持不变。", { dialog = null }) { action { editing?.let { repo.dao.deleteRule(it.id) }; withContext(Dispatchers.Main) { dialog = null } } }
        "clear" -> Confirm("清空已完成记录？", "将删除已提交和明确失败的记录；等待发送、配置异常和结果不确定的任务继续保留。", { dialog = null }) { action { repo.dao.clearCompleted(Long.MAX_VALUE); withContext(Dispatchers.Main) { dialog = null } } }
    }
    val selected = events.find { it.event.id == selectedId }
    if (selected != null) SheetDialog(if (selected.event.kind == MessageKind.MMS) "彩信详情" else "短信详情", { selectedId = null }) {
        Text("来源：${runCatching { repo.decrypt(selected.event.source) }.getOrDefault("无法解密")}")
        Text("类型：${if (selected.event.kind == MessageKind.MMS) "彩信" else "短信"}\n接收：${formatTime(selected.event.receivedAt)}\n${selected.event.sim}\n规则：${selected.event.matchedRules}")
        if (selected.event.kind == MessageKind.MMS && selected.event.subject.isNotEmpty()) Text("主题：${runCatching { repo.decrypt(selected.event.subject) }.getOrDefault("无法解密主题")}")
        var expanded by remember(selected.event.id) { mutableStateOf(false) }
        val bodyLabel = if (selected.event.kind == MessageKind.MMS) "彩信正文" else "短信正文"
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起$bodyLabel" else "查看$bodyLabel") }
        if (expanded) Text(runCatching { repo.decrypt(selected.event.body) }.getOrDefault("无法解密本机记录"))
        if (selected.event.kind == MessageKind.MMS) {
            var attachments by remember(selected.event.id) { mutableStateOf(emptyList<AttachmentRow>()) }
            LaunchedEffect(selected.event.id) { attachments = withContext(Dispatchers.IO) { repo.dao.attachments(selected.event.id) } }
            Text("彩信附件：${selected.event.attachmentCount} 个")
            attachments.forEach { attachment ->
                Text(runCatching { repo.decrypt(attachment.fileName) }.getOrDefault("无法解密附件名称") + " · " + attachment.contentType,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        selected.deliveries.forEach { delivery ->
            HorizontalDivider()
            Text(delivery.recipient, fontWeight = FontWeight.Bold)
            Text("${stateLabel(delivery.state)} · 已尝试 ${delivery.attempts} 次", color = deliveryColor(listOf(delivery)))
            Text(delivery.detail, style = MaterialTheme.typography.bodySmall)
            if (delivery.state in listOf("FAILED", "CONFIG_ERROR", "UNCERTAIN")) {
                var confirmRetry by remember(delivery.id) { mutableStateOf(false) }
                TextButton(enabled = !busy, onClick = { confirmRetry = true }) { Text("重试此收件人") }
                if (confirmRetry) Confirm("重试投递？", "如果之前已经收到邮件，重试可能产生重复邮件。仅重试当前收件人。", { confirmRetry = false }) { confirmRetry = false; action { repo.retry(delivery.id) } }
            }
        }
    }
}

@Composable fun deliveryColor(deliveries: List<DeliveryRow>): Color = when {
    deliveries.any { it.state in listOf("FAILED", "CONFIG_ERROR", "UNCERTAIN") } -> MaterialTheme.colorScheme.error
    deliveries.all { it.state == "SENT" } -> if (isSystemInDarkTheme()) Color(0xFF81D6A6) else Color(0xFF196A42)
    else -> if (isSystemInDarkTheme()) Color(0xFFFFCE85) else Color(0xFF875400)
}
fun stateLabel(state: String) = when (state) { "PENDING" -> "等待发送"; "SENDING" -> "发送中"; "SENT" -> "已提交"; "CONFIG_ERROR" -> "配置待修正"; "UNCERTAIN" -> "结果不确定"; else -> "发送失败" }
@Composable fun Panel(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) } }
@Composable fun Stat(title: String, value: String, modifier: Modifier) { Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(20.dp)) { Text(value, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.labelLarge) } } }
@Composable fun EmptyCard(title: String, text: String) { Panel { Icon(Icons.Outlined.Inbox, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleMedium); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun SectionTitle(title: String, action: String, click: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = click) { Text(action) } } }
@Composable fun SettingButton(title: String, text: String, click: () -> Unit) { Surface(onClick = click, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(vertical = 12.dp)) { Text(title, color = MaterialTheme.colorScheme.primary); Text(text, style = MaterialTheme.typography.bodySmall) } } }
@Composable fun EventCard(row: EventWithDeliveries, repo: Repository, click: () -> Unit) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(runCatching { repo.decrypt(row.event.source) }.getOrDefault("无法解密来源"), fontWeight = FontWeight.Bold)
            if (row.event.kind == MessageKind.MMS) Text("彩信 · ${row.event.attachmentCount} 个附件", color = MaterialTheme.colorScheme.primary)
            Text("${formatTime(row.event.receivedAt)} · ${row.event.matchedRules}", style = MaterialTheme.typography.bodySmall)
            Text(row.deliveries.groupingBy { stateLabel(it.state) }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }, color = deliveryColor(row.deliveries))
        }
    }
}
@Composable fun Confirm(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = confirm) { Text("确认") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }) }
@Composable fun SheetDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 700.dp).imePadding(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭") } }
                content()
            }
        }
    }
}
@Composable fun TextEditor(title: String, initial: String, hint: String, busy: Boolean, dismiss: () -> Unit, button: String = "保存", save: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    SheetDialog(title, dismiss) {
        Text(hint, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value, { value = it; error = "" }, label = { Text("收件邮箱") }, modifier = Modifier.fillMaxWidth(), minLines = 2, isError = error.isNotBlank())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, onClick = { try { val parsed = Addresses.parse(value); if (button == "发送测试") require(parsed.size == 1) { "测试时请填写一个邮箱" }; save(value) } catch (e: IllegalArgumentException) { error = e.message.orEmpty() } }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "处理中…" else button) }
    }
}
@Composable fun MailEditor(initial: MailConfig?, busy: Boolean, dismiss: () -> Unit, save: (MailConfig) -> Unit) {
    var host by remember { mutableStateOf(initial?.host ?: "smtp.qq.com") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "465") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var security by remember { mutableStateOf(initial?.security ?: "SSL") }
    var error by remember { mutableStateOf("") }
    SheetDialog("发件邮箱", dismiss) {
        Text("使用邮箱提供的 SMTP 授权码，不是邮箱登录密码。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { host = "smtp.qq.com"; port = "465"; security = "SSL" }) { Text("QQ 邮箱") }; OutlinedButton(onClick = { host = "smtp.163.com"; port = "465"; security = "SSL" }) { Text("163 邮箱") } }
        OutlinedTextField(email, { email = it }, label = { Text("发件邮箱地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("SMTP 授权码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(host, { host = it }, label = { Text("SMTP 服务器") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(security == "SSL", { security = "SSL"; port = "465" }, { Text("SSL / TLS") }); FilterChip(security == "STARTTLS", { security = "STARTTLS"; port = "587" }, { Text("STARTTLS") }) }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { val config = MailConfig(host.trim(), port.toIntOrNull() ?: 0, security, email.trim(), password.trim()); error = config.error().orEmpty(); if (error.isBlank()) save(config) }) { Text("保存发件配置") }
    }
}
fun ruleTypeLabel(type: String): String = when (runCatching { RuleType.valueOf(type) }.getOrDefault(RuleType.CUSTOM)) {
    RuleType.ALL -> "所有"
    RuleType.OTP -> "验证码"
    RuleType.AMOUNT -> "金额"
    RuleType.CUSTOM -> "自定义"
}

@Composable fun RuleEditor(initial: RuleWithContacts?, contacts: List<ContactRow>, name: String, onNameChange: (String) -> Unit, type: RuleType, onTypeChange: (RuleType) -> Unit, expression: String, onExpressionChange: (String) -> Unit, sample: String, onSampleChange: (String) -> Unit, selectedIds: Set<String>, busy: Boolean, dismiss: () -> Unit, openPicker: () -> Unit, save: (RuleRow, List<String>) -> Unit) {
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val actualExpression = RulePresets.expression(type, expression.takeIf { type == RuleType.CUSTOM })
    SheetDialog(if (initial == null) "新增规则" else "编辑规则", dismiss) {
        OutlinedTextField(name, onNameChange, label = { Text("规则名称") }, modifier = Modifier.fillMaxWidth())
        Text("快速配置", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            RuleType.values().forEach { option -> FilterChip(selected = type == option, onClick = { onTypeChange(option); result = ""; if (option != RuleType.CUSTOM) onExpressionChange(RulePresets.expression(option)) }, label = { Text(ruleTypeLabel(option.name)) }) }
        }
        Text(RulePresets.description(type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (type == RuleType.CUSTOM) OutlinedTextField(expression, { onExpressionChange(it); result = "" }, label = { Text("短信正文正则") }, supportingText = { Text("包含匹配，例如：告警|故障。RE2 语法，不支持回溯引用或环视。") }, modifier = Modifier.fillMaxWidth())
        else TextButton(onClick = { onTypeChange(RuleType.CUSTOM); onExpressionChange(actualExpression); result = "" }) { Text("基于此模板自定义") }
        OutlinedButton(onClick = openPicker, modifier = Modifier.fillMaxWidth()) { Text(if (selectedIds.isEmpty()) "选择收件联系人" else "已选择 ${selectedIds.size} 个收件联系人") }
        contacts.filter { it.id in selectedIds }.forEach { Text("${it.name}（${it.email}）", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        OutlinedTextField(sample, { onSampleChange(it); result = "" }, label = { Text("输入样例短信") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { result = Router.regexError(actualExpression) ?: if (Router.matches(actualExpression, sample)) "✓ 匹配成功，将使用所选联系人" else "未匹配本规则" }) { Text("测试规则") }
        if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.primary)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            try {
                require(name.trim().isNotBlank()) { "请填写规则名称" }
                require(Router.regexError(actualExpression) == null) { Router.regexError(actualExpression)!! }
                require(selectedIds.isNotEmpty()) { "请至少选择一个收件联系人" }
                val row = RuleRow(initial?.rule?.id ?: UUID.randomUUID().toString(), name.trim(), actualExpression, contacts.filter { it.id in selectedIds }.joinToString("\n") { it.email }, initial?.rule?.enabled ?: true, initial?.rule?.createdAt ?: System.currentTimeMillis(), type.name, RulePresets.VERSION)
                save(row, selectedIds.toList())
            } catch (e: IllegalArgumentException) { error = e.message.orEmpty() }
        }) { Text("保存规则") }
    }
}

@Composable fun ContactEditor(initial: ContactRow?, busy: Boolean, dismiss: () -> Unit, save: (String?, String, String, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var error by remember { mutableStateOf("") }
    SheetDialog(if (initial == null) "新增联系人" else "编辑联系人", dismiss) {
        Text("联系人只保存在本机，用于快速选择收件人。")
        OutlinedTextField(name, { name = it; error = "" }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it; error = "" }, label = { Text("邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, onClick = { try { ContactValues.validate(name, email, note); save(initial?.id, name, email, note) } catch (e: IllegalArgumentException) { error = e.message.orEmpty() } }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "处理中…" else "保存联系人") }
    }
}

@Composable fun ContactsManager(contacts: List<ContactRow>, busy: Boolean, dismiss: () -> Unit, edit: (ContactRow) -> Unit, delete: (ContactRow) -> Unit, add: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val visible = contacts.filter { query.isBlank() || listOf(it.name, it.email, it.note).any { value -> value.contains(query, ignoreCase = true) } }
    SheetDialog("联系人管理", dismiss) {
        Text("维护后，规则和默认收件人都可以直接选择联系人。")
        OutlinedTextField(query, { query = it }, label = { Text("搜索联系人") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = add, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("新增联系人") }
        if (visible.isEmpty()) EmptyCard("还没有联系人", "先新增姓名和邮箱，配置规则时就能直接选择。")
        visible.forEach { contact ->
            Surface(onClick = { edit(contact) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(contact.name, fontWeight = FontWeight.Bold); Text(contact.email); if (contact.note.isNotBlank()) Text(contact.note, style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = { delete(contact) }, enabled = !busy) { Icon(Icons.Outlined.Delete, "删除 ${contact.name}") }
                }
            }
        }
    }
}

@Composable fun ContactPicker(title: String, contacts: List<ContactRow>, initialIds: Set<String>, multiple: Boolean, busy: Boolean, dismiss: () -> Unit, save: (Set<String>) -> Unit, add: (Set<String>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentContacts by remember { mutableStateOf(contacts) }
    LaunchedEffect(title, contacts) {
        currentContacts = contacts
        currentContacts = withContext(Dispatchers.IO) { context.mailApp.repository.dao.contacts() }
    }
    var selected by remember(initialIds) { mutableStateOf(initialIds) }
    var query by remember { mutableStateOf("") }
    val visible = currentContacts.filter { query.isBlank() || it.name.contains(query, true) || it.email.contains(query, true) || it.note.contains(query, true) }
    SheetDialog(title, dismiss) {
        Text(if (multiple) "可多选联系人；同一邮箱只会发送一次。" else "请选择一个联系人。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text("搜索联系人") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (currentContacts.isEmpty()) EmptyCard("还没有联系人", "点击下面的按钮新增联系人。")
        visible.forEach { contact ->
            Row(Modifier.fillMaxWidth().clickable { selected = if (multiple) if (contact.id in selected) selected - contact.id else selected + contact.id else setOf(contact.id) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = contact.id in selected, onCheckedChange = { checked -> selected = if (multiple) if (checked) selected + contact.id else selected - contact.id else if (checked) setOf(contact.id) else emptySet() })
                Column { Text(contact.name, fontWeight = FontWeight.Bold); Text(contact.email, style = MaterialTheme.typography.bodySmall) }
            }
        }
        OutlinedButton(onClick = { add(selected) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("新增联系人") }
        Button(onClick = { save(selected) }, enabled = !busy && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("确认选择（${selected.size}）") }
    }
}
