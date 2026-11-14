# UPC-MCP 来源

只读协议与解析语义参考 [UPC-MCP](https://github.com/dlam12138/UPC-MCP)，固定提交
`948f5f21ad7a12fd94b53403a07ac867209f7700`。MIT，Copyright (c) 2026 3beuml；完整许可见
[UPC-MCP-MIT.txt](UPC-MCP-MIT.txt)、`feature/shiguang/src/main/resources/META-INF/UPC-MCP-LICENSE.txt` 与
`feature/service-portal/src/main/resources/META-INF/UPC-MCP-SERVICE-PORTAL-LICENSE.txt`。两个 Android 模块使用不同资源名，避免打包时重复的 `META-INF` 路径冲突。

## 石光

参考 `src/upc_mcp/shiguang.py`。Android 移植仅保留活动列表/搜索、详情、本人参与和五模块
学分学时。未移植报名、取消、签到、导入、Python/MCP 服务、DPAPI 或桌面会话存储。解析器、
固定 GET 端点与合成契约测试归 `:feature:shiguang`；正常 CAS 认证归共享 `:core:upc-auth`。

## e站通

参考 `service_portal_protocol.py`、`service_portal_service.py`、`service_portal_tools.py`、
`auto_auth/providers/service_portal.py`、`auto_auth/cas.py` 和 `tests/test_service_portal_*.py`。
Android `:feature:service-portal` 保留事项目录/指南、本人待处理与在办的只读读取和相关解析语义；
CAS 认证由 Kotlin `:core:upc-auth` 适配，不嵌入 Python/MCP、Windows DPAPI 或上游持久化层。
业务结果只在内存中保留。e站通模块 44 项测试通过，相关认证/图书馆/宿主测试及独立审查已完成；实际签名 Release 中已核对两份独立 MIT 许可资源。最终签名包已在 Android 验证公开目录、指南、无结果搜索与本地分页；真实 CAS、验证码和本人队列未验证。实际范围见 [2.0.5 开发记录](../releases/2.0.5-development.md)。
