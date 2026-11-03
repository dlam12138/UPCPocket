package com.upcpocket.app.platform

/**
 * 查询途中数据被清除，迟到的成功结果必须丢弃。
 *
 * 放在 platform 是因为余额与电量两个独立业务都要用它；它不携带任何业务字段。
 */
class RefreshInvalidatedException : java.io.IOException("本次查询已失效")
