# Android Skills 来源与维护

本项目通过项目级 `.agents/skills/` 引入 [android/skills](https://github.com/android/skills) 的全部 36 个技能及其附带资源。项目开发约定见根目录 [AGENTS.md](../AGENTS.md)。这些是 Agent 技术指导文件，不是 Android 应用运行时依赖。

- 固定提交：`45fd1739a4dbca5452c3e936570797f7f6261cd7`
- 上游插件版本：`1.0.18`
- 许可证：[Apache-2.0](../.agents/android-skills-LICENSE.txt)，独立适用于引入的上游技能文件；保留项目原有 MIT 许可证。
- 机器可读清单：[android-skills.lock.json](../.agents/android-skills.lock.json)
- 安装方式：使用 Codex skill-installer 的 `install-skill-from-github.py`，指定上述提交和 `--dest .agents/skills`。
- 技能按任务相关性启用；不要求升级 AGP、迁移导航或更换现有小组件框架。

## 更新流程

1. 确定需要更新到的上游提交，审查技能及脚本差异。
2. 按 lock 清单中的 `upstreamPath`，通过安装器安装到临时目录，使用固定提交参数 `--ref <commit>`。
3. 核对文件和许可证后替换对应项目技能；安装器不会覆盖已有目录。
4. 同步本文件、lock 清单和技能索引，检查引用及 `git diff`。不要静默跟随上游 main。

## 技能索引

| 技能 | 项目文件 | 上游目录 |
| --- | --- | --- |
| `agp-9-upgrade` | [SKILL.md](../.agents/skills/agp-9-upgrade/SKILL.md) | `build-system/agp/agp-9-upgrade` |
| `camerax` | [SKILL.md](../.agents/skills/camerax/SKILL.md) | `camera/camerax` |
| `android-for-cars-parked-apps` | [SKILL.md](../.agents/skills/android-for-cars-parked-apps/SKILL.md) | `cars/android-for-cars-parked-apps` |
| `appfunctions` | [SKILL.md](../.agents/skills/appfunctions/SKILL.md) | `device-ai/appfunctions` |
| `litert-lm` | [SKILL.md](../.agents/skills/litert-lm/SKILL.md) | `device-ai/litert/litert-lm` |
| `litert-runtime` | [SKILL.md](../.agents/skills/litert-runtime/SKILL.md) | `device-ai/litert/litert-runtime` |
| `ml-kit-genai-prompt-api` | [SKILL.md](../.agents/skills/ml-kit-genai-prompt-api/SKILL.md) | `device-ai/ml-kit-genai-prompt-api` |
| `mlkit-agent-api` | [SKILL.md](../.agents/skills/mlkit-agent-api/SKILL.md) | `device-ai/mlkit-agent-api` |
| `android-cli` | [SKILL.md](../.agents/skills/android-cli/SKILL.md) | `devtools/android-cli` |
| `restore-credentials` | [SKILL.md](../.agents/skills/restore-credentials/SKILL.md) | `identity/restore-credentials` |
| `verified-email` | [SKILL.md](../.agents/skills/verified-email/SKILL.md) | `identity/verified-email` |
| `adaptive` | [SKILL.md](../.agents/skills/adaptive/SKILL.md) | `jetpack-compose/adaptive` |
| `ink-app-builder` | [SKILL.md](../.agents/skills/ink-app-builder/SKILL.md) | `jetpack-compose/ink/ink-app-builder` |
| `ink-custom-brush-builder` | [SKILL.md](../.agents/skills/ink-custom-brush-builder/SKILL.md) | `jetpack-compose/ink/ink-custom-brush-builder` |
| `jetpack-compose-performance` | [SKILL.md](../.agents/skills/jetpack-compose-performance/SKILL.md) | `jetpack-compose/jetpack-compose-performance` |
| `migrate-xml-views-to-jetpack-compose` | [SKILL.md](../.agents/skills/migrate-xml-views-to-jetpack-compose/SKILL.md) | `jetpack-compose/migration/migrate-xml-views-to-jetpack-compose` |
| `media3-cast-integration` | [SKILL.md](../.agents/skills/media3-cast-integration/SKILL.md) | `media/media3-cast-integration` |
| `navigation-3` | [SKILL.md](../.agents/skills/navigation-3/SKILL.md) | `navigation/navigation-3` |
| `navigation-event` | [SKILL.md](../.agents/skills/navigation-event/SKILL.md) | `navigation/navigation-event` |
| `anr-audit-and-fix` | [SKILL.md](../.agents/skills/anr-audit-and-fix/SKILL.md) | `performance/anr-audit-and-fix` |
| `r8-analyzer` | [SKILL.md](../.agents/skills/r8-analyzer/SKILL.md) | `performance/r8-analyzer` |
| `engage-sdk-integration` | [SKILL.md](../.agents/skills/engage-sdk-integration/SKILL.md) | `play/engage-sdk-integration` |
| `play-billing-library-version-upgrade` | [SKILL.md](../.agents/skills/play-billing-library-version-upgrade/SKILL.md) | `play/play-billing-library-version-upgrade` |
| `play-policy-insights` | [SKILL.md](../.agents/skills/play-policy-insights/SKILL.md) | `play/play-policy-insights` |
| `android-profiler` | [SKILL.md](../.agents/skills/android-profiler/SKILL.md) | `profilers/android-profiler` |
| `android-intent-security` | [SKILL.md](../.agents/skills/android-intent-security/SKILL.md) | `security/android-intent-security` |
| `android-permissions-security` | [SKILL.md](../.agents/skills/android-permissions-security/SKILL.md) | `security/android-permissions-security` |
| `edge-to-edge` | [SKILL.md](../.agents/skills/edge-to-edge/SKILL.md) | `system/edge-to-edge` |
| `glance-appwidget-updates-and-memory` | [SKILL.md](../.agents/skills/glance-appwidget-updates-and-memory/SKILL.md) | `system/glance-appwidget-updates-and-memory` |
| `glance-canonical-layouts` | [SKILL.md](../.agents/skills/glance-canonical-layouts/SKILL.md) | `system/glance-canonical-layouts` |
| `testing-setup` | [SKILL.md](../.agents/skills/testing-setup/SKILL.md) | `testing/testing-setup` |
| `leanback-to-compose-tv-migration` | [SKILL.md](../.agents/skills/leanback-to-compose-tv-migration/SKILL.md) | `tv/leanback-to-compose-tv-migration` |
| `wear-compose-m3` | [SKILL.md](../.agents/skills/wear-compose-m3/SKILL.md) | `wear/wear-compose-m3` |
| `wear-widgets` | [SKILL.md](../.agents/skills/wear-widgets/SKILL.md) | `wear/wear-widgets` |
| `display-glasses-with-jetpack-compose-glimmer` | [SKILL.md](../.agents/skills/display-glasses-with-jetpack-compose-glimmer/SKILL.md) | `xr/display-glasses-with-jetpack-compose-glimmer` |
| `projected-hardware` | [SKILL.md](../.agents/skills/projected-hardware/SKILL.md) | `xr/projected-hardware` |
