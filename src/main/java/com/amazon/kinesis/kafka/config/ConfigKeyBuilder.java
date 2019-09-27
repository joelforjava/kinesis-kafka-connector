package com.amazon.kinesis.kafka.config;

import org.apache.kafka.common.config.ConfigDef.ConfigKey;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Recommender;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Validator;
import org.apache.kafka.common.config.ConfigDef.Width;

import java.util.Collections;
import java.util.List;

public class ConfigKeyBuilder {
    private String name;
    private Type type;
    private String documentation;
    private Object defaultValue;
    private Validator validator;
    private Importance importance;
    private String group;
    private int orderInGroup;
    private Width width;
    private String displayName;
    private List<String> dependents;
    private Recommender recommender;
    private boolean internalConfig;

    public ConfigKeyBuilder(String name) {
        this.name = name;
    }

    public ConfigKeyBuilder type(Type type) {
        this.type = type;
        this.dependents = Collections.emptyList();
        return this;
    }

    public ConfigKeyBuilder documentation(String documentation) {
        this.documentation = documentation;
        return this;
    }

    public ConfigKeyBuilder defaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public ConfigKeyBuilder validator(Validator validator) {
        this.validator = validator;
        return this;
    }

    public ConfigKeyBuilder importance(Importance importance) {
        this.importance = importance;
        return this;
    }

    public ConfigKeyBuilder group(String group) {
        this.group = group;
        return this;
    }

    public ConfigKeyBuilder orderInGroup(int orderInGroup) {
        this.orderInGroup = orderInGroup;
        return this;
    }

    public ConfigKeyBuilder width(Width width) {
        this.width = width;
        return this;
    }

    public ConfigKeyBuilder displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public ConfigKeyBuilder dependents(List<String> dependents) {
        this.dependents = dependents;
        return this;
    }

    public ConfigKeyBuilder recommender(Recommender recommender) {
        this.recommender = recommender;
        return this;
    }

    public ConfigKeyBuilder internalConfig(boolean internalConfig) {
        this.internalConfig = internalConfig;
        return this;
    }

    public ConfigKey build() {
        return new ConfigKey(name, type, defaultValue, validator, importance, documentation, group, orderInGroup,
                width, displayName, dependents, recommender, internalConfig);
    }
}
