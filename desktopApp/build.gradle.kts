import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "morefish.knowledge.trainer.MainKt"

        nativeDistributions {
            // 只声明当前平台真能产出的格式；跨平台打包由各自平台上的构建完成
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "KnowledgeTrainer"
            packageVersion = "1.0.0"
            // 必须是 ASCII，不能写中文。MSI 的数据库码页跟着**构建机的 locale** 走：
            // 本机 zh-CN 是 936，中文装得下；en-US 的机器是 1252，中文装不下，
            // light.exe 直接退出 311（LGHT0311），jpackage 只说 "Exit code: 1"。
            // 这条在 2026-10-28 的 GitHub runner 上真实踩过（见 STATUS-P10 第 19 节）。
            // 中文说明留在应用内的「关于」与 README 里，安装包属性用英文。
            description = "Offline-first knowledge trainer: import banks, practice, grade offline, review mistakes"
            vendor = "morefish"
            copyright = "© 2026 morefish"
            // 图标由 tools/release/make_icons.py 生成，改配色改尺寸只改那一处
            linux {
                iconFile.set(project.file("icons/app.png"))
            }
            windows {
                iconFile.set(project.file("icons/app.ico"))
                menuGroup = "Knowledge Trainer"
                shortcut = true
                dirChooser = true
                // 用户目录安装：不需要管理员权限，卸载也在"应用和功能"里
                perUserInstall = true
                // MSI 升级靠这个 UUID 认"同一个产品"，必须固定，不能每次构建重新生成
                upgradeUuid = "5ba16a17-1c09-4d1d-9b9f-910fa375f057"
            }
        }
    }
}