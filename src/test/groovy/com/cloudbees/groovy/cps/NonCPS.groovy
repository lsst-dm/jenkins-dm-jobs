package com.cloudbees.groovy.cps

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

// Test-only stub so util.groovy's @NonCPS resolves when compiled outside Jenkins.
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD, ElementType.TYPE])
@interface NonCPS {}
