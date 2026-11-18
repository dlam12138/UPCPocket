package com.upcpocket.app.widget

/** 本机待办 RemoteViews widget; data rendering stays in the active Todo feature. */
class TodoWidgetProvider : BaseWidgetProvider() {
    override val variant: WidgetProviderVariant = WidgetProviderVariant.TODO
}
