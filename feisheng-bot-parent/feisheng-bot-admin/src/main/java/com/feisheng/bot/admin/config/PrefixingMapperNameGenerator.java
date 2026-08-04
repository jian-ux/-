package com.feisheng.bot.admin.config;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;

/**
 * Generates unique bean names for MyBatis mappers across modules.
 * Adds a short prefix based on the mapper's package to avoid conflicts
 * when modules have mappers with the same simple class name.
 */
public class PrefixingMapperNameGenerator extends AnnotationBeanNameGenerator {

    @Override
    public String generateBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
        if (definition instanceof AnnotatedBeanDefinition abd) {
            String className = abd.getMetadata().getClassName();
            if (className != null) {
                if (className.startsWith("com.feisheng.bot.core.mapper.")) {
                    return "core_" + buildDefaultBeanName(definition);
                }
                if (className.startsWith("com.feisheng.bot.knowledge.mapper.")) {
                    return "kbid_" + buildDefaultBeanName(definition);
                }
                if (className.startsWith("com.feisheng.bot.gateway.mapper.")) {
                    return "gw_" + buildDefaultBeanName(definition);
                }
            }
        }
        return buildDefaultBeanName(definition);
    }
}
