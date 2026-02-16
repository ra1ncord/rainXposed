package io.github.revenge.xposed.modules.base

import io.github.revenge.xposed.Module
import io.github.revenge.xposed.modules.annotations.RegisterMethod
import kotlinx.serialization.json.JsonElement

abstract class HookModule : Module() {
    val registeredFunctions = this::class
        .members
        .filter { it.annotations.any { annotation -> annotation is RegisterMethod } }
        .map { member ->
            val annotation = member.annotations.find { it is RegisterMethod } as RegisterMethod
            member.name to annotation.version
        }

    open fun getConstants(): Map<String, JsonElement> {
        return mapOf()
    }
}
