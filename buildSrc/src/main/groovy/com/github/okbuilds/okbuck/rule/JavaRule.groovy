package com.github.okbuilds.okbuck.rule

abstract class JavaRule extends BuckRule {
    private final Set<String> mSrcSet
    private final Set<String> mAnnotationProcessors
    private final Set<String> mAnnotationProcessorDeps
    private final String mSourceCompatibility
    private final String mTargetCompatibility
    private final List<String> mPostprocessClassesCommands
    private final List<String> mOptions
    private final Set<String> mProvidedDeps

    JavaRule(String ruleType, String name, List<String> visibility, List<String> deps,
             Set<String> srcSet, Set<String> annotationProcessors,
             Set<String> aptDeps, Set<String> providedDeps, String sourceCompatibility,
             String targetCompatibility, List<String> postprocessClassesCommands,
             List<String> options) {
        super(ruleType, name, visibility, deps)
        mSrcSet = srcSet
        mAnnotationProcessors = annotationProcessors
        mAnnotationProcessorDeps = aptDeps
        mSourceCompatibility = sourceCompatibility
        mTargetCompatibility = targetCompatibility
        mPostprocessClassesCommands = postprocessClassesCommands
        mOptions = options
        mProvidedDeps = providedDeps
    }

    @Override
    protected final void printContent(PrintStream printer) {
        if (!mSrcSet.empty) {
            printer.println("\tsrcs = glob([")
            for (String src : mSrcSet) {
                printer.println("\t\t'${src}/**/*.java',")
            }
            printer.println("\t]),")
        }

        if (!mAnnotationProcessors.empty) {
            printer.println("\tannotation_processors = [")
            mAnnotationProcessors.each { String processor ->
                printer.println("\t\t'${processor}',")
            }
            printer.println("\t],")

            if (!mAnnotationProcessorDeps.empty) {
                printer.println("\tannotation_processor_deps = [")
                for (String dep : mAnnotationProcessorDeps) {
                    printer.println("\t\t'${dep}',")
                }
                printer.println("\t],")
            }
        }

        if (!mProvidedDeps.empty) {
            printer.println("\tprovided_deps = [")
            for (String dep : mProvidedDeps) {
                printer.println("\t\t'${dep}',")
            }
            printer.println("\t],")
        }

        printer.println("\tsource = '${mSourceCompatibility}',")
        printer.println("\ttarget = '${mTargetCompatibility}',")
        if (!mPostprocessClassesCommands.empty) {
            printer.println("\tpostprocess_classes_commands = [")
            mPostprocessClassesCommands.each { String command ->
                printer.println("\t\t'${command}',")
            }
            printer.println("\t],")
        }

        if (!mOptions.empty) {
            printer.println("\textra_arguments = [")
            mOptions.each { String option ->
                printer.println("\t\t'${option}',")
            }
            printer.println("\t],")
        }
    }
}
