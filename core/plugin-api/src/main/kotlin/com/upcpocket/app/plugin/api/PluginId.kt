package com.upcpocket.app.plugin.api

/**
 * 稳定的插件标识。
 *
 * 插件 ID 是构建期装配、依赖声明与诊断输出共用的兼容契约，**不能**随 Kotlin
 * 类名、包名或页面标题变化。约定为小写点分名，例如 `card`、`power`、`library`。
 */
@JvmInline
value class PluginId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")

        fun parse(raw: String): PluginId {
            require(raw.matches(PATTERN)) {
                "非法插件 ID：只允许小写字母、数字、点和连字符，且不能以点开头/结尾"
            }
            return PluginId(raw)
        }
    }
}

/**
 * 能力标识，例如 `card.balance`。
 *
 * 与 [CapabilityKey] 绑定的字符串就是它；注册时声明、启动时核对，避免插件
 * 偷偷提供未声明能力。
 */
@JvmInline
value class CapabilityId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+")

        fun parse(raw: String): CapabilityId {
            require(raw.matches(PATTERN)) {
                "非法能力 ID：需要小写点分名，例如 card.balance"
            }
            return CapabilityId(raw)
        }
    }
}

/**
 * 类型化能力键：`id` 是稳定契约，`type` 只用于运行时校验。
 *
 * 同一 ID 只能对应一种类型；插件模块导出常量键，消费者不接触字符串地图。
 */
class CapabilityKey<T : Any>(
    val id: String,
    val type: Class<T>,
) {
    init {
        CapabilityId.parse(id)
    }

    override fun equals(other: Any?): Boolean = other is CapabilityKey<*> && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "CapabilityKey($id)"
}

inline fun <reified T : Any> capabilityKey(id: String): CapabilityKey<T> =
    CapabilityKey(id, T::class.java)
