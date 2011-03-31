package org.jfrog.hudson.util;

import com.google.common.base.Predicate;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.io.Closeables;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Computer;
import hudson.model.Result;
import hudson.slaves.SlaveComputer;
import hudson.tasks.LogRotator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.BuildInfoProperties;
import org.jfrog.build.client.ClientGradleProperties;
import org.jfrog.build.client.ClientIvyProperties;
import org.jfrog.build.client.ClientProperties;
import org.jfrog.build.extractor.maven.BuildInfoRecorder;
import org.jfrog.hudson.ArtifactoryServer;
import org.jfrog.hudson.action.ActionableHelper;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

/**
 * @author Tomer Cohen
 */
public class ExtractorUtils {
    private ExtractorUtils() {
        // utility class
        throw new IllegalAccessError();
    }

    public static void addCustomClassworlds(AbstractBuild<?, ?> build, URL resource, Map<String, String> env,
            File classWorldsFile) {
        String classworldsConfPath;
        if (Computer.currentComputer() instanceof SlaveComputer) {
            try {
                FilePath remoteClassworlds =
                        build.getWorkspace().createTextTempFile("classworlds", "conf", "", false);
                remoteClassworlds.copyFrom(resource);
                classworldsConfPath = remoteClassworlds.getRemote();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            classworldsConfPath = classWorldsFile.getAbsolutePath();
            File classWorldsConf = new File(resource.getFile());
            try {
                FileUtils.copyFile(classWorldsConf, classWorldsFile);
            } catch (IOException e) {
                build.setResult(Result.FAILURE);
                throw new RuntimeException(
                        "Unable to copy classworlds file: " + classWorldsConf.getAbsolutePath() + " to: " +
                                classWorldsFile.getAbsolutePath(), e);
            }
        }
        env.put("classworlds.conf", classworldsConfPath);
    }

    public static void addBuilderInfoArguments(Map<String, String> env, AbstractBuild build,
            ArtifactoryServer selectedArtifactoryServer, BuildContext context)
            throws IOException, InterruptedException {

        Properties props = new Properties();

        props.put(BuildInfoRecorder.ACTIVATE_RECORDER, Boolean.TRUE.toString());

        String buildName = build.getProject().getDisplayName();
        props.put(BuildInfoProperties.PROP_BUILD_NAME, buildName);
        props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX + "build.name", buildName);

        String buildNumber = build.getNumber() + "";
        props.put(BuildInfoProperties.PROP_BUILD_NUMBER, buildNumber);
        props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX + "build.number", buildNumber);

        Date buildStartDate = build.getTimestamp().getTime();
        props.put(BuildInfoProperties.PROP_BUILD_STARTED,
                new SimpleDateFormat(Build.STARTED_FORMAT).format(buildStartDate));

        props.put(BuildInfoProperties.PROP_BUILD_TIMESTAMP, String.valueOf(buildStartDate.getTime()));
        props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX + "build.timestamp",
                String.valueOf(buildStartDate.getTime()));

        String vcsRevision = env.get("SVN_REVISION");
        if (StringUtils.isNotBlank(vcsRevision)) {
            props.put(BuildInfoProperties.PROP_VCS_REVISION, vcsRevision);
            props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX +
                    BuildInfoProperties.PROP_VCS_REVISION, vcsRevision);
        }

        String buildUrl = ActionableHelper.getBuildUrl(build);
        if (StringUtils.isNotBlank(buildUrl)) {
            props.put(BuildInfoProperties.PROP_BUILD_URL, buildUrl);
        }

        String userName = "unknown";
        Cause.UpstreamCause parent = ActionableHelper.getUpstreamCause(build);
        if (parent != null) {
            String parentProject = parent.getUpstreamProject();
            props.put(BuildInfoProperties.PROP_PARENT_BUILD_NAME, parentProject);
            props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX +
                    BuildInfoProperties.PROP_PARENT_BUILD_NAME, parentProject);

            String parentBuildNumber = parent.getUpstreamBuild() + "";
            props.put(BuildInfoProperties.PROP_PARENT_BUILD_NUMBER, parentBuildNumber);
            props.put(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX +
                    BuildInfoProperties.PROP_PARENT_BUILD_NUMBER, parentBuildNumber);
            userName = "auto";
        }

        CauseAction action = ActionableHelper.getLatestAction(build, CauseAction.class);
        if (action != null) {
            for (Cause cause : action.getCauses()) {
                if (cause instanceof Cause.UserCause) {
                    userName = ((Cause.UserCause) cause).getUserName();
                }
            }
        }

        props.put(BuildInfoProperties.PROP_PRINCIPAL, userName);

        props.put(BuildInfoProperties.PROP_AGENT_NAME, "Hudson");
        props.put(BuildInfoProperties.PROP_AGENT_VERSION, build.getHudsonVersion());

        props.put(ClientProperties.PROP_CONTEXT_URL, selectedArtifactoryServer.getUrl());
        props.put(ClientProperties.PROP_TIMEOUT, Integer.toString(selectedArtifactoryServer.getTimeout()));
        props.put(ClientProperties.PROP_PUBLISH_REPOKEY, context.getDetails().repositoryKey);
        if (StringUtils.isNotBlank(context.getDetails().downloadRepositoryKey)) {
            props.put(ClientProperties.PROP_RESOLVE_REPOKEY, context.getDetails().downloadRepositoryKey);
        }
        props.put(ClientProperties.PROP_PUBLISH_SNAPSHOTS_REPOKEY, context.getDetails().snapshotsRepositoryKey);

        Credentials preferredDeployer =
                CredentialResolver.getPreferredDeployer(context.getDeployerOverrider(), selectedArtifactoryServer);
        if (StringUtils.isNotBlank(preferredDeployer.getUsername())) {
            props.put(ClientProperties.PROP_PUBLISH_USERNAME, preferredDeployer.getUsername());
            props.put(ClientProperties.PROP_PUBLISH_PASSWORD, preferredDeployer.getPassword());
        }
        props.put(BuildInfoProperties.PROP_LICENSE_CONTROL_RUN_CHECKS, Boolean.toString(context.isRunChecks()));
        props.put(BuildInfoProperties.PROP_LICENSE_CONTROL_INCLUDE_PUBLISHED_ARTIFACTS,
                Boolean.toString(context.isIncludePublishArtifacts()));
        props.put(BuildInfoProperties.PROP_LICENSE_CONTROL_AUTO_DISCOVER,
                Boolean.toString(context.isLicenseAutoDiscovery()));
        if (context.isRunChecks()) {
            if (StringUtils.isNotBlank(context.getViolationRecipients())) {
                props.put(BuildInfoProperties.PROP_LICENSE_CONTROL_VIOLATION_RECIPIENTS,
                        context.getViolationRecipients());
            }
            if (StringUtils.isNotBlank(context.getScopes())) {
                props.put(BuildInfoProperties.PROP_LICENSE_CONTROL_SCOPES, context.getScopes());
            }
        }
        if (context.isDiscardOldBuilds()) {
            LogRotator rotator = build.getProject().getLogRotator();
            if (rotator != null) {
                if (rotator.getNumToKeep() > -1) {
                    props.put(BuildInfoProperties.PROP_BUILD_RETENTION_DAYS, String.valueOf(rotator.getNumToKeep()));
                }
                if (rotator.getDaysToKeep() > -1) {
                    props.put(BuildInfoProperties.PROP_BUILD_RETENTION_MINIMUM_DATE,
                            String.valueOf(rotator.getDaysToKeep()));
                }
            }
        }
        props.put(ClientProperties.PROP_PUBLISH_ARTIFACT, Boolean.toString(context.isDeployArtifacts()));
        props.put(ClientProperties.PROP_PUBLISH_EVEN_UNSTABLE, Boolean.toString(context.isEvenIfUnstable()));
        props.put(ClientIvyProperties.PROP_PUBLISH_IVY, Boolean.toString(context.isDeployIvy()));
        props.put(ClientGradleProperties.PROP_PUBLISH_MAVEN, Boolean.toString(context.isDeployMaven()));
        IncludesExcludes deploymentPatterns = context.getIncludesExcludes();
        if (deploymentPatterns != null) {
            String includePatterns = deploymentPatterns.getIncludePatterns();
            if (StringUtils.isNotBlank(includePatterns)) {
                props.put(ClientProperties.PROP_PUBLISH_ARTIFACT_INCLUDE_PATTERNS, includePatterns);
            }

            String excludePatterns = deploymentPatterns.getExcludePatterns();
            if (StringUtils.isNotBlank(excludePatterns)) {
                props.put(ClientProperties.PROP_PUBLISH_ARTIFACT_EXCLUDE_PATTERNS, excludePatterns);
            }
        }

        props.put(ClientProperties.PROP_PUBLISH_BUILD_INFO,
                Boolean.toString(!context.isSkipBuildInfoDeploy()));
        props.put(BuildInfoConfigProperties.PROP_INCLUDE_ENV_VARS, Boolean.toString(context.isIncludeEnvVars()));
        addEnvVars(env, build, props);

        String propFilePath;
        OutputStream fileOutputStream = null;
        try {
            FilePath tempFile = build.getWorkspace().createTextTempFile("buildInfo", "properties", "", false);
            fileOutputStream = tempFile.write();
            props.store(fileOutputStream, null);
            propFilePath = tempFile.getRemote();
        } finally {
            Closeables.closeQuietly(fileOutputStream);
        }
        env.put(BuildInfoConfigProperties.PROP_PROPS_FILE, propFilePath);
    }

    private static void addEnvVars(Map<String, String> env, AbstractBuild build, Properties props) {
        // Write all the deploy (matrix params) properties.
        Map<String, String> filteredEnvMatrixParams = Maps.filterKeys(env, new Predicate<String>() {
            public boolean apply(String input) {
                return input.startsWith(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX);
            }
        });
        for (Map.Entry<String, String> entry : filteredEnvMatrixParams.entrySet()) {
            props.put(entry.getKey(), entry.getValue());
        }

        //Add only the hudson specific environment variables
        MapDifference<String, String> envDifference = Maps.difference(env, System.getenv());
        Map<String, String> filteredEnvDifference = envDifference.entriesOnlyOnLeft();
        for (Map.Entry<String, String> entry : filteredEnvDifference.entrySet()) {
            props.put(BuildInfoProperties.BUILD_INFO_ENVIRONMENT_PREFIX + entry.getKey(), entry.getValue());
        }

        // add build variables
        Map<String, String> buildVariables = build.getBuildVariables();
        Map<String, String> filteredBuildVars = Maps.newHashMap();

        filteredBuildVars.putAll(Maps.filterKeys(buildVariables, new Predicate<String>() {
            public boolean apply(String input) {
                return input.startsWith(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX);
            }
        }));
        filteredBuildVars.putAll(Maps.filterKeys(buildVariables, new Predicate<String>() {
            public boolean apply(String input) {
                return input.startsWith(BuildInfoProperties.BUILD_INFO_PROP_PREFIX);
            }
        }));

        for (Map.Entry<String, String> filteredBuildVar : filteredBuildVars.entrySet()) {
            props.put(filteredBuildVar.getKey(), filteredBuildVar.getValue());
        }

        MapDifference<String, String> buildVarDifference = Maps.difference(buildVariables, filteredBuildVars);
        Map<String, String> filteredBuildVarDifferences = buildVarDifference.entriesOnlyOnLeft();

        for (Map.Entry<String, String> filteredBuildVarDifference : filteredBuildVarDifferences.entrySet()) {
            props.put(BuildInfoProperties.BUILD_INFO_ENVIRONMENT_PREFIX + filteredBuildVarDifference.getKey(),
                    filteredBuildVarDifference.getValue());
        }
    }
}