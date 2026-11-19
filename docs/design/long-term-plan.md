# UPC Pocket 长期计划

## MyGo 前端备选

**待收集社区意见。** 继续收集 iOS 支持需求。MyGo 仅作为应用内界面的备选。若评估双端共用界面，可先用单页原型验证与 Kotlin 的桥接、真机体验和维护成本。 [MyGo 移动端文档](https://github.com/egoist/mygo/blob/v0.10.0/docs/mobile/README.md)

校园接口、业务规则和数据层沿用 Kotlin，双端需要复用的逻辑可逐步抽到 Kotlin Multiplatform。桌面小组件、通知、打包和发布按平台适配。

## 桌面小组件玻璃质感

**长期项目。** 2.0.8 的半透明渐变与固定磨砂候选均已试装，通透感和边缘光影与参考图仍有差距。 [Issue #6](https://github.com/dlam12138/UPCPocket/issues/6) · [2.0.8 试装记录](../releases/2.0.8-development.md#固定磨砂材质)

后续以真机桌面对照为验收依据，重点检查通透感、磨砂层次和文字可读性。
