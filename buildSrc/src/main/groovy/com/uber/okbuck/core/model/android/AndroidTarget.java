package com.uber.okbuck.core.model.android;

import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.api.BaseVariant;
import com.android.build.gradle.api.TestVariant;
import com.android.build.gradle.api.UnitTestVariant;
import com.android.build.gradle.internal.api.TestedVariant;
import com.android.builder.core.VariantType;
import com.android.builder.model.ClassField;
import com.android.builder.model.LintOptions;
import com.android.builder.model.SourceProvider;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.MergingReport;
import com.android.utils.ILogger;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.uber.okbuck.OkBuckGradlePlugin;
import com.uber.okbuck.core.model.base.AnnotationProcessorCache;
import com.uber.okbuck.core.model.base.RuleType;
import com.uber.okbuck.core.model.base.Scope;
import com.uber.okbuck.core.model.jvm.JvmTarget;
import com.uber.okbuck.core.model.jvm.TestOptions;
import com.uber.okbuck.core.util.FileUtil;
import com.uber.okbuck.core.util.ProjectUtil;
import com.uber.okbuck.core.util.XmlUtil;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.jetbrains.kotlin.gradle.internal.AndroidExtensionsExtension;
import org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.uber.okbuck.core.util.KotlinUtil.KOTLIN_ANDROID_EXTENSIONS_MODULE;
import static com.uber.okbuck.core.util.KotlinUtil.KOTLIN_KAPT_PLUGIN;

/**
 * An Android target
 */
public abstract class AndroidTarget extends JvmTarget {

    private static final EmptyLogger EMPTY_LOGGER = new EmptyLogger();

    private static final String DEFAULT_SDK = "1";
    private final String applicationId;
    private final String applicationIdSuffix;
    private final String versionName;
    private final Integer versionCode;
    private final String minSdk;
    private final String targetSdk;
    private final boolean debuggable;
    private final boolean generateR2;
    private final String genDir;
    private final boolean isKotlin;
    private final boolean isKapt;
    private final boolean hasKotlinAndroidExtensions;
    private final boolean hasExperimentalKotlinAndroidExtensions;
    private final boolean lintExclude;
    private final boolean testExclude;
    private String manifestPath;
    private String packageName;
    private boolean isTest;

    public AndroidTarget(Project project, String name, boolean isTest) {
        super(project, name);

        this.isTest = isTest;

        String suffix = "";
        if (getBaseVariant().getMergedFlavor().getApplicationIdSuffix() != null) {
            suffix += getBaseVariant().getMergedFlavor().getApplicationIdSuffix();
        }

        if (getBaseVariant().getBuildType().getApplicationIdSuffix() != null) {
            suffix += getBaseVariant().getBuildType().getApplicationIdSuffix();
        }

        applicationIdSuffix = suffix;
        if (isTest) {
            String applicationIdString = StringGroovyMethods.minus(
                    StringGroovyMethods.minus(getBaseVariant().getApplicationId(), ".test"),
                    applicationIdSuffix);
            applicationId = StringGroovyMethods.minus(applicationIdString, applicationIdSuffix);
        } else {
            applicationId = StringGroovyMethods.minus(getBaseVariant().getApplicationId(),
                    applicationIdSuffix);
        }

        versionName = getBaseVariant().getMergedFlavor().getVersionName();
        versionCode = getBaseVariant().getMergedFlavor().getVersionCode();

        debuggable = getBaseVariant().getBuildType().isDebuggable();

        // Butterknife support
        generateR2 = project.getPlugins().hasPlugin("com.jakewharton.butterknife");

        // Create gen dir
        genDir = Paths.get(OkBuckGradlePlugin.OKBUCK_GEN, getPath(), name).toString();
        FileUtil.copyResourceToProject("gen/BUCK_FILE",
                getRootProject().file(genDir).toPath().resolve(OkBuckGradlePlugin.BUCK).toFile());

        // Check if kotlin
        isKotlin = project.getPlugins().hasPlugin(KotlinAndroidPluginWrapper.class);
        isKapt = project.getPlugins().hasPlugin(KOTLIN_KAPT_PLUGIN);
        hasKotlinAndroidExtensions = project.getPlugins()
                .hasPlugin(KOTLIN_ANDROID_EXTENSIONS_MODULE);

        // Check if any rules are excluded
        lintExclude = getProp(getOkbuck().lintExclude, ImmutableSet.of()).contains(name);
        testExclude = getProp(getOkbuck().testExclude, ImmutableSet.of()).contains(name);

        boolean hasKotlinExtension;
        try {
            AndroidExtensionsExtension androidExtensions =
                    project.getExtensions().getByType(AndroidExtensionsExtension.class);
            hasKotlinExtension =
                    hasKotlinAndroidExtensions && androidExtensions.isExperimental();
        } catch (Exception ignored) {
            hasKotlinExtension = false;
        }
        hasExperimentalKotlinAndroidExtensions = hasKotlinExtension;

        if (getBaseVariant().getMergedFlavor().getMinSdkVersion() == null
                || getBaseVariant().getMergedFlavor().getTargetSdkVersion() == null) {
            minSdk = targetSdk = DEFAULT_SDK;
            throw new IllegalStateException("module `" + project.getName()
                    + "` must specify minSdkVersion and targetSdkVersion in build.gradle");
        } else {
            minSdk = getBaseVariant().getMergedFlavor().getMinSdkVersion().getApiString();
            targetSdk = getBaseVariant().getMergedFlavor().getTargetSdkVersion().getApiString();
        }

    }

    public AndroidTarget(Project project, String name) {
        this(project, name, false);
    }

    protected abstract BaseVariant getBaseVariant();

    protected abstract ManifestMerger2.MergeType getMergeType();

    @Override
    public Scope getMain() {
        return Scope.from(
                getProject(),
                getBaseVariant().getRuntimeConfiguration(),
                getSources(getBaseVariant()),
                getJavaResources(getBaseVariant()),
                getJavaCompilerOptions(getBaseVariant()));
    }

    @Override
    public Scope getTest() {
        return Scope.from(
                getProject(),
                getUnitTestVariant() != null ? getUnitTestVariant().getRuntimeConfiguration() :
                        null,
                getUnitTestVariant() != null ? getSources(getUnitTestVariant()) : ImmutableSet.of(),
                getJavaResources(getUnitTestVariant()),
                getJavaCompilerOptions(getUnitTestVariant()));
    }

    @Override
    public List<Scope> getAptScopes() {
        Configuration configuration = getConfigurationFromVariant(getBaseVariant());
        AnnotationProcessorCache apCache = ProjectUtil.getAnnotationProcessorCache(getProject());
        return apCache.getAnnotationProcessorScopes(getProject(), configuration);
    }

    @Override
    public List<Scope> getTestAptScopes() {
        Configuration configuration = getConfigurationFromVariant(getUnitTestVariant());
        AnnotationProcessorCache apCache = ProjectUtil.getAnnotationProcessorCache(getProject());
        return apCache.getAnnotationProcessorScopes(getProject(), configuration);
    }

    @Override
    public Scope getApt() {
        Configuration configuration = getConfigurationFromVariant(getBaseVariant());
        return getAptScopeForConfiguration(configuration);
    }

    @Override
    public Scope getTestApt() {
        Configuration configuration = getConfigurationFromVariant(getUnitTestVariant());
        return getAptScopeForConfiguration(configuration);
    }

    @Override
    public Scope getProvided() {
        return Scope.from(getProject(), getBaseVariant().getCompileConfiguration());
    }

    @Override
    public Scope getTestProvided() {
        return Scope.from(getProject(), DefaultGroovyMethods.asBoolean(getUnitTestVariant()) ?
                getUnitTestVariant().getCompileConfiguration() : null);
    }

    @Override
    public LintOptions getLintOptions() {
        return getAndroidExtension().getLintOptions();
    }

    public boolean getRobolectricEnabled() {
        return getOkbuck().getTestExtension().robolectric && !testExclude;
    }

    public boolean getLintEnabled() {
        return !getOkbuck().getLintExtension().disabled && !lintExclude;
    }

    @Override
    public String getSourceCompatibility() {

        return JvmTarget.javaVersion(
                getAndroidExtension().getCompileOptions().getSourceCompatibility());
    }

    @Override
    public String getTargetCompatibility() {
        return JvmTarget.javaVersion(
                getAndroidExtension().getCompileOptions().getTargetCompatibility());
    }

    @Override
    public TestOptions getTestOptions() {
        Optional<Test> optionalTest = getProject().getTasks()
                .withType(Test.class)
                .stream()
                .filter(test -> test.getName()
                        .equals(VariantType.UNIT_TEST.getPrefix() +
                                StringUtils.capitalize(getName()) +
                                VariantType.UNIT_TEST.getSuffix())
                )
                .findFirst();

        List<String> jvmArgs = optionalTest.map(Test::getAllJvmArgs)
                .orElseGet(Collections::<String>emptyList);
        Map<String, Object> env = optionalTest.map(Test::getEnvironment)
                .orElseGet(Collections::emptyMap);

        System.getenv().keySet().forEach(env::remove);

        return new TestOptions(jvmArgs, env);
    }

    List<String> getBuildConfigFields() {
        List<String> buildConfig = new ArrayList<>();

        if (isTest) {
            buildConfig.add(String.format(
                    "String APPLICATION_ID = \"%s%s.test\"", applicationId, applicationIdSuffix));
        } else {
            buildConfig.add(String.format(
                    "String APPLICATION_ID = \"%s%s\"", applicationId, applicationIdSuffix));
        }
        buildConfig.add(String.format("String BUILD_TYPE = \"%s\"", getBuildType()));
        buildConfig.add(String.format("String FLAVOR = \"%s\"", getFlavor()));

        if (versionCode != null) {
            buildConfig.add(String.format("int VERSION_CODE = %s", versionCode));
        }
        if (versionName != null) {
            buildConfig.add(String.format("String VERSION_NAME = \"%s\"", versionName));
        }

        Map<String, ClassField> extraBuildConfig = new HashMap<>();
        extraBuildConfig.putAll(getBaseVariant().getMergedFlavor().getBuildConfigFields());
        extraBuildConfig.putAll(getBaseVariant().getBuildType().getBuildConfigFields());

        buildConfig.addAll(
                extraBuildConfig
                        .keySet()
                        .stream()
                        .sorted()
                        .map(key -> {
                            ClassField classField = extraBuildConfig.get(key);
                            return String.format("%s %s = %s",
                                    classField.getType(), key, classField.getValue());
                        })
                        .collect(Collectors.toList()));

        return buildConfig;
    }

    String getFlavor() {
        return getBaseVariant().getFlavorName();
    }

    String getBuildType() {
        return getBaseVariant().getBuildType().getName();
    }

    Set<String> getResDirs() {
        return getBaseVariant()
                .getSourceSets()
                .stream()
                .map(i -> getAvailable(i.getResDirectories()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    /**
     * Returns a map of each resource directory to its corresponding variant
     */
    Map<String, String> getResVariantDirs() {
        Map<String, String> variantDirs = new HashMap<>();
        for (SourceProvider provider : getBaseVariant().getSourceSets()) {
            for (String dir : getAvailable(provider.getResDirectories())) {
                variantDirs.put(dir, provider.getName());
            }
        }
        return variantDirs;
    }

    public Set<String> getAssetDirs() {
        return getBaseVariant()
                .getSourceSets()
                .stream()
                .map(i -> getAvailable(i.getAssetsDirectories()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }


    Set<String> getAidl() {
        return getBaseVariant()
                .getSourceSets()
                .stream()
                .map(i -> getAvailable(i.getAidlDirectories()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    Set<String> getJniLibs() {
        return getBaseVariant()
                .getSourceSets()
                .stream()
                .map(i -> getAvailable(i.getJniLibsDirectories()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    public String getPackage() throws ManifestMerger2.MergeFailureException, IOException {
        if (packageName == null) {
            ensureManifest();
        }

        return packageName;
    }

    public String getManifest() throws ManifestMerger2.MergeFailureException, IOException {
        if (manifestPath == null) {
            ensureManifest();
        }

        return manifestPath;
    }

    Document processManifestXml(Document manifestXml) {
        getSdkNode(manifestXml, minSdk, targetSdk);

        return manifestXml;
    }

    private void ensureManifest() throws IOException, ManifestMerger2.MergeFailureException {
        Set<String> manifests = getBaseVariant()
                .getSourceSets()
                .stream()
                .map(SourceProvider::getManifestFile)
                .map(file -> getAvailable(ImmutableSet.of(file)))
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Nothing to merge
        if (manifests.isEmpty()) {
            return;
        }

        File mergedManifest = getGenPath("AndroidManifest.xml");
        mergedManifest.getParentFile().mkdirs();
        mergedManifest.createNewFile();

        File mainManifest = getProject().file(manifests.iterator().next());

        if (manifests.size() == 1 && getMergeType() == ManifestMerger2.MergeType.LIBRARY) {
            // No need to merge for libraries
            parseManifest(
                    Files.lines(mainManifest.toPath())
                            .collect(Collectors.joining(System.lineSeparator())),
                    mergedManifest);
        } else {
            // always merge if more than one manifest or its an application
            List<File> secondaryManifests =
                    manifests
                            .stream()
                            .map(i -> getProject().file(i))
                            .collect(Collectors.toList());

            secondaryManifests.remove(mainManifest);

            // errors are reported later
            MergingReport report =
                    ManifestMerger2.newMerger(mainManifest, EMPTY_LOGGER, getMergeType())
                            .addFlavorAndBuildTypeManifests(
                                    secondaryManifests.toArray(new File[secondaryManifests.size()]))
                            .withFeatures(
                                    ManifestMerger2.Invoker.Feature.NO_PLACEHOLDER_REPLACEMENT) // handled by buck
                            .merge();

            if (report.getResult().isSuccess()) {
                parseManifest(report.getMergedDocument(MergingReport.MergedManifestKind.MERGED),
                        mergedManifest);
            } else {
                throw new IllegalStateException(
                        report
                                .getLoggingRecords()
                                .stream()
                                .map(i -> String.format("%s: %s at %s",
                                        i.getSeverity(), i.getMessage(), i.getSourceLocation()))
                                .collect(Collectors.joining(System.lineSeparator())));
            }
        }
        manifestPath = FileUtil.getRelativePath(getProject().getRootDir(), mergedManifest);
    }

    private void parseManifest(String originalManifest, File mergedManifest) {
        Document manifestXml = XmlUtil.loadXml(originalManifest);
        packageName = manifestXml.getDocumentElement().getAttribute("package").trim();

        Document processedManifest = processManifestXml(manifestXml);

        XmlUtil.writeToXml(processedManifest, mergedManifest);
    }

    static List<String> getJavaCompilerOptions(BaseVariant baseVariant) {
        if (baseVariant != null && baseVariant.getJavaCompiler() instanceof JavaCompile) {
            List<String> options = ((JavaCompile) baseVariant.getJavaCompiler())
                    .getOptions().getCompilerArgs();

            // Remove options added by apt plugin since they are handled by apt scope separately
            filterOptions(options, ImmutableList.of("-s", "-processorpath"));
            return options;
        } else {
            return ImmutableList.of();
        }
    }

    static void filterOptions(List<String> options, List<String> remove) {
        remove.forEach(key -> {
            int index = options.indexOf(key);
            if (index != -1) {
                options.remove(index + 1);
                options.remove(index);
            }
        });
    }

    private static void getSdkNode(Document manifestXml, String minSdk, String targetSdk) {
        NodeList usesSdkNodes = manifestXml.getElementsByTagName("uses-sdk");

        Element usesSdkNode;
        if (usesSdkNodes.getLength() == 0) {
            usesSdkNode = manifestXml.createElement("uses-sdk");
            manifestXml.getDocumentElement().appendChild(usesSdkNode);
        } else {
            usesSdkNode = (Element) usesSdkNodes.item(0);
        }
        usesSdkNode.setAttribute("android:minSdkVersion", minSdk);
        usesSdkNode.setAttribute("android:targetSdkVersion", targetSdk);
    }

    private UnitTestVariant getUnitTestVariant() {
        if (getBaseVariant() instanceof TestedVariant) {
            return ((TestedVariant) getBaseVariant()).getUnitTestVariant();
        } else {
            return null;
        }
    }

    TestVariant getInstrumentationTestVariant() {
        if (getBaseVariant() instanceof TestedVariant) {
            TestVariant testVariant = ((TestedVariant) getBaseVariant()).getTestVariant();
            if (testVariant != null) {
                Set<String> manifests = new HashSet<>();
                testVariant.getSourceSets().forEach(provider -> {
                    manifests.addAll(getAvailable(ImmutableSet.of(provider.getManifestFile())));
                });
                return manifests.isEmpty() ? null : testVariant;
            }
        }
        return null;
    }


    public File getGenPath(String... paths) {
        return getRootProject().file(Paths.get(genDir, paths).toFile());
    }

    public RuleType getRuleType() {
        if (isKotlin) {
            return RuleType.KOTLIN_ANDROID_LIBRARY;
        } else {
            return RuleType.ANDROID_LIBRARY;
        }

    }

    public RuleType getTestRuleType() {
        if (isKotlin) {
            return RuleType.KOTLIN_ROBOLECTRIC_TEST;
        } else {
            return RuleType.ROBOLECTRIC_TEST;
        }

    }

    private Configuration getConfigurationFromVariant(BaseVariant variant) {
        Configuration configuration = null;
        if (isKapt) {
            configuration = getProject().getConfigurations().getByName(
                    "kapt" + StringUtils.capitalize(getBaseVariant().getName()));
        } else if (variant != null) {
            configuration = variant.getAnnotationProcessorConfiguration();
        }
        return configuration;
    }

    public BaseExtension getAndroidExtension() {
        return (BaseExtension) getProject().getExtensions().getByName("android");
    }

    public Set<File> getSources(BaseVariant variant) {
        ImmutableSet.Builder<File> srcs = new ImmutableSet.Builder<>();

        Set<File> javaSrcs = variant
                .getSourceSets()
                .stream()
                .map(SourceProvider::getJavaDirectories)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        srcs.addAll(javaSrcs);

        if (isKotlin) {
            srcs.addAll(javaSrcs
                    .stream()
                    .filter(i -> i.getName().equals("java"))
                    .map(i -> getProject().file(
                            i.getAbsolutePath().replaceFirst("/java$", "/kotlin")))
                    .collect(Collectors.toSet()));
        }
        return srcs.build();
    }

    public Set<File> getJavaResources(BaseVariant variant) {
        return variant
                .getSourceSets()
                .stream()
                .map(SourceProvider::getResourcesDirectories)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    public final String getApplicationId() {
        return applicationId;
    }

    public final String getApplicationIdSuffix() {
        return applicationIdSuffix;
    }

    public final String getVersionName() {
        return versionName;
    }

    public final Integer getVersionCode() {
        return versionCode;
    }

    public final boolean getDebuggable() {
        return debuggable;
    }

    public final boolean getGenerateR2() {
        return generateR2;
    }

    public final boolean isGenerateR2() {
        return generateR2;
    }

    public final boolean getIsKapt() {
        return isKapt;
    }

    public final boolean getHasKotlinAndroidExtensions() {
        return hasKotlinAndroidExtensions;
    }

    public final boolean getHasExperimentalKotlinAndroidExtensions() {
        return hasExperimentalKotlinAndroidExtensions;
    }

    public boolean getIsTest() {
        return isTest;
    }

    private static class EmptyLogger implements ILogger {

        @Override
        public void error(Throwable throwable, String s, Object... objects) {
            // ignore
        }

        @Override
        public void warning(String s, Object... objects) {
            // ignore
        }

        @Override
        public void info(String s, Object... objects) {
            // ignore
        }

        @Override
        public void verbose(String s, Object... objects) {
            //ignore
        }
    }

    @Override
    public <T> T getProp(Map<String, T> map, T defaultValue) {
        String nameKey = getIdentifier() + StringUtils.capitalize(getName());
        String flavorKey = getIdentifier() + StringUtils.capitalize(getFlavor());
        String buildTypeKey = getIdentifier() + StringUtils.capitalize(getBuildType());

        if (map.containsKey(nameKey)) {
            return map.get(nameKey);
        } else if (map.containsKey(flavorKey)) {
            return map.get(flavorKey);
        } else if (map.containsKey(buildTypeKey)) {
            return map.get(buildTypeKey);
        } else {
            return map.getOrDefault(getIdentifier(), defaultValue);
        }
    }
}
