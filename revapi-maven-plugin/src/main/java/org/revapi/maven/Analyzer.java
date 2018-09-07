/*
 * Copyright 2014-2018 Lukas Krejci
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.revapi.maven;

import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.jboss.dmr.ModelNode;
import org.revapi.API;
import org.revapi.AnalysisContext;
import org.revapi.AnalysisResult;
import org.revapi.Reporter;
import org.revapi.Revapi;
import org.revapi.configuration.JSONUtil;
import org.revapi.configuration.ValidationResult;
import org.revapi.configuration.XmlToJson;
import org.revapi.maven.utils.ArtifactResolver;
import org.revapi.maven.utils.ScopeDependencySelector;
import org.revapi.maven.utils.ScopeDependencyTraverser;

/**
 * @author Lukas Krejci
 * @since 0.1
 */
public final class Analyzer {
    private static final Pattern ANY_NON_SNAPSHOT = Pattern.compile("^.*(?<!-SNAPSHOT)$");
    private static final Pattern ANY = Pattern.compile(".*");

    private final PlexusConfiguration analysisConfiguration;

    private final Object[] analysisConfigurationFiles;

    private final String[] oldGavs;

    private final String[] newGavs;

    private final Artifact[] oldArtifacts;

    private final Artifact[] newArtifacts;

    private final MavenProject project;

    private final RepositorySystem repositorySystem;

    private final RepositorySystemSession repositorySystemSession;

    private final Class<? extends Reporter> reporterType;

    private final Map<String, Object> contextData;

    private final Locale locale;

    private final Log log;

    private final boolean failOnMissingConfigurationFiles;

    private final boolean failOnMissingArchives;

    private final boolean failOnMissingSupportArchives;

    private final Supplier<Revapi.Builder> revapiConstructor;

    private final boolean resolveDependencies;

    private final Pattern versionRegex;

    private API resolvedOldApi;
    private API resolvedNewApi;

    private Revapi revapi;

    Analyzer(PlexusConfiguration analysisConfiguration, Object[] analysisConfigurationFiles, Artifact[] oldArtifacts,
             Artifact[] newArtifacts, String[] oldGavs, String[] newGavs, MavenProject project,
             RepositorySystem repositorySystem, RepositorySystemSession repositorySystemSession,
             Class<? extends Reporter> reporterType, Map<String, Object> contextData,
             Locale locale, Log log, boolean failOnMissingConfigurationFiles, boolean failOnMissingArchives,
             boolean failOnMissingSupportArchives, boolean alwaysUpdate, boolean resolveDependencies,
             boolean resolveProvidedDependencies, boolean resolveTransitiveProvidedDependencies,
             String versionRegex, Supplier<Revapi.Builder> revapiConstructor, Revapi sharedRevapi) {

        this.analysisConfiguration = analysisConfiguration;
        this.analysisConfigurationFiles = analysisConfigurationFiles;
        this.oldGavs = oldGavs;
        this.newGavs = newGavs;
        this.oldArtifacts = oldArtifacts;
        this.newArtifacts = newArtifacts;
        this.project = project;
        this.repositorySystem = repositorySystem;

        this.resolveDependencies = resolveDependencies;

        this.versionRegex = versionRegex == null ? null : Pattern.compile(versionRegex);

        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(repositorySystemSession);
        String[] topLevelScopes = resolveProvidedDependencies
                ? new String[] {"compile", "provided"}
                : new String[] {"compile"};
        String[] transitiveScopes = resolveTransitiveProvidedDependencies
                ? new String[]{"compile", "provided"}
                : new String[]{"compile"};

        session.setDependencySelector(new ScopeDependencySelector(topLevelScopes, transitiveScopes));
        session.setDependencyTraverser(new ScopeDependencyTraverser(topLevelScopes, transitiveScopes));

        if (alwaysUpdate) {
            session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);
        }

        this.repositorySystemSession = session;

        this.reporterType = reporterType;
        this.contextData = contextData;
        this.locale = locale;
        this.log = log;
        this.failOnMissingConfigurationFiles = failOnMissingConfigurationFiles;
        this.failOnMissingArchives = failOnMissingArchives;
        this.failOnMissingSupportArchives = failOnMissingSupportArchives;
        this.revapi = sharedRevapi;
        this.revapiConstructor = revapiConstructor;
    }

    public static String getProjectArtifactCoordinates(MavenProject project, String versionOverride) {

        org.apache.maven.artifact.Artifact artifact = project.getArtifact();

        String extension = artifact.getArtifactHandler().getExtension();

        String version = versionOverride == null ? project.getVersion() : versionOverride;

        if (artifact.hasClassifier()) {
            return project.getGroupId() + ":" + project.getArtifactId() + ":" + extension + ":" +
                    artifact.getClassifier() + ":" + version;
        } else {
            return project.getGroupId() + ":" + project.getArtifactId() + ":" + extension + ":" +
                    version;
        }
    }

    ValidationResult validateConfiguration() throws Exception {
        buildRevapi();

        AnalysisContext.Builder ctxBuilder = AnalysisContext.builder(revapi).withLocale(locale);
        gatherConfig(ctxBuilder);

        ctxBuilder.withData(contextData);

        return revapi.validateConfiguration(ctxBuilder.build());
    }

    /**
     * Resolves the gav using the resolver. If the gav corresponds to the project artifact and is an unresolved version
     * for a RELEASE or LATEST, the gav is resolved such it a release not newer than the project version is found that
     * optionally corresponds to the provided version regex, if provided.
     *
     * <p>If the gav exactly matches the current project, the file of the artifact is found on the filesystem in
     * target directory and the resolver is ignored.
     *
     * @param project the project to restrict by, if applicable
     * @param gav the gav to resolve
     * @param versionRegex the optional regex the version must match to be considered.
     * @param resolver the version resolver to use
     * @return the resolved artifact matching the criteria.
     *
     * @throws VersionRangeResolutionException on error
     * @throws ArtifactResolutionException on error
     */
    static Artifact resolveConstrained(MavenProject project, String gav, Pattern versionRegex, ArtifactResolver resolver)
            throws VersionRangeResolutionException, ArtifactResolutionException {
        boolean latest = gav.endsWith(":LATEST");
        if (latest || gav.endsWith(":RELEASE")) {
            Artifact a = new DefaultArtifact(gav);

            if (latest) {
                versionRegex = versionRegex == null ? ANY : versionRegex;
            } else {
                versionRegex = versionRegex == null ? ANY_NON_SNAPSHOT : versionRegex;
            }

            String upTo = project.getGroupId().equals(a.getGroupId()) && project.getArtifactId().equals(a.getArtifactId())
                    ? project.getVersion()
                    : null;

            return resolver.resolveNewestMatching(gav, upTo, versionRegex, latest, latest);
        } else {
            String projectGav = getProjectArtifactCoordinates(project, null);
            Artifact ret = null;

            if (projectGav.equals(gav)) {
                ret = findProjectArtifact(project);
            }

            return ret == null ? resolver.resolveArtifact(gav) : ret;
        }
    }

    private static Artifact findProjectArtifact(MavenProject project) {
        String extension = project.getArtifact().getArtifactHandler().getExtension();

        String fileName = project.getModel().getBuild().getFinalName() + "." + extension;
        File f = new File(new File(project.getBasedir(), "target"), fileName);
        if (f.exists()) {
            Artifact ret = RepositoryUtils.toArtifact(project.getArtifact());
            return ret.setFile(f);
        } else {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    void resolveArtifacts() {
        if (resolvedOldApi == null) {
            final ArtifactResolver resolver = new ArtifactResolver(repositorySystem, repositorySystemSession,
                    project.getRemoteProjectRepositories());

            Function<String, MavenArchive> toFileArchive = gav -> {
                try {
                    Artifact a = resolveConstrained(project, gav, versionRegex, resolver);
                    return MavenArchive.of(a);
                } catch (ArtifactResolutionException | VersionRangeResolutionException | IllegalArgumentException e) {
                    throw new MarkerException(e.getMessage(), e);
                }
            };

            List<MavenArchive> oldArchives = new ArrayList<>(1);
            try {
                if (oldGavs != null) {
                    oldArchives = Stream.of(oldGavs).map(toFileArchive).collect(toList());
                }
                if (oldArtifacts != null) {
                    oldArchives.addAll(Stream.of(oldArtifacts).map(MavenArchive::of).collect(toList()));
                }
            } catch (MarkerException | IllegalArgumentException e) {
                String message = "Failed to resolve old artifacts: " + e.getMessage() + ".";

                if (failOnMissingArchives) {
                    throw new IllegalStateException(message, e);
                } else {
                    log.warn(message + " The API analysis will proceed comparing the new archives against an empty" +
                            " archive.");
                }
            }

            List<MavenArchive> newArchives = new ArrayList<>(1);
            try {
                if (newGavs != null) {
                    newArchives = Stream.of(newGavs).map(toFileArchive).collect(toList());
                }
                if (newArtifacts != null) {
                    newArchives.addAll(Stream.of(newArtifacts).map(MavenArchive::of).collect(toList()));
                }
            } catch (MarkerException | IllegalArgumentException e) {
                String message = "Failed to resolve new artifacts: " + e.getMessage() + ".";

                if (failOnMissingArchives) {
                    throw new IllegalStateException(message, e);
                } else {
                    log.warn(message + " The API analysis will not proceed.");
                    return;
                }
            }

            //now we need to be a little bit clever. When using RELEASE or LATEST as the version of the old artifact
            //it might happen that it gets resolved to the same version as the new artifacts - this notoriously happens
            //when releasing using the release plugin - you first build your artifacts, put them into the local repo
            //and then do the site updates for the released version. When you do the site, maven will find the released
            //version in the repo and resolve RELEASE to it. You compare it against what you just built, i.e. the same
            //code, et voila, the site report doesn't ever contain any found differences...

            Set<MavenArchive> oldTransitiveDeps = new HashSet<>();
            Set<MavenArchive> newTransitiveDeps = new HashSet<>();

            if (resolveDependencies) {
                String[] resolvedOld = oldArchives.stream().map(MavenArchive::getName).toArray(String[]::new);
                String[] resolvedNew = newArchives.stream().map(MavenArchive::getName).toArray(String[]::new);
                oldTransitiveDeps.addAll(collectDeps("old", resolver, resolvedOld));
                newTransitiveDeps.addAll(collectDeps("new", resolver, resolvedNew));
            }

            resolvedOldApi = API.of(oldArchives).supportedBy(oldTransitiveDeps).build();
            resolvedNewApi = API.of(newArchives).supportedBy(newTransitiveDeps).build();
        }
    }

    private Set<MavenArchive> collectDeps(String depDescription, ArtifactResolver resolver, String... gavs) {
        try {
            if (gavs == null) {
                return Collections.emptySet();
            }
            ArtifactResolver.CollectionResult res = resolver.collectTransitiveDeps(gavs);
            return collectDeps(depDescription, res);
        } catch (RepositoryException e) {
            return handleResolutionError(e, depDescription, null);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<MavenArchive> collectDeps(String depDescription, ArtifactResolver.CollectionResult res) {
        Set<MavenArchive> ret = null;
        try {
            ret = new HashSet<>();
            for (Artifact a : res.getResolvedArtifacts()) {
                try {
                    ret.add(MavenArchive.of(a));
                } catch (IllegalArgumentException e) {
                    res.getFailures().add(e);
                }
            }

            if (!res.getFailures().isEmpty()) {
                StringBuilder bld = new StringBuilder();
                for (Exception e : res.getFailures()) {
                    bld.append(e.getMessage()).append(", ");
                }
                bld.replace(bld.length() - 2, bld.length(), "");
                throw new MarkerException("Resolution of some artifacts failed: " + bld.toString());
            } else {
                return ret;
            }
        } catch (MarkerException e) {
            return handleResolutionError(e, depDescription, ret);
        }
    }

    private Set<MavenArchive> handleResolutionError(Exception e, String depDescription, Set<MavenArchive> toReturn) {
        String message = "Failed to resolve dependencies of " + depDescription + " artifacts: " + e.getMessage() +
                ".";
        if (failOnMissingSupportArchives) {
            throw new IllegalArgumentException(message, e);
        } else {
            if (log.isDebugEnabled()) {
                log.warn(message + ". The API analysis might produce unexpected results.", e);
            } else {
                log.warn(message + ". The API analysis might produce unexpected results.");
            }
            return toReturn == null ? Collections.<MavenArchive>emptySet() : toReturn;
        }
    }

    @SuppressWarnings("unchecked")
    AnalysisResult analyze() throws MojoExecutionException {
        resolveArtifacts();

        if (resolvedOldApi == null || resolvedNewApi == null) {
            return AnalysisResult.fakeSuccess();
        }

        List<?> oldArchives = StreamSupport.stream(
                (Spliterator<MavenArchive>) resolvedOldApi.getArchives().spliterator(), false)
                .map(MavenArchive::getName).collect(toList());

        List<?> newArchives =  StreamSupport.stream(
                (Spliterator<MavenArchive>) resolvedNewApi.getArchives().spliterator(), false)
                .map(MavenArchive::getName).collect(toList());

        log.info("Comparing " + oldArchives + " against " + newArchives +
                (resolveDependencies ? " (including their transitive dependencies)." : "."));

        try {
            buildRevapi();

            AnalysisContext.Builder ctxBuilder = AnalysisContext.builder(revapi).withOldAPI(resolvedOldApi)
                    .withNewAPI(resolvedNewApi).withLocale(locale);
            gatherConfig(ctxBuilder);

            ctxBuilder.withData(contextData);

            return revapi.analyze(ctxBuilder.build());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze archives", e);
        }
    }

    public API getResolvedNewApi() {
        return resolvedNewApi;
    }

    public API getResolvedOldApi() {
        return resolvedOldApi;
    }

    public Revapi getRevapi() {
        buildRevapi();
        return revapi;
    }

    @SuppressWarnings("unchecked")
    private void gatherConfig(AnalysisContext.Builder ctxBld) throws MojoExecutionException {
        if (analysisConfigurationFiles != null && analysisConfigurationFiles.length > 0) {
            for (Object pathOrConfigFile : analysisConfigurationFiles) {
                ConfigurationFile configFile;
                if (pathOrConfigFile instanceof String) {
                    configFile = new ConfigurationFile();
                    configFile.setPath((String) pathOrConfigFile);
                } else {
                    configFile = (ConfigurationFile) pathOrConfigFile;
                }

                String path = configFile.getPath();
                String resource = configFile.getResource();

                if (path == null && resource == null) {
                    throw new MojoExecutionException(
                            "Either 'path' or 'resource' has to be specified in a configurationFile definition.");
                } else if (path != null && resource != null) {
                    throw new MojoExecutionException(
                            "Either 'path' or 'resource' has to be specified in a configurationFile definition but" +
                                    " not both.");
                }

                String readErrorMessage = "Error while processing the configuration file on "
                        + (path == null ? "classpath " + resource : "path " + path);

                Supplier<Iterator<InputStream>> configFileContents;

                if (path != null) {
                    File f = new File(path);
                    if (!f.isAbsolute()) {
                        f = new File(project.getBasedir(), path);
                    }

                    if (!f.isFile() || !f.canRead()) {
                        String message = "Could not locate analysis configuration file '" + f.getAbsolutePath() + "'.";
                        if (failOnMissingConfigurationFiles) {
                            throw new MojoExecutionException(message);
                        } else {
                            log.debug(message);
                            continue;
                        }
                    }

                    final File ff = f;
                    configFileContents = () -> {
                        try {
                            return Collections.<InputStream>singletonList(new FileInputStream(ff)).iterator();
                        } catch (FileNotFoundException e) {
                            throw new MarkerException("Failed to read the configuration file '"
                                    + ff.getAbsolutePath() + "'.", e);
                        }
                    };
                } else {
                    configFileContents =
                            () -> {
                                try {
                                    return Collections.list(getClass().getClassLoader().getResources(resource))
                                            .stream()
                                            .map(url -> {
                                                try {
                                                    return url.openStream();
                                                } catch (IOException e) {
                                                    throw new MarkerException (
                                                            "Failed to read the classpath resource '" + url + "'.");
                                                }
                                            }).iterator();
                                } catch (IOException e) {
                                    throw new IllegalArgumentException(
                                            "Failed to locate classpath resources on path '" + resource + "'.");
                                }
                            };
                }

                Iterator<InputStream> it = configFileContents.get();
                List<Integer> nonJsonIndexes = new ArrayList<>(4);
                int idx = 0;
                while (it.hasNext()) {
                    ModelNode config;
                    try (InputStream in = it.next()) {
                        config = readJson(in);
                    } catch (MarkerException | IOException e) {
                        throw new MojoExecutionException(readErrorMessage, e.getCause());
                    }

                    if (config == null) {
                        nonJsonIndexes.add(idx);
                        continue;
                    }

                    mergeJsonConfigFile(ctxBld, configFile, config);

                    idx++;
                }

                if (!nonJsonIndexes.isEmpty()) {
                    idx = 0;
                    it = configFileContents.get();
                    while (it.hasNext()) {
                        try (Reader rdr = new InputStreamReader(it.next())) {
                            if (nonJsonIndexes.contains(idx)) {
                                mergeXmlConfigFile(ctxBld, configFile, rdr);
                            }
                        } catch (MarkerException | IOException | XmlPullParserException e) {
                            throw new MojoExecutionException(readErrorMessage, e.getCause());
                        }

                        idx++;
                    }
                }
            }
        }

        if (analysisConfiguration != null) {
            String text = analysisConfiguration.getValue();
            if (text == null) {
                convertNewStyleConfigFromXml(ctxBld, getRevapi());
            } else {
                ctxBld.mergeConfigurationFromJSON(text);
            }
        }
    }

    private void mergeXmlConfigFile(AnalysisContext.Builder ctxBld, ConfigurationFile configFile, Reader rdr)
            throws IOException, XmlPullParserException {
        XmlToJson<PlexusConfiguration> conv = new XmlToJson<>(revapi, PlexusConfiguration::getName,
                PlexusConfiguration::getValue, PlexusConfiguration::getAttribute, x -> Arrays.asList(x.getChildren()));

        PlexusConfiguration xml = new XmlPlexusConfiguration(Xpp3DomBuilder.build(rdr));

        String[] roots = configFile.getRoots();

        if (roots == null) {
            ctxBld.mergeConfiguration(conv.convert(xml));
        } else {
            roots: for (String r : roots) {
                PlexusConfiguration root = xml;
                boolean first = true;
                String[] rootPath = r.split("/");
                for (String name : rootPath) {
                    if (first) {
                        first = false;
                        if (!name.equals(root.getName())) {
                            continue roots;
                        }
                    } else {
                        root = root.getChild(name);
                        if (root == null) {
                            continue roots;
                        }
                    }
                }

                ctxBld.mergeConfiguration(conv.convert(root));
            }
        }
    }

    private void mergeJsonConfigFile(AnalysisContext.Builder ctxBld, ConfigurationFile configFile, ModelNode config) {
        String[] roots = configFile.getRoots();

        if (roots == null) {
            ctxBld.mergeConfiguration(config);
        } else {
            for (String r : roots) {
                String[] rootPath = r.split("/");
                ModelNode root = config.get(rootPath);

                if (!root.isDefined()) {
                    continue;
                }

                ctxBld.mergeConfiguration(root);
            }
        }
    }

    private void buildRevapi() {
        if (revapi == null) {
            Revapi.Builder builder = revapiConstructor.get();
            if (reporterType != null) {
                builder.withReporters(reporterType);
            }

            revapi = builder.build();
        }
    }

    private void convertNewStyleConfigFromXml(AnalysisContext.Builder bld, Revapi revapi) {
        XmlToJson<PlexusConfiguration> conv = new XmlToJson<>(revapi, PlexusConfiguration::getName,
                PlexusConfiguration::getValue, PlexusConfiguration::getAttribute, x -> Arrays.asList(x.getChildren()));

        bld.mergeConfiguration(conv.convert(analysisConfiguration));
    }

    private ModelNode readJson(InputStream in) {
        try {
            return ModelNode.fromJSONStream(JSONUtil.stripComments(in, Charset.forName("UTF-8")));
        } catch (IOException e) {
            return null;
        }
    }

    private static final class MarkerException extends RuntimeException {
        public MarkerException(String message) {
            super(message);
        }

        public MarkerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
