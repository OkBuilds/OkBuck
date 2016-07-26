package com.github.okbuilds.core.dependency

import com.github.okbuilds.core.util.FileUtil
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

class DependencyCache {

    final Project rootProject
    final File cacheDir
    private Map<VersionlessDependency, String> finalDepFiles = [:]
    private Map<VersionlessDependency, ExternalDependency> greatestVersions = [:]

    DependencyCache(Project rootProject, String cacheDirPath) {
        this.rootProject = rootProject
        cacheDir = new File(rootProject.projectDir, cacheDirPath)
        cacheDir.mkdirs()
    }

    void put(ExternalDependency dependency) {
        if (!isValid(dependency.depFile)) {
            throw new IllegalArgumentException("${dependency.depFile.absolutePath} is not a valid jar/aar file")
        }

        ExternalDependency externalDependency = greatestVersions.get(dependency)
        if (externalDependency == null || dependency.version > externalDependency.version) {
            greatestVersions.put(dependency, dependency)
        }
    }

    String get(ExternalDependency dependency) {
        ExternalDependency greatestVersion = greatestVersions.get(dependency)
        if (!finalDepFiles.containsKey(greatestVersion)) {
            File depFile = greatestVersion.depFile
            File cachedCopy = new File(cacheDir, "${DigestUtils.md5Hex(depFile.parentFile.absolutePath)}/${depFile.name}")
            if (!cachedCopy.exists()) {
                FileUtils.copyFile(depFile, cachedCopy)
            }
            String path = FileUtil.getRelativePath(rootProject.projectDir, cachedCopy)
            finalDepFiles.put(greatestVersion, path)

            File thirdPartyBuckFile = new File(cachedCopy.parentFile, "BUCK")
            if (!thirdPartyBuckFile.exists()) {
                copyThirdPartyBuckFile(cachedCopy.parentFile)
            }
        }

        return finalDepFiles.get(greatestVersion)
    }

    private static void copyThirdPartyBuckFile(File dstDir) {
        FileUtil.copyResourceToProject("thirdparty/BUCK_FILE", new File(dstDir, "BUCK"))
    }

    private static boolean isValid(File dep) {
        return (dep.name.endsWith(".jar") || dep.name.endsWith(".aar"))
    }
}
