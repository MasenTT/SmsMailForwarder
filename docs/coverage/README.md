# 覆盖率口径

生成日期：2026-09-22。下方 `authored/` 和 `all/` HTML 是 0.1.1 的历史基线；0.2.1 新增联系人、联系人选择器、预设规则、短信广播诊断和数据库迁移后，本轮以 14 项核心 JVM、8 项 SMTP、17 项 Android 设备／界面测试作为当前功能验证证据。未重新生成覆盖率 HTML，因此不把历史数字当作 0.2.1 覆盖率。

## 结果

| 范围 | 行 | 方法 | 分支 |
| --- | --- | --- | --- |
| 手写源码（app + core） | 462 / 488，94.7% | 355 / 377，94.2% | 405 / 648，62.5% |
| 包含 Room 生成实现 | 924 / 969，95.4% | 416 / 440，94.5% | 437 / 690，63.3% |

`authored/` 仅排除 `MailDao_Impl*` 和 `MailDatabase_Impl*`，保留 Kotlin／Compose 生成的所有其他相关类。`all/` 不作这些排除。不能用行覆盖率代替功能、真机兼容性或全路径验证。

## 0.2.0 当前验证

- 核心 JVM：14 / 14 通过。
- 本机 SMTP 单元：8 / 8 通过。
- Android 15 模拟器集成／Compose UI：17 / 17 通过。
- 结果文件：`app/build/reports/androidTests/connected/debug/index.html`、`app/build/outputs/androidTest-results/connected/debug/`。
- 若需发布级覆盖率趋势，下一轮应在功能稳定后按下方命令重新生成并替换两套 HTML。

## 重现

在 JDK 21、Android SDK 和已启动测试设备环境中运行：

```sh
./gradlew -Pcoverage=true :core:test :core:jacocoTestReport :app:createDebugUnitTestCoverageReport :app:createDebugAndroidTestCoverageReport
```

用 JaCoCo CLI 0.8.13 的 `report` 命令合并以下执行数据：

- `core/build/jacoco/test.exec`
- `app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`
- `app/build/outputs/code_coverage/debugAndroidTest/connected/<设备>/coverage.ec`

使用未插桩类目录 `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` 和 `core/build/classes/kotlin/main`；不要使用 `jacocoDebug/dirs` 中已经插桩的类。分别输出原始口径和排除上述 Room 生成类的口径。

覆盖率构建只供测试。交付 APK 已通过不带 `-Pcoverage=true` 的正常构建生成。
