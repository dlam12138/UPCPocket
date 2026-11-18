package com.upcpocket.app.designsystem

/** Shared static backgrounds for RemoteViews widgets; independent from the in-app UI style. */
enum class WidgetAppearance(
    val backgroundResource: Int,
) {
    SOLID(R.drawable.widget_surface),
    TRANSLUCENT(R.drawable.widget_surface_translucent),
    GLASS(R.drawable.widget_surface_glass),
    FROSTED_GLASS(R.drawable.widget_surface_frosted_glass),
    ;

    fun textColorResource(colorResource: Int): Int =
        if (this != FROSTED_GLASS) {
            colorResource
        } else {
            when (colorResource) {
                R.color.widget_secondary -> R.color.widget_frosted_secondary
                R.color.widget_tertiary -> R.color.widget_frosted_tertiary
                R.color.widget_accent -> R.color.widget_frosted_accent
                R.color.widget_error -> R.color.widget_frosted_error
                else -> colorResource
            }
        }

    companion object {
        fun fromStoredValue(value: String?): WidgetAppearance =
            when (value) {
                SOLID.name -> SOLID
                TRANSLUCENT.name -> TRANSLUCENT
                GLASS.name -> GLASS
                else -> SOLID
            }
    }
}
