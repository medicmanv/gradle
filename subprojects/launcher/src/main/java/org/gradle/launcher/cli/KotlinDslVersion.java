package org.gradle.launcher.cli;

import org.gradle.internal.UncheckedException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class KotlinDslVersion {

    static KotlinDslVersion current() {
        Properties kotlinDslVersions = new Properties();
        try {
            InputStream input = KotlinDslVersion.class.getClassLoader().getResourceAsStream("gradle-kotlin-dsl-versions.properties");
            try {
                kotlinDslVersions.load(input);
            } finally {
                input.close();
            }
        } catch (IOException ex) {
            throw UncheckedException.throwAsUncheckedException(ex);
        }
        return new KotlinDslVersion(kotlinDslVersions.getProperty("provider"), kotlinDslVersions.getProperty("kotlin"));
    }

    private final String provider;
    private final String kotlin;

    private KotlinDslVersion(String provider, String kotlin) {
        this.provider = provider;
        this.kotlin = kotlin;
    }

    String getProviderVersion() {
        return provider;
    }

    String getKotlinVersion() {
        return kotlin;
    }
}
