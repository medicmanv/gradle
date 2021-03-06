/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal.component.local.model;

import org.gradle.api.Project;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.internal.artifacts.DefaultProjectComponentIdentifier;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.build.BuildState;
import org.gradle.util.Path;

public class DefaultProjectComponentSelector implements ProjectComponentSelector {
    private final BuildIdentifier buildIdentifier;
    private final Path projectPath;
    private final Path identityPath;
    private final String projectName;
    private String displayName;

    public DefaultProjectComponentSelector(BuildIdentifier buildIdentifier, Path identityPath, Path projectPath, String projectName) {
        assert buildIdentifier != null : "build cannot be null";
        assert identityPath != null : "identity path cannot be null";
        assert projectPath != null : "project path cannot be null";
        assert projectName != null : "project name cannot be null";
        this.buildIdentifier = buildIdentifier;
        this.identityPath = identityPath;
        this.projectPath = projectPath;
        this.projectName = projectName;
    }

    public String getDisplayName() {
        if (displayName == null) {
            displayName = "project " + identityPath;
        }
        return displayName;
    }

    public BuildIdentifier getBuildIdentifier() {
        return buildIdentifier;
    }

    @Override
    public String getBuildName() {
        return buildIdentifier.getName();
    }

    public Path getIdentityPath() {
        return identityPath;
    }

    public String getProjectPath() {
        return projectPath.getPath();
    }

    public Path projectPath() {
        return projectPath;
    }

    public String getProjectName() {
        return projectName;
    }

    public boolean matchesStrictly(ComponentIdentifier identifier) {
        assert identifier != null : "identifier cannot be null";

        if (identifier instanceof DefaultProjectComponentIdentifier) {
            DefaultProjectComponentIdentifier projectComponentIdentifier = (DefaultProjectComponentIdentifier) identifier;
            return projectComponentIdentifier.getIdentityPath().equals(identityPath);
        }

        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultProjectComponentSelector)) {
            return false;
        }
        DefaultProjectComponentSelector that = (DefaultProjectComponentSelector) o;
        return identityPath.equals(that.identityPath);
    }

    @Override
    public int hashCode() {
        return identityPath.hashCode();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    public static ProjectComponentSelector newSelector(Project project) {
        ProjectInternal projectInternal = (ProjectInternal) project;
        BuildIdentifier currentBuild = projectInternal.getServices().get(BuildState.class).getBuildIdentifier();
        return new DefaultProjectComponentSelector(currentBuild, projectInternal.getIdentityPath(), projectInternal.getProjectPath(), project.getName());
    }

    public static ProjectComponentSelector newSelector(ProjectComponentIdentifier identifier) {
        DefaultProjectComponentIdentifier projectComponentIdentifier = (DefaultProjectComponentIdentifier) identifier;
        return new DefaultProjectComponentSelector(projectComponentIdentifier.getBuild(), projectComponentIdentifier.getIdentityPath(), projectComponentIdentifier.projectPath(), projectComponentIdentifier.getProjectName());
    }

    public ProjectComponentIdentifier toIdentifier() {
        return new DefaultProjectComponentIdentifier(buildIdentifier, identityPath, projectPath, projectName);
    }
}
