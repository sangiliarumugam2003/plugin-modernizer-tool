package io.jenkins.tools.pluginmodernizer.core.impl;

import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jenkins.tools.pluginmodernizer.core.config.Config;
import io.jenkins.tools.pluginmodernizer.core.config.Settings;
import io.jenkins.tools.pluginmodernizer.core.model.JDK;
import io.jenkins.tools.pluginmodernizer.core.model.ModernizerException;
import io.jenkins.tools.pluginmodernizer.core.model.Plugin;
import io.jenkins.tools.pluginmodernizer.core.model.PluginProcessingException;
import io.jenkins.tools.pluginmodernizer.core.utils.JdkFetcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.openrewrite.Recipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "false positive")
public class MavenInvoker {

    /**
     * The logger to use
     */
    private static final Logger LOG = LoggerFactory.getLogger(MavenInvoker.class);

    /**
     * The configuration to use
     */
    private final Config config;

    /**
     * The JDK fetcher to use
     */
    private final JdkFetcher jdkFetcher;

    /**
     * Create a new MavenInvoker
     * @param config The configuration to use
     * @param jdkFetcher The JDK fetcher to use
     */
    public MavenInvoker(Config config, JdkFetcher jdkFetcher) {
        this.config = config;
        this.jdkFetcher = jdkFetcher;
        validateMavenHome();
        validateMavenVersion();
    }

    /**
     * Get the maven version
     * @return The maven version
     */
    public @Nullable ComparableVersion getMavenVersion() {
        AtomicReference<String> version = new AtomicReference<>();
        try {
            Invoker invoker = new DefaultInvoker();
            invoker.setMavenHome(config.getMavenHome().toFile());
            InvocationRequest request = new DefaultInvocationRequest();
            request.setBatchMode(true);
            request.addArg("-q");
            request.addArg("--version");
            request.setOutputHandler(version::set);
            invoker.execute(request);
            return new ComparableVersion(version.get());
        } catch (MavenInvocationException e) {
            LOG.error("Failed to check for maven version", e);
            return null;
        }
    }

    /**
     * Invoke a goal on a plugin
     * @param plugin The plugin to run the goal on
     * @param goal The goal to run. For example, "clean"
     */
    public void invokeGoal(Plugin plugin, String goal) {
        LOG.debug("Running {} phase for plugin {}", goal, plugin.getName());
        LOG.debug(
                "Running maven on directory {}",
                plugin.getLocalRepository().toAbsolutePath().toFile());
        invokeGoals(plugin, goal);
    }

    /**
     * Invoke the rewrite modernization for a given plugin
     * @param plugin The plugin to run the rewrite on
     */
    public void collectMetadata(Plugin plugin) {
        LOG.info("Collecting metadata for plugin {}... Please be patient", plugin);
        invokeGoals(plugin, getSingleRecipeArgs(Settings.FETCH_METADATA_RECIPE));
        LOG.info("Done");
    }

    /**
     * Ensure a minimal build can be performed on the plugin.
     * Some plugin are very outdated they cannot compile anymore due to non-https URL
     * and missing relative path on the parent pom. This methods ensure that the plugin
     * is setup correctly before attempting to compile it.
     * @param plugin The plugin to ensure minimal build
     */
    public void ensureMinimalBuild(Plugin plugin) {
        LOG.info("Ensuring minimal build for plugin {}... Please be patient", plugin);
        invokeGoals(plugin, getSingleRecipeArgs(Settings.MINIMAL_BUILD_JAVA_8_RECIPE));
        LOG.info("Done");
    }

    /**
     * Invoke the rewrite modernization for a given plugin
     * @param plugin The plugin to run the rewrite on
     */
    public void invokeRewrite(Plugin plugin) {
        plugin.addTags(config.getRecipes().stream()
                .flatMap(recipe -> recipe.getTags().stream())
                .collect(Collectors.toSet()));
        LOG.info(
                "Running recipes {} for plugin {}... Please be patient",
                String.join(
                        ",", config.getRecipes().stream().map(Recipe::getName).toList()),
                plugin);
        invokeGoals(plugin, getRewriteArgs());
        LOG.info("Done");
    }

    /**
     * Get the rewrite arguments to be executed for metadata collection
     * @return The list of arguments to be passed to the rewrite plugin
     */
    private String[] getSingleRecipeArgs(Recipe recipe) {
        List<String> goals = new ArrayList<>();
        goals.add("org.openrewrite.maven:rewrite-maven-plugin:" + Settings.MAVEN_REWRITE_PLUGIN_VERSION + ":run");
        goals.add("-Drewrite.exportDatatables=" + config.isExportDatatables());
        goals.add("-Drewrite.activeRecipes=" + recipe.getName());
        goals.add("-Drewrite.recipeArtifactCoordinates=io.jenkins.plugin-modernizer:plugin-modernizer-core:"
                + config.getVersion());
        return goals.toArray(String[]::new);
    }

    /**
     * Get the rewrite arguments to be executed for each plugin
     * @return The list of arguments to be passed to the rewrite plugin
     */
    private String[] getRewriteArgs() {
        List<String> goals = new ArrayList<>();
        goals.add("org.openrewrite.maven:rewrite-maven-plugin:" + Settings.MAVEN_REWRITE_PLUGIN_VERSION + ":run");
        goals.add("-Drewrite.exportDatatables=" + config.isExportDatatables());

        String activeRecipesStr =
                config.getRecipes().stream().map(Recipe::getName).collect(Collectors.joining(", "));
        LOG.debug("Active recipes: {}", activeRecipesStr);
        goals.add("-Drewrite.activeRecipes=" + String.join(",", activeRecipesStr));
        goals.add("-Drewrite.recipeArtifactCoordinates=io.jenkins.plugin-modernizer:plugin-modernizer-core:"
                + config.getVersion());
        return goals.toArray(String[]::new);
    }

    /**
     * Invoke a list of maven goal on the plugin
     * @param plugin The plugin to run the goals on
     * @param goals The list of goals to run
     */
    private void invokeGoals(Plugin plugin, String... goals) {
        validatePom(plugin);
        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(config.getMavenHome().toFile());
        try {
            InvocationRequest request = createInvocationRequest(plugin, goals);
            JDK jdk = plugin.getJDK();
            if (jdk != null) {
                Path jdkPath = jdk.getHome(jdkFetcher);
                request.setJavaHome(jdkPath.toFile());
                LOG.debug("JDK home: {}", jdkPath);
            }
            request.setBatchMode(true);
            request.setNoTransferProgress(false);
            request.setErrorHandler((message) -> {
                LOG.error(plugin.getMarker(), String.format("Something went wrong when running maven: %s", message));
            });
            request.setOutputHandler((message) -> {
                LOG.info(plugin.getMarker(), message);
            });
            InvocationResult result = invoker.execute(request);
            handleInvocationResult(plugin, result);
        } catch (MavenInvocationException | InterruptedException | IOException e) {
            plugin.addError("Maven invocation failed", e);
        }
    }

    /**
     * Validate a pom exist for the given plugin
     * @param plugin The plugin to validate
     */
    private void validatePom(Plugin plugin) {
        LOG.debug("Validating POM for plugin: {}", plugin);
        if (!plugin.getLocalRepository().resolve("pom.xml").toFile().isFile()) {
            plugin.addError("POM file not found");
            throw new PluginProcessingException("POM file not found", plugin);
        }
    }

    /**
     * Validate the Maven home directory.
     * @throws IllegalArgumentException if the Maven home directory is not set or invalid.
     */
    private void validateMavenHome() {
        Path mavenHome = config.getMavenHome();
        if (mavenHome == null) {
            LOG.error("Neither MAVEN_HOME nor M2_HOME environment variables are set.");
            throw new ModernizerException("Maven home directory not set.");
        }

        if (!Files.isDirectory(mavenHome) || !Files.isExecutable(mavenHome.resolve("bin/mvn"))) {
            LOG.error("Invalid Maven home directory. Aborting build.");
            throw new ModernizerException("Invalid Maven home directory.");
        }
    }

    /**
     * Validate the Maven version.
     * @throws IllegalArgumentException if the Maven version is too old or cannot be determined.
     */
    private void validateMavenVersion() {
        ComparableVersion mavenVersion = getMavenVersion();
        LOG.debug("Maven version detected: {}", mavenVersion);
        if (mavenVersion == null) {
            LOG.error("Failed to check Maven version. Aborting build.");
            throw new ModernizerException("Failed to check Maven version.");
        }
        if (mavenVersion.compareTo(Settings.MAVEN_MINIMAL_VERSION) < 0) {
            LOG.error(
                    "Maven version detected {}, is too old. Please use at least version {}",
                    mavenVersion,
                    Settings.MAVEN_MINIMAL_VERSION);
            throw new ModernizerException("Maven version is too old.");
        }
    }

    /**
     * Create an invocation request for the plugin.
     * @param plugin The plugin to run the goals on
     * @param args The list of args
     * @return The invocation request
     */
    private InvocationRequest createInvocationRequest(Plugin plugin, String... args) {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(plugin.getLocalRepository().resolve("pom.xml").toFile());
        request.addArgs(List.of(args));
        if (config.isDebug()) {
            request.addArg("-X");
        }
        return request;
    }

    /**
     * Handle invocation result for the plugin
     * @param plugin The plugin
     * @param result The invocation result
     */
    private void handleInvocationResult(Plugin plugin, InvocationResult result) {
        if (result.getExitCode() != 0) {
            LOG.error(plugin.getMarker(), "Build fail with code: {}", result.getExitCode());
            if (result.getExecutionException() != null) {
                plugin.addError("Maven generic exception occurred", result.getExecutionException());
            } else {
                String errorMessage;
                if (config.isDebug()) {
                    errorMessage = "Build failed with code: " + result.getExitCode();
                } else {
                    errorMessage = "Build failed";
                }
                plugin.addError(errorMessage);
            }
        }
    }
}
