package com.uber.okbuck.core.dependency

import com.uber.okbuck.OkBuckGradlePlugin
import com.uber.okbuck.core.model.base.Store
import com.uber.okbuck.core.util.FileUtil
import org.apache.commons.io.IOUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarFile

class DependencyCache {

    private final File cacheDir
    private final Project rootProject
    private final boolean fetchSources
    private final Store lintJars
    private final Store processors
    private final Store proguardConfigs
    private final Store sources

    private final Set<File> created = new HashSet<>()
    private final Set<ExternalDependency> requested = ConcurrentHashMap.newKeySet()

    DependencyCache(Project project, File cacheDir) {
        this.rootProject = project.rootProject
        this.cacheDir = cacheDir
        this.fetchSources = rootProject.okbuck.intellij.sources

        sources = new Store(new File("${OkBuckGradlePlugin.OKBUCK_STATE_DIR}/SOURCES"))
        processors = new Store(new File("${OkBuckGradlePlugin.OKBUCK_STATE_DIR}/PROCESSORS"))
        lintJars = new Store(new File("${OkBuckGradlePlugin.OKBUCK_STATE_DIR}/LINT_JARS"))
        proguardConfigs = new Store(new File("${OkBuckGradlePlugin.OKBUCK_STATE_DIR}/PROGUARD_CONFIGS"))
    }

    void finalizeDeps() {
        sources.persist()
        processors.persist()
        lintJars.persist()
        proguardConfigs.persist()
        cleanup()
    }

    void cleanup() {
        requested.each {
            DependencyUtils.validate(rootProject, it)
        }
        (cacheDir.listFiles(new FileFilter() {

            @Override
            boolean accept(File pathname) {
                return pathname.isFile() && (pathname.name.endsWith(".jar")
                        || pathname.name.endsWith(".aar")
                        || pathname.name.endsWith(".pro"))
            }
        }) - created).each { File f ->
            Files.deleteIfExists(f.toPath())
        }
    }

    String get(ExternalDependency dependency, boolean resolveOnly = false) {
        File cachedCopy = new File(cacheDir, dependency.getCacheName(!resolveOnly))
        String key = FileUtil.getRelativePath(rootProject.projectDir, cachedCopy)
        createLink(Paths.get(key), dependency.depFile.toPath())

        if (!resolveOnly && fetchSources) {
            getSources(dependency)
        }
        requested.add(dependency)

        return key
    }

    /**
     * Gets the sources jar path for a dependency if it exists.
     *
     * @param dependency The dependency.
     */
    void getSources(ExternalDependency dependency) {
        String key = dependency.cacheName
        String sourcesJarPath = sources.get(key)
        if (sourcesJarPath == null || !Files.exists(Paths.get(sourcesJarPath))) {
            sourcesJarPath = ""
            if (!DependencyUtils.isWhiteListed(dependency.depFile)) {
                String sourcesJarName = dependency.getSourceCacheName(false)
                File sourcesJar = new File(dependency.depFile.parentFile, sourcesJarName)

                if (!Files.exists(sourcesJar.toPath())) {
                    if (!dependency.isLocal) {
                        // Most likely jar is in Gradle/Maven cache directory, try to find sources jar in "jar/../..".
                        def sourceJars = rootProject.fileTree(
                                dir: dependency.depFile.parentFile.parentFile.absolutePath,
                                includes: ["**/${sourcesJarName}"]) as List

                        if (sourceJars.size() == 1) {
                            sourcesJarPath = sourceJars[0].absolutePath
                        } else if (sourceJars.size() > 1) {
                            throw new IllegalStateException("Found multiple source jars: ${sourceJars} for ${dependency}")
                        }
                    }
                }
            }
            sources.set(key, sourcesJarPath)
        }
        if (sourcesJarPath) {
            createLink(new File(cacheDir, dependency.getSourceCacheName(true)).toPath(), Paths.get(sourcesJarPath))
        }
    }

    /**
     * Get the list of annotation processor classes provided by a dependency.
     *
     * @param dependency The dependency
     * @return The list of annotation processor classes available in the manifest
     */
    List<String> getAnnotationProcessors(ExternalDependency dependency) {
        String key = dependency.cacheName
        String processorsList = processors.get(key)
        if (processorsList == null) {
            JarFile jarFile = new JarFile(dependency.depFile)
            JarEntry jarEntry = (JarEntry) jarFile.getEntry("META-INF/services/javax.annotation.processing.Processor")
            if (jarEntry) {
                List<String> processorClasses = IOUtils.toString(jarFile.getInputStream(jarEntry))
                        .trim().split("\\n").findAll { String entry ->
                    !entry.startsWith('#') && !entry.trim().empty // filter out comments and empty lines
                }
                processorsList = processorClasses.join(",")
            } else {
                processorsList = ""
            }
            processors.set(key, processorsList)
        }

        if (processorsList == "") {
            return Collections.emptyList()
        } else {
            return processorsList.split(",")
        }
    }

    /**
     * Get the packaged lint jar of an aar dependency if any.
     *
     * @param dependency The depenency
     * @return path to the lint jar in the cache.
     */
    String getLintJar(ExternalDependency dependency) {
        return getAarEntry(dependency, lintJars, "lint.jar", "-lint.jar")
    }

    /**
     * Get the packaged proguard config of an aar dependency if any.
     *
     * @param dependency The depenency
     * @return path to the proguard config in the cache.
     */
    File getProguardConfig(ExternalDependency dependency) {
        String entry = getAarEntry(dependency, proguardConfigs, "proguard.txt", "-proguard.pro")
        if (entry) {
            return new File(entry)
        } else {
            return null
        }
    }

    void build(Configuration configuration) {
        build(Collections.singleton(configuration))
    }

    /**
     * Use this method to populate dependency caches of tools/languages etc. This is not meant to be used across
     * multiple threads/gradle task executions which can run in parallel. This method is fully synchronous.
     *
     * @param configurations The set of configurations to materialize into the dependency cache
     */
    void build(Set<Configuration> configurations) {
        configurations.each { Configuration configuration ->
            configuration.incoming.artifacts.each { ResolvedArtifactResult artifact ->
                ComponentIdentifier identifier = artifact.id.componentIdentifier
                if (identifier instanceof ProjectComponentIdentifier) {
                    return
                }
                ExternalDependency dependency
                if (identifier instanceof ModuleComponentIdentifier) {
                    dependency = new ExternalDependency(
                            identifier.group,
                            identifier.module,
                            identifier.version,
                            artifact.file)
                } else {
                    dependency = ExternalDependency.fromLocal(artifact.file)
                }
                get(dependency, true)
            }
        }
        cleanup()
    }

    void createLink(Path link, Path target) {
        created.add(link.toAbsolutePath().toFile())
        if (!Files.exists(link) && Files.exists(target)) {
            try {
                Files.createSymbolicLink(link, target)
            } catch (IOException ignored) { }
        }
    }

    private String getAarEntry(ExternalDependency dependency, Store store, String entry, String suffix) {
        if (!dependency.depFile.name.endsWith(".aar")) {
            return null
        }

        String key = dependency.cacheName
        String entryPath = store.get(key)
        if (entryPath == null || !Files.exists(Paths.get(entryPath))) {
            entryPath = ""
            File cachedCopy = new File(cacheDir, key)
            File packagedEntry = getPackagedFile(cachedCopy, entry, suffix)
            if (packagedEntry != null) {
                entryPath = FileUtil.getRelativePath(rootProject.projectDir, packagedEntry)
            }
            store.set(key, entryPath)
        }
        if (entryPath) {
            created.add(new File(rootProject.projectDir, entryPath))
        }

        return entryPath
    }

    private static File getPackagedFile(File aar, String entry, String suffix) {
        File packagedFile = new File(aar.parentFile, aar.name.replaceFirst(/\.aar$/, suffix))
        if (Files.exists(packagedFile.toPath())) {
            return packagedFile
        }

        FileSystem zipFile = FileSystems.newFileSystem(aar.toPath(), null)
        Path packagedPath = zipFile.getPath(entry)
        if (Files.exists(packagedPath)) {
            Files.copy(packagedPath, packagedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return packagedFile
        } else {
            return null
        }
    }
}
