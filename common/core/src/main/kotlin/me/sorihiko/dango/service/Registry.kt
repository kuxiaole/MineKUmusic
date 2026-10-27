package me.sorihiko.dango.service

interface Registry<T> {
    fun register(item: T)
    fun unregister(item: T)
}