/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.integtests.fixtures.executer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.gradle.api.Action;
import org.gradle.integtests.fixtures.logging.GroupedOutputFixture;
import org.gradle.internal.Pair;
import org.gradle.internal.featurelifecycle.LoggingDeprecatedFeatureHandler;
import org.gradle.internal.jvm.UnsupportedJavaRuntimeException;
import org.gradle.launcher.daemon.client.DaemonStartupMessage;
import org.gradle.launcher.daemon.server.DaemonStateCoordinator;
import org.gradle.launcher.daemon.server.health.LowTenuredSpaceDaemonExpirationStrategy;
import org.gradle.util.GUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class OutputScrapingExecutionResult implements ExecutionResult {
    static final Pattern STACK_TRACE_ELEMENT = Pattern.compile("\\s+(at\\s+)?([\\w.$_]+/)?[\\w.$_]+\\.[\\w$_ =\\+\'-<>]+\\(.+?\\)(\\x1B\\[0K)?");
    private static final String TASK_PREFIX = "> Task ";

    //for example: ':a SKIPPED' or ':foo:bar:baz UP-TO-DATE' but not ':a'
    private static final Pattern SKIPPED_TASK_PATTERN = Pattern.compile("(> Task )?(:\\S+?(:\\S+?)*)\\s+((SKIPPED)|(UP-TO-DATE)|(NO-SOURCE)|(FROM-CACHE))");

    //for example: ':hey' or ':a SKIPPED' or ':foo:bar:baz UP-TO-DATE' but not ':a FOO'
    private static final Pattern TASK_PATTERN = Pattern.compile("(> Task )?(:\\S+?(:\\S+?)*)((\\s+SKIPPED)|(\\s+UP-TO-DATE)|(\\s+FROM-CACHE)|(\\s+NO-SOURCE)|(\\s+FAILED)|(\\s*))");

    private static final Pattern BUILD_RESULT_PATTERN = Pattern.compile("BUILD (SUCCESSFUL|FAILED) in( \\d+[smh])+");

    private final LogContent output;
    private final LogContent error;
    private final LogContent mainContent;
    private final LogContent postBuild;
    private GroupedOutputFixture groupedOutputFixture;
    private Set<String> tasks;

    public static List<String> flattenTaskPaths(Object[] taskPaths) {
        return org.gradle.util.CollectionUtils.toStringList(GUtil.flatten(taskPaths, Lists.newArrayList()));
    }

    /**
     * Creates a result from the output of a <em>single</em> Gradle invocation.
     *
     * @param output The raw build stdout chars.
     * @param error The raw build stderr chars.
     * @return A {@link OutputScrapingExecutionResult} for a successful build, or a {@link OutputScrapingExecutionFailure} for a failed build.
     */
    public static OutputScrapingExecutionResult from(String output, String error) {
        // Should provide a Gradle version as parameter so this check can be more precise
        if (output.contains("BUILD FAILED") || output.contains("FAILURE: Build failed with an exception.") || error.contains("BUILD FAILED")) {
            return new OutputScrapingExecutionFailure(output, error);
        }
        return new OutputScrapingExecutionResult(LogContent.of(output), LogContent.of(error));
    }

    /**
     * @param output The build stdout content.
     * @param error The build stderr content. Must have normalized line endings.
     */
    protected OutputScrapingExecutionResult(LogContent output, LogContent error) {
        this.output = output;
        this.error = error;

        // Split out up the output into main content and post build content
        LogContent filteredOutput = this.output.removeAnsiChars().removeDebugPrefix();
        Pair<LogContent, LogContent> match = filteredOutput.splitOnFirstMatchingLine(BUILD_RESULT_PATTERN);
        if (match == null) {
            this.mainContent = filteredOutput;
            this.postBuild = LogContent.empty();
        } else {
            this.mainContent = match.getLeft();
            this.postBuild = match.getRight().drop(1);
        }
    }

    public String getOutput() {
        return output.withNormalizedEol();
    }

    /**
     * The main content with debug prefix and ANSI characters removed.
     */
    public LogContent getMainContent() {
        return mainContent;
    }

    @Override
    public String getNormalizedOutput() {
        return normalize(output);
    }

    @Override
    public GroupedOutputFixture getGroupedOutput() {
        if (groupedOutputFixture == null) {
            groupedOutputFixture = new GroupedOutputFixture(getMainContent().getRawContent().withNormalizedEol());
        }
        return groupedOutputFixture;
    }

    private String normalize(LogContent output) {
        List<String> result = new ArrayList<String>();
        List<String> lines = output.getLines();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.contains(DaemonStartupMessage.STARTING_DAEMON_MESSAGE)) {
                // Remove the "daemon starting" message
                i++;
            } else if (line.contains(DaemonStateCoordinator.DAEMON_WILL_STOP_MESSAGE)) {
                // Remove the "Daemon will be shut down" message
                i++;
            } else if (line.contains(LowTenuredSpaceDaemonExpirationStrategy.EXPIRE_DAEMON_MESSAGE)) {
                // Remove the "Expiring Daemon" message
                i++;
            } else if (line.contains(LoggingDeprecatedFeatureHandler.WARNING_SUMMARY)) {
                // Remove the "Deprecated Gradle features..." message and "See https://docs.gradle.org..."
                i+=2;
            } else if (line.contains(UnsupportedJavaRuntimeException.JAVA7_DEPRECATION_WARNING)) {
                // Remove the Java 7 deprecation warning. This should be removed after 5.0
                i++;
                while (i < lines.size() && STACK_TRACE_ELEMENT.matcher(lines.get(i)).matches()) {
                    i++;
                }
            } else if (BUILD_RESULT_PATTERN.matcher(line).matches()) {
                result.add(BUILD_RESULT_PATTERN.matcher(line).replaceFirst("BUILD $1 in 0s"));
                i++;
            } else {
                result.add(line);
                i++;
            }
        }

        return LogContent.of(result).withNormalizedEol();
    }

    public ExecutionResult assertOutputEquals(String expectedOutput, boolean ignoreExtraLines, boolean ignoreLineOrder) {
        SequentialOutputMatcher matcher = ignoreLineOrder ? new AnyOrderOutputMatcher() : new SequentialOutputMatcher();
        matcher.assertOutputMatches(expectedOutput, getNormalizedOutput(), ignoreExtraLines);
        return this;
    }

    @Override
    public ExecutionResult assertHasPostBuildOutput(String expectedOutput) {
        return assertContentContains(postBuild.withNormalizedEol(), expectedOutput, "Post-build output");
    }

    @Override
    public ExecutionResult assertNotOutput(String expectedOutput) {
        String expectedText = LogContent.of(expectedOutput).withNormalizedEol();
        if (getOutput().contains(expectedText)|| getError().contains(expectedText)) {
            throw new AssertionError(String.format("Found unexpected text in build output.%nExpected not present: %s%n%nOutput:%n=======%n%s%nError:%n======%n%s", expectedText, getOutput(), getError()));
        }
        return this;
    }

    @Override
    public ExecutionResult assertContentContains(String actualText, String expectedOutput, String label) {
        String expectedText = LogContent.of(expectedOutput).withNormalizedEol();
        if (!actualText.contains(expectedText)) {
            failOnMissingOutput("Did not find expected text in " + label.toLowerCase() + ".", label, expectedOutput, actualText);
        }
        return this;
    }

    @Override
    public ExecutionResult assertOutputContains(String expectedOutput) {
        return assertContentContains(getMainContent().withNormalizedEol(), expectedOutput, "Build output");
    }

    @Override
    public boolean hasErrorOutput(String expectedOutput) {
        return getError().contains(expectedOutput);
    }

    @Override
    public ExecutionResult assertHasErrorOutput(String expectedOutput) {
        return assertContentContains(getError(), expectedOutput, "Error output");
    }

    public String getError() {
        return error.withNormalizedEol();
    }

    public List<String> getExecutedTasks() {
        return ImmutableList.copyOf(findExecutedTasksInOrderStarted());
    }

    private Set<String> findExecutedTasksInOrderStarted() {
        if (tasks == null) {
            tasks = new LinkedHashSet<String>(grepTasks(TASK_PATTERN));
        }
        return tasks;
    }

    public ExecutionResult assertTasksExecutedInOrder(Object... taskPaths) {
        Set<String> allTasks = TaskOrderSpecs.exact(taskPaths).getTasks();
        assertTasksExecuted(allTasks);
        assertTaskOrder(taskPaths);
        return this;
    }

    @Override
    public ExecutionResult assertTasksExecuted(Object... taskPaths) {
        Set<String> expectedTasks = new TreeSet<String>(flattenTaskPaths(taskPaths));
        Set<String> actualTasks = findExecutedTasksInOrderStarted();
        if (!expectedTasks.equals(actualTasks)) {
            failOnDifferentSets("Build output does not contain the expected tasks.", expectedTasks, actualTasks);
        }
        return this;
    }

    @Override
    public ExecutionResult assertTaskExecuted(String taskPath) {
        Set<String> actualTasks = findExecutedTasksInOrderStarted();
        if (!actualTasks.contains(taskPath)) {
            failOnMissingElement("Build output does not contain the expected task.", taskPath, actualTasks);
        }
        return this;
    }

    @Override
    public ExecutionResult assertTaskNotExecuted(String taskPath) {
        Set<String> actualTasks = findExecutedTasksInOrderStarted();
        if (actualTasks.contains(taskPath)) {
            failOnMissingElement("Build output does contains unexpected task.", taskPath, actualTasks);
        }
        return this;
    }

    @Override
    public ExecutionResult assertTaskOrder(Object... taskPaths) {
        TaskOrderSpecs.exact(taskPaths).assertMatches(-1, getExecutedTasks());
        return this;
    }

    public Set<String> getSkippedTasks() {
        return new TreeSet<String>(grepTasks(SKIPPED_TASK_PATTERN));
    }

    @Override
    public ExecutionResult assertTasksSkipped(Object... taskPaths) {
        Set<String> expectedTasks = new TreeSet<String>(flattenTaskPaths(taskPaths));
        Set<String> skippedTasks = getSkippedTasks();
        if (!expectedTasks.equals(skippedTasks)) {
            failOnDifferentSets("Build output does not contain the expected skipped tasks.", expectedTasks, skippedTasks);
        }
        return this;
    }

    public ExecutionResult assertTaskSkipped(String taskPath) {
        Set<String> tasks = new TreeSet<String>(getSkippedTasks());
        if (!tasks.contains(taskPath)) {
            failOnMissingElement("Build output does not contain the expected skipped task.", taskPath, tasks);
        }
        return this;
    }

    private Collection<String> getNotSkippedTasks() {
        Set<String> all = new TreeSet<String>(getExecutedTasks());
        Set<String> skipped = getSkippedTasks();
        all.removeAll(skipped);
        return all;
    }

    @Override
    public ExecutionResult assertTasksNotSkipped(Object... taskPaths) {
        Set<String> expectedTasks = new TreeSet<String>(flattenTaskPaths(taskPaths));
        Set<String> tasks = new TreeSet<String>(getNotSkippedTasks());
        if (!expectedTasks.equals(tasks)) {
            failOnDifferentSets("Build output does not contain the expected non skipped tasks.", expectedTasks, tasks);
        }
        return this;
    }

    public ExecutionResult assertTaskNotSkipped(String taskPath) {
        Set<String> tasks = new TreeSet<String>(getNotSkippedTasks());
        if (!tasks.contains(taskPath)) {
            failOnMissingElement("Build output does not contain the expected non skipped task.", taskPath, tasks);
        }
        return this;
    }

    private void failOnDifferentSets(String message, Set<String> expected, Set<String> actual) {
        throw new AssertionError(String.format("%s%nExpected: %s%nActual: %s%nOutput:%n=======%n%s%nError:%n======%n%s", message, expected, actual, getOutput(), getError()));
    }

    private void failOnMissingElement(String message, String expected, Set<String> actual) {
        throw new AssertionError(String.format("%s%nExpected: %s%nActual: %s%nOutput:%n=======%n%s%nError:%n======%n%s", message, expected, actual, getOutput(), getError()));
    }

    private void failOnMissingOutput(String message, String type, String expected, String actual) {
        throw new AssertionError(String.format("%s%nExpected: %s%n%n%s:%n=======%n%s%nOutput:%n=======%n%s%nError:%n======%n%s", message, expected, type, actual, getOutput(), getError()));
    }

    private List<String> grepTasks(final Pattern pattern) {
        final List<String> tasks = Lists.newArrayList();
        final List<String> taskStatusLines = Lists.newArrayList();

        getMainContent().eachLine(new Action<String>() {
            public void execute(String line) {
                java.util.regex.Matcher matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    String taskStatusLine = matcher.group().replace(TASK_PREFIX, "");
                    String taskName = matcher.group(2);
                    if (!taskName.contains(":buildSrc:")) {
                        // The task status line may appear twice - once for the execution, once for the UP-TO-DATE/SKIPPED/etc
                        // So don't add to the task list if this is an update to a previously added task.

                        // Find the status line for the previous record of this task
                        String previousTaskStatusLine = tasks.contains(taskName) ? taskStatusLines.get(tasks.lastIndexOf(taskName)) : "";
                        // Don't add if our last record has a `:taskName` status, and this one is `:taskName SOMETHING`
                        if (previousTaskStatusLine.equals(taskName) && !taskStatusLine.equals(taskName)) {
                            return;
                        }

                        taskStatusLines.add(taskStatusLine);
                        tasks.add(taskName);
                    }
                }
            }
        });

        return tasks;
    }
}
