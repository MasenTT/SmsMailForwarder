# 实现结构

- `core`：与 Android 无关的分流、地址校验、指纹和重试状态策略。
- `app/Storage.kt`：Room 持久化；短信／彩信正文、来源、主题和附件使用 Keystore AES-GCM 加密；发件配置整体加密。去重摘要使用 Keystore HMAC-SHA256。联系人存储在 `contacts`，规则和默认收件人分别通过 `rule_contacts`、`default_contacts` 关联；数据库 v4→v5 增加规则顺序、标题模板、正文显示选项及待发标题快照字段。
- `app/Forwarding.kt`：`SMS_RECEIVED`／`DATA_SMS_RECEIVED` → 合并短信正文 → 按规则顺序选中首条启用命中 → 接收时固定收件人、渲染标题并快照正文选项；标题支持 `{消息内容}`，空白归一后按 Unicode 码点截短至 50 字符（含省略号）；短信／彩信事件、加密附件与逐收件人任务在同一事务写入 → WorkManager。默认收件人从 `default_contacts` 关联读取；该处为空时才回退旧版文本配置。开启校验与短信路由共用这一来源。
- `app/MmsIngestion.kt`：监听非默认短信应用可收到的 `WAP_PUSH_RECEIVED` 通知，等待当前默认短信应用下载到 Telephony MMS Provider，再读取主题、文本片段、发件人、SIM 信息和媒体附件；缺权限、未下载和读取异常写入脱敏 MMS 诊断，不切换默认短信角色。
- `app/MailSender.kt`：单收件人 SMTP 提交，彩信以 multipart MIME 发送文本和原始附件；强制 TLS 和主机身份校验，清理附件名及 MIME 类型，错误消息固定脱敏。
- `app/MainActivity.kt`：Material 3 四页导航与编辑、测试、详情弹窗；规则页可持久化拖动排序，标题模板支持光标插入变量；设置页展示短信／彩信权限、诊断信息及默认关闭的五项正文显示开关。诊断报告不含消息正文、号码、邮箱或授权码。

## 状态与恢复

PENDING → SENDING → SENT / PENDING / FAILED / CONFIG_ERROR / UNCERTAIN。

每次实际发送前原子 claim 并累计次数；临时失败最多尝试 5 次。每个收件人使用独立串行唯一工作链，重复调度检查数据库状态后结束，不重发 SENT。使用 APPEND_OR_REPLACE 避免旧工作即将退出时，恢复调度被 KEEP 忽略。

中断后仍是 SENDING 的任务变为 UNCERTAIN，用户核实后才能重试。配置保存恢复 CONFIG_ERROR。启动、重新开启和开机重新调度未发送任务。暂停不取消已经进行的 SMTP 提交。

事务成功后、调度前进程终止的窗口，由下次启动或开机扫描 PENDING 修复。Android 或厂商系统在广播交付前限制应用时，本应用无法恢复未收到的短信，不扫描历史收件箱；带 SMS Retriever 标识的短信也可能按系统规则只发给目标应用。彩信还依赖 `RECEIVE_MMS`、硬限制的 `READ_SMS`，以及默认短信应用完成系统 MMS 下载；未下载时不能仅靠 WAP Push 通知还原原始媒体。诊断只保存时间、片段数、附件数和状态，不保存消息正文。

## 数据保留

按事件接收时间清理 7 天前、且所有收件人均为 SENT / FAILED 的事件。PENDING、SENDING、CONFIG_ERROR、UNCERTAIN 保留，关联的加密彩信附件使用外键级联删除。来源号码、主题、正文、附件名和二进制附件加密；收件邮箱及匹配规则名称为应用沙箱内元数据。关闭系统备份。

## 测试层级

JVM 测试覆盖规则和重试政策；Android 集成测试使用真实 Room/Keystore 和伪邮件网关，不对外发送邮件；Compose 测试驱动真实页面。真实邮箱和实体设备验证单独记录。

## 数据库升级

数据库版本 5。版本 1→2 转换去重摘要；版本 2→3 新增联系人关联和规则预设；版本 3→4 新增事件消息类型、主题、附件数量及 `event_attachments` 表；版本 4→5 新增 `rules.sortOrder`、`rules.subjectTemplate`、`events.emailMetadataMask` 和 `deliveries.emailSubjectSnapshot`。升级时旧规则按创建时间初始化顺序；新短信的正文选项默认关闭，迁移前已排队的事件保留旧版默认显示的五项元数据，避免重试邮件正文发生变化。旧待投递标题为空并在投递时沿用系统标题；现有联系人、规则、事件、附件及投递状态和尝试次数均保留。附件采用 AES-GCM 加密后存为 BLOB，并在事件清理时级联删除。
