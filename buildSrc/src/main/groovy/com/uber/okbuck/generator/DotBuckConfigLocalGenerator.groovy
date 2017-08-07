package com.uber.okbuck.generator

import com.uber.okbuck.composer.android.AndroidBuckRuleComposer
import com.uber.okbuck.config.DotBuckConfigLocalFile
import com.uber.okbuck.core.model.android.AndroidAppTarget
import com.uber.okbuck.core.model.base.ProjectType
import com.uber.okbuck.core.model.base.Target
import com.uber.okbuck.core.util.ProjectUtil
import com.uber.okbuck.extension.OkBuckExtension
import org.gradle.api.Project
import org.jetbrains.annotations.Nullable

final class DotBuckConfigLocalGenerator {

    private DotBuckConfigLocalGenerator() {}

    /**
     * generate {@link DotBuckConfigLocalFile}
     */
    static DotBuckConfigLocalFile generate(OkBuckExtension okbuck,
                                           @Nullable String groovyHome,
                                           @Nullable String kotlinHome,
                                           @Nullable String scalaHome,
                                           @Nullable String proguardJar,
                                           Set<String> defs) {
        Map<String, String> aliases = [:]
        okbuck.buckProjects.findAll { Project project ->
            ProjectUtil.getType(project) == ProjectType.ANDROID_APP
        }.each { Project project ->
            ProjectUtil.getTargets(project).each { String name, Target target ->
                aliases.put("${target.identifier.replaceAll(':', '-')}${name.capitalize()}" as String,
                        "//${target.path}:${AndroidBuckRuleComposer.bin((AndroidAppTarget) target)}" as String)
            }
        }

        return new DotBuckConfigLocalFile(aliases,
                okbuck.buildToolVersion,
                okbuck.target,
                groovyHome,
                kotlinHome,
                scalaHome,
                proguardJar,
                defs)
    }
}
