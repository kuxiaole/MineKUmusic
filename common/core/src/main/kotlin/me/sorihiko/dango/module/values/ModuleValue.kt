package me.sorihiko.dango.module.values

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModuleValue(
    val name: String = "",
    val desc: String = ""
)