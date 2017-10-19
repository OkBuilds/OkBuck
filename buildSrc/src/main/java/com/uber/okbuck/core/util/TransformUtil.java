package com.uber.okbuck.core.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.uber.okbuck.OkBuckGradlePlugin;
import com.uber.okbuck.composer.base.BuckRuleComposer;
import com.uber.okbuck.core.dependency.DependencyCache;
import com.uber.okbuck.core.dependency.DependencyUtils;
import com.uber.okbuck.core.model.android.AndroidAppTarget;
import com.uber.okbuck.core.model.base.Scope;
import com.uber.okbuck.template.config.TransformBuckFile;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class TransformUtil {

    public static final String TRANSFORM_CACHE = OkBuckGradlePlugin.DEFAULT_CACHE_PATH + "/transform";
    public static final String TRANSFORM_RULE = "//" + TRANSFORM_CACHE + ":okbuck_transform";

    public static final String CONFIGURATION_TRANSFORM = "transform";
    private static final String TRANSFORM_FOLDER = "transform/";
    private static final String TRANSFORM_JAR = "transform-cli-1.1.0.jar";

    private static final String OPT_TRANSFORM_CLASS = "transform";
    private static final String OPT_CONFIG_FILE = "configFile";
    private static final String PREFIX = "java -Dokbuck.inJarsDir=$IN_JARS_DIR -Dokbuck.outJarsDir=$OUT_JARS_DIR "
            + "-Dokbuck.androidBootClasspath=$ANDROID_BOOTCLASSPATH ";
    private static final String SUFFIX = "-cp $(location " + TransformUtil.TRANSFORM_RULE + ") "
            + "com.uber.okbuck.transform.CliTransform; ";

    private TransformUtil() { }

    public static void fetchTransformDeps(Project project) {
        Set<Configuration> transformConfigurations =
                ImmutableSet.of(project.getConfigurations().getByName(CONFIGURATION_TRANSFORM));

        File cacheDir = DependencyUtils.createCacheDir(project, TRANSFORM_CACHE);
        DependencyCache dependencyCache = new DependencyCache(project, cacheDir);
        dependencyCache.build(transformConfigurations);

        Scope transformScope = Scope.from(
                project,
                Collections.singleton(CONFIGURATION_TRANSFORM),
                ImmutableSet.of(),
                null,
                ImmutableList.of(),
                dependencyCache);

        Set<String> targetDeps = BuckRuleComposer.targets(transformScope.getTargetDeps())
                .stream()
                .map(s -> "'" + s + "'")
                .collect(MoreCollectors.toImmutableSet());

        new TransformBuckFile().targetDeps(targetDeps).render(cacheDir.toPath().resolve("BUCK"));
        FileUtil.copyResourceToProject(TRANSFORM_FOLDER + TRANSFORM_JAR, new File(cacheDir, TRANSFORM_JAR));
    }

    public static String getBashCommand(AndroidAppTarget target) {
        return String.join(" ", target.getTransforms().stream().map(
                TransformUtil::getBashCommand
        ).collect(Collectors.toList()));
    }

    private static String getBashCommand(Map<String, String> options) {
        String transformClass = options.get(OPT_TRANSFORM_CLASS);
        String configFile = options.get(OPT_CONFIG_FILE);

        StringBuilder bashCmd = new StringBuilder(PREFIX);
        if (transformClass != null) {
            bashCmd.append("-Dokbuck.transformClass=");
            bashCmd.append(transformClass);
            bashCmd.append(" ");
        }
        if (configFile != null) {
            bashCmd.append("-Dokbuck.configFile=");
            bashCmd.append(configFile);
            bashCmd.append(" ");
        }
        bashCmd.append(SUFFIX);
        return bashCmd.toString();
    }
}
