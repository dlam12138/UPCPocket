plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * M0 依赖边界检查：feature 实现之间、core 到 feature、模块到 app 的反向依赖
 * 都在这里失败。新增模块时同步更新白名单，不允许用“全部塞进公共模块”绕过。
 */
tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "检查 Gradle 模块依赖方向（M0 架构约束）"

    doLast {
        val violations = mutableListOf<String>()

        fun projectDependenciesOf(project: Project): Set<String> {
            val result = mutableSetOf<String>()
            project.configurations.forEach { configuration ->
                configuration.allDependencies.forEach { dependency ->
                    if (dependency is org.gradle.api.artifacts.ProjectDependency) {
                        val target = dependency.dependencyProject.path
                        // AGP 会在部分可消费配置上生成指向自身的依赖，不构成架构边。
                        if (target != project.path) result += target
                    }
                }
            }
            return result
        }

        subprojects.forEach { current ->
            val path = current.path
            val dependencies = projectDependenciesOf(current)
            val internal = dependencies.filter { it.startsWith(":") }

            when {
                path.startsWith(":feature:") -> internal.forEach { dependency ->
                    if (dependency == ":app") {
                        violations += "$path 不能依赖 :app"
                    } else if (
                        dependency.startsWith(":feature:") &&
                        dependency != path &&
                        !dependency.startsWith("$path:")
                    ) {
                        violations += "$path 不能依赖其他 feature 实现：$dependency"
                    }
                }

                path.startsWith(":core:") -> internal.forEach { dependency ->
                    if (dependency == ":app" || dependency.startsWith(":feature:")) {
                        violations += "$path 不能依赖 $dependency"
                    }
                }
            }

            if (path == ":core:plugin-api" && internal.isNotEmpty()) {
                violations += ":core:plugin-api 必须保持无内部依赖，实际：${internal.sorted()}"
            }
            if (path == ":core:account-api" && internal.isNotEmpty()) {
                violations += ":core:account-api 必须保持无内部依赖，实际：${internal.sorted()}"
            }
            if (path == ":core:plugin-runtime") {
                internal.filter { it != ":core:plugin-api" }.forEach {
                    violations += ":core:plugin-runtime 只允许依赖 :core:plugin-api，实际包含 $it"
                }
            }
            if (path == ":core:plugin-ui") {
                internal.filter { it != ":core:plugin-api" }.forEach {
                    violations += ":core:plugin-ui 只允许依赖 :core:plugin-api，实际包含 $it"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "模块依赖边界检查失败：\n" + violations.distinct().sorted().joinToString("\n"),
            )
        }
        logger.lifecycle("模块依赖边界检查通过")
    }
}
