# 实现结构

- `core`：与 Android 无关的分流、地址校验、指纹和重试状态策略。
- `app/Storage.kt`：Room 持久化；短信正文与来源号码使用 Keystore AES-GCM 加密；发件配置整体加密。授权码不进入源码或日志。去重摘要使用 Keystore HMAC-SHA256，普通内容哈希仅作为内存中的输入。联系人存储在 `contacts`，规则和默认收件人分别通过 `rule_contacts`、`default_contacts` 关联。
- `app/Forwarding.kt`：`SMS_RECEIVED`／`DATA_SMS_RECEIVED` 系统广播 → 合并正文 → 路由快照 → 同一事务写入短信与逐收件人任务 → WorkManager；同时处理 `SMS_REJECTED`，把不含短信正文的接收时间、片段数和长度写入诊断状态，并输出同样脱敏的广播元数据日志供真机定位。
- `app/MailSender.kt`：单收件人 SMTP 提交，强制 TLS 和主机身份校验，错误消息固定脱敏。
- `app/MainActivity.kt`：Material 3 四页导航与编辑、测试、详情弹窗；设置页可复制不含短信正文、号码、邮箱或授权码的诊断报告，并显示 Android／系统构建／安全补丁、短信 AppOps、系统敏感短信能力、默认短信角色、安装来源和省电状态。

## 状态与恢复

PENDING → SENDING → SENT / PENDING / FAILED / CONFIG_ERROR / UNCERTAIN。

每次实际发送前原子 claim 并累计次数；临时失败最多尝试 5 次。每个收件人使用独立串行唯一工作链，重复调度检查数据库状态后结束，不重发 SENT。使用 APPEND_OR_REPLACE 避免旧工作即将退出时，恢复调度被 KEEP 忽略。

中断后仍是 SENDING 的任务变为 UNCERTAIN，用户核实后才能重试。配置保存恢复 CONFIG_ERROR。启动、重新开启和开机重新调度未发送任务。暂停不取消已经进行的 SMTP 提交。

事务成功后、调度前进程终止的窗口，由下次启动或开机扫描 PENDING 修复。Android 或厂商系统在广播交付前限制应用时，本应用无法恢复未收到的短信，不扫描历史收件箱；带 SMS Retriever 标识的短信也可能按系统规则只发给目标应用。设置页的短信接收诊断只保存时间、片段数、正文长度和状态，不保存诊断用正文；复制报告额外读取系统权限状态、角色、安装来源和省电状态，用于区分广播未到达、广播解析失败和后续发送失败。

## 数据保留

按事件接收时间清理 7 天前、且所有收件人均为 SENT / FAILED 的事件。PENDING、SENDING、CONFIG_ERROR、UNCERTAIN 保留。手动清空也遵循此规则。来源号码和正文加密，收件邮箱及匹配规则名称为应用沙箱内元数据。联系人邮箱不会写入短信正文；收到短信时解析联系人关联并将邮箱写入独立投递任务，因此之后编辑联系人不会改变已经排队的投递。关闭系统备份。

## 测试层级

JVM 测试覆盖规则和重试政策；Android 集成测试使用真实 Room/Keystore 和伪邮件网关，不对外发送邮件；Compose 测试驱动真实页面。真实邮箱和实体设备验证单独记录。

## 数据库升级

数据库版本 3。版本 1→2 在 Room 迁移事务内将已有 SHA-256 去重值转换为带 `h1:` 前缀的 HMAC-SHA256 摘要；版本 2→3 新增联系人表、规则／默认联系人关联及规则预设类型。旧规则的 `recipients` 和旧 SharedPreferences 默认邮箱会导入本地联系人，并保留原有规则、事件、投递状态、尝试次数和配置。新短信使用联系人关联路由，旧 `recipients` 仍作为迁移兼容回退。未启用破坏性迁移；密钥访问失败或迁移失败不会清空记录。
