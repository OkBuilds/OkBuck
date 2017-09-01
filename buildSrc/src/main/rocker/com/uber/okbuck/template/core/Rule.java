package com.uber.okbuck.template.core;

import com.fizzed.rocker.runtime.DefaultRockerModel;
import com.google.common.collect.ImmutableSet;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public abstract class Rule<T extends Rule> extends DefaultRockerModel {

    private static Set<String> DEFAULT_VISIBILITY = ImmutableSet.of("PUBLIC");

    protected String ruleType;
    protected String name;
    protected Collection visibility = ImmutableSet.of();
    protected Collection deps = ImmutableSet.of();
    protected Collection labels = ImmutableSet.of();
    protected Collection extraBuckOpts = ImmutableSet.of();

    public String name() {
        return name;
    }

    public T ruleType(String ruleType) {
        this.ruleType = ruleType;
        return (T) this;
    }

    public T name(String name) {
        this.name = name;
        return (T) this;
    }

    public T deps(Collection deps) {
        this.deps = deps;
        return (T) this;
    }

    public T labels(Collection labels) {
        this.labels = labels;
        return (T) this;
    }

    public T visibility(Collection visibility) {
        this.visibility = visibility;
        return (T) this;
    }

    public T defaultVisibility() {
        this.visibility = DEFAULT_VISIBILITY;
        return (T) this;
    }

    public T extraBuckOpts(Collection extraBuckOpts) {
        this.extraBuckOpts = extraBuckOpts;
        return (T) this;
    }

    protected static boolean valid(Map m) {
        return m != null && !m.isEmpty();
    }

    protected static boolean valid(Collection c) {
        return c != null && !c.isEmpty();
    }

    protected static boolean valid(String s) {
        return s != null && !s.isEmpty();
    }
}
