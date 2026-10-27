package me.sorihiko.dango.module

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModuleMeta(val category: Category, val name: String)
