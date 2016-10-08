package com.uber.okbuck.composer

import com.uber.okbuck.core.model.AndroidTarget
import com.uber.okbuck.core.model.Target
import com.uber.okbuck.rule.AndroidResourceRule

final class AndroidResourceRuleComposer extends AndroidBuckRuleComposer {

    private AndroidResourceRuleComposer() {
        // no instance
    }

    static AndroidResourceRule compose(AndroidTarget target, AndroidTarget.ResBundle resBundle) {
        List<String> resDeps = new ArrayList<>()

        resDeps.addAll(external(target.main.externalDeps.findAll { String dep ->
            dep.endsWith(".aar")
        }))

        target.main.targetDeps.each { Target targetDep ->
            if (targetDep instanceof AndroidTarget) {
                targetDep.resources.each { AndroidTarget.ResBundle bundle ->
                    resDeps.add(res(targetDep as AndroidTarget, bundle))
                }
            }
        }

        return new AndroidResourceRule(resLocal(resBundle), ["PUBLIC"], resDeps,
                AndroidTarget.getPackage(target.project), resBundle.resDir, resBundle.assetsDir)
    }
}
