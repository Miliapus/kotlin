/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle.importing

import com.intellij.build.FilePosition
import com.intellij.build.SyncViewManager
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.MessageEventImpl
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemEventDispatcher
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.KotlinIdeaGradleBundle
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.core.script.configuration.cache.CachedConfigurationInputs
import org.jetbrains.kotlin.idea.core.script.configuration.cache.ScriptConfigurationSnapshot
import org.jetbrains.kotlin.idea.scripting.gradle.GradleScriptInputsWatcher
import org.jetbrains.kotlin.idea.scripting.gradle.getGradleScriptInputsStamp
import org.jetbrains.kotlin.idea.scripting.gradle.saveGradleProjectRootsAfterImport
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import org.jetbrains.kotlin.scripting.resolve.VirtualFileScriptSource
import org.jetbrains.kotlin.scripting.resolve.adjustByDefinition
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.jdkHome
import kotlin.script.experimental.jvm.jvm

private const val gradle_build_script_errors_group = "Kotlin Build Script Errors"

fun saveScriptModels(
    resolverContext: ProjectResolverContext,
    models: List<KotlinDslScriptModel>
) {
    val task = resolverContext.externalSystemTaskId
    val project = task.findProject() ?: return
    val settings = resolverContext.settings ?: return

    val scriptConfigurations = mutableListOf<Pair<VirtualFile, ScriptConfigurationSnapshot>>()

    val syncViewManager = project.service<SyncViewManager>()
    val buildEventDispatcher =
        ExternalSystemEventDispatcher(task, syncViewManager)

    val javaHome = settings.javaHome?.let { File(it) }
    models.forEach { buildScript ->
        val scriptFile = File(buildScript.file)
        val virtualFile = VfsUtil.findFile(scriptFile.toPath(), true)!!

        val inputs = getGradleScriptInputsStamp(
            project,
            virtualFile,
            givenTimeStamp = buildScript.inputsTimeStamp
        )

        val definition = virtualFile.findScriptDefinition(project) ?: return@forEach

        val configuration =
            definition.compilationConfiguration.with {
                if (javaHome != null) {
                    jvm.jdkHome(javaHome)
                }
                defaultImports(buildScript.imports)
                dependencies(JvmDependency(buildScript.classPath.map {
                    File(
                        it
                    )
                }))
                ide.dependenciesSources(JvmDependency(buildScript.sourcePath.map {
                    File(
                        it
                    )
                }))
            }.adjustByDefinition(definition)

        scriptConfigurations.add(
            Pair(
                virtualFile,
                ScriptConfigurationSnapshot(
                    inputs
                        ?: CachedConfigurationInputs.OutOfDate,
                    listOf(),
                    ScriptCompilationConfigurationWrapper.FromCompilationConfiguration(
                        VirtualFileScriptSource(virtualFile),
                        configuration,
                    ),
                ),
            ),
        )

        buildScript.messages.forEach {
            val severity = when (it.severity) {
                KotlinDslScriptModel.Severity.WARNING -> MessageEvent.Kind.WARNING
                KotlinDslScriptModel.Severity.ERROR -> MessageEvent.Kind.ERROR
            }
            val position = it.position
            if (position == null) {
                buildEventDispatcher.onEvent(
                    task,
                    MessageEventImpl(
                        task,
                        severity,
                        gradle_build_script_errors_group,
                        it.text,
                        it.details
                    )
                )
            } else {
                buildEventDispatcher.onEvent(
                    task,
                    FileMessageEventImpl(
                        task,
                        severity,
                        gradle_build_script_errors_group,
                        it.text, it.details,
                        // 0-based line numbers
                        FilePosition(scriptFile, position.line - 1, position.column)
                    ),
                )
            }
        }
    }

    saveGradleProjectRootsAfterImport(
        scriptConfigurations.map { it.first.parent.path }.toSet()
    )

    project.service<ScriptConfigurationManager>().saveCompilationConfigurationAfterImport(scriptConfigurations)
    project.service<GradleScriptInputsWatcher>().clearState()
}