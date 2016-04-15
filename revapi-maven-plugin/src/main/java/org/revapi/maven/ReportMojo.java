/*
 * Copyright 2015 Lukas Krejci
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
 * limitations under the License
 */

package org.revapi.maven;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.revapi.API;
import org.revapi.Archive;
import org.revapi.CompatibilityType;
import org.revapi.DifferenceSeverity;
import org.revapi.Element;

/**
 * @author Lukas Krejci
 * @since 0.1
 */
@Mojo(name = "report", defaultPhase = LifecyclePhase.SITE, threadSafe = true)
public class ReportMojo extends AbstractMavenReport {

    /**
     * The JSON configuration of various analysis options. The available options depend on what
     * analyzers are present on the plugins classpath through the {@code &lt;dependencies&gt;}.
     *
     * <p>These settings take precedence over the configuration loaded from {@code analysisConfigurationFiles}.
     */
    @Parameter
    private String analysisConfiguration;

    /**
     * The list of files containing the configuration of various analysis options.
     * The available options depend on what analyzers are present on the plugins classpath through the
     * {@code &lt;dependencies&gt;}.
     *
     * <p>The {@code analysisConfiguration} can override the settings present in the files.
     *
     * <p>The list is either a list of strings or has the following form:
     * <pre><code>
     *    &lt;analysisConfigurationFiles&gt;
     *        &lt;configurationFile&gt;
     *            &lt;path&gt;path/to/the/file/relative/to/project/base/dir&lt;/path&gt;
     *            &lt;roots&gt;
     *                &lt;root&gt;configuration/root1&lt;/root&gt;
     *                &lt;root&gt;configuration/root2&lt;/root&gt;
     *                ...
     *            &lt;/roots&gt;
     *        &lt;/configurationFile&gt;
     *        ...
     *    &lt;/analysisConfigurationFiles&gt;
     * </code></pre>
     *
     * where
     * <ul>
     *     <li>{@code path} is mandatory,</li>
     *     <li>{@code roots} is optional and specifies the subtrees of the JSON config that should be used for
     *     configuration. If not specified, the whole file is taken into account.</li>
     * </ul>
     * The {@code configuration/root1} and {@code configuration/root2} are JSON paths to the roots of the
     * configuration inside that JSON config file. This might be used in cases where multiple configurations are stored
     * within a single file and you want to use a particular one.
     *
     * <p>An example of this might be a config file which contains API changes to be ignored in all past versions of a
     * library. The classes to be ignored are specified in a configuration that is specific for each version:
     * <pre><code>
     *     {
     *         "0.1.0" : {
     *             "revapi" : {
     *                 "ignore" : [
     *                     {
     *                         "code" : "java.method.addedToInterface",
     *                         "new" : "method void com.example.MyInterface::newMethod()",
     *                         "justification" : "This interface is not supposed to be implemented by clients."
     *                     },
     *                     ...
     *                 ]
     *             }
     *         },
     *         "0.2.0" : {
     *             ...
     *         }
     *     }
     * </code></pre>
     */
    @Parameter(property = "revapi.analysisConfigurationFiles")
    private Object[] analysisConfigurationFiles;

    /**
     * Set to false if you want to tolerate files referenced in the {@code analysisConfigurationFiles} missing on the
     * filesystem and therefore not contributing to the analysis configuration.
     *
     * <p>The default is {@code true}, which means that a missing analysis configuration file will fail the build.
     */
    @Parameter(property = "revapi.failOnMissingConfigurationFiles", defaultValue = "true")
    private boolean failOnMissingConfigurationFiles;

    /**
     * The coordinates of the old artifacts. Defaults to single artifact with the latest released version of the
     * current project.
     * <p/>
     * If the coordinates are exactly "BUILD" (without quotes) the build artifacts are used.
     * <p/>
     * If the this property is null, the {@link #oldVersion} property is checked for a value of the old version of the
     * artifact being built.
     *
     * @see #oldVersion
     */
    @Parameter(property = "revapi.oldArtifacts")
    private String[] oldArtifacts;

    /**
     * If you don't want to compare a different artifact than the one being built, specifying the just the old version
     * is simpler way of specifying the old artifact.
     * <p/>
     * The default value is "RELEASE" meaning that the old version is the last released version of the artifact being
     * built.
     */
    @Parameter(defaultValue = "RELEASE", property = "revapi.oldVersion")
    private String oldVersion;

    /**
     * The coordinates of the new artifacts. These are the full GAVs of the artifacts, which means that you can compare
     * different artifacts than the one being built. If you merely want to specify the artifact being built, use
     * {@link #newVersion} property instead.
     */
    @Parameter(property = "revapi.newArtifacts")
    private String[] newArtifacts;

    /**
     * The new version of the artifact. Defaults to "${project.version}".
     */
    @Parameter(defaultValue = "${project.version}", property = "revapi.newVersion")
    private String newVersion;

    /**
     * Problems with this or higher severity will be included in the report.
     * Possible values: nonBreaking, potentiallyBreaking, breaking.
     */
    @Parameter(defaultValue = "potentiallyBreaking", property = "revapi.reportSeverity")
    private FailSeverity reportSeverity;

    /**
     * Whether to skip the mojo execution.
     */
    @Parameter(defaultValue = "false", property = "revapi.skip")
    private boolean skip;

    @Parameter(property = "revapi.outputDirectory", defaultValue = "${project.reporting.outputDirectory}",
        required = true, readonly = true)
    private String outputDirectory;

    /**
     * Whether to include the dependencies in the API checks. This is the default thing to do because your API might
     * be exposing classes from the dependencies and thus classes from your dependencies could become part of your API.
     * <p>
     * However, setting this to false might be useful in situations where you have checked your dependencies in another
     * module and don't want do that again. In that case, you might want to configure Revapi to ignore missing classes
     * because it might find the classes from your dependencies as used in your API and would complain that it could not
     * find it. See <a href="http://revapi.org/modules/revapi-java/extensions/java.html">the docs</a>.
     */
    @Parameter(defaultValue = "true", property = "revapi.checkDependencies")
    private boolean checkDependencies;

    /**
     * If set, this property demands a format of the version string when the {@link #oldVersion} or {@link #newVersion}
     * parameters are set to {@code RELEASE} or {@code LATEST} special version strings.
     * <p>
     * Because Maven will report the newest non-snapshot version as the latest release, we might end up comparing a
     * {@code .Beta} or other pre-release versions with the new version. This might not be what you want and setting the
     * versionFormat will make sure that a newest version conforming to the version format is used instead of the one
     * resolved by Maven by default.
     * <p>
     * This parameter is a regular expression pattern that the version string needs to match in order to be considered
     * a {@code RELEASE}.
     */
    @Parameter(property = "revapi.versionFormat")
    private String versionFormat;

    @Component
    private Renderer siteRenderer;

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repositorySystemSession;

    /**
     * If true (the default) revapi will always download the information about the latest version from the remote
     * repositories (instead of using locally cached info). This will respect the offline settings.
     */
    @Parameter(defaultValue = "true", property = "revapi.alwaysCheckForReleaseVersion")
    private boolean alwaysCheckForReleaseVersion;

    /**
     * If true, the build will fail if one of the old or new artifacts fails to be resolved. Defaults to false.
     */
    @Parameter(defaultValue = "false", property = "revapi.failOnUnresolvedArtifacts")
    private boolean failOnUnresolvedArtifacts;

    /**
     * If true, the build will fail if some of the dependencies of the old or new artifacts fail to be resolved.
     * Defaults to false.
     */
    @Parameter(defaultValue = "false", property = "revapi.failOnUnresolvedDependencies")
    private boolean failOnUnresolvedDependencies;

    private API oldAPI;
    private API newAPI;
    private ReportTimeReporter reporter;

    @Override
    protected Renderer getSiteRenderer() {
        return siteRenderer;
    }

    @Override
    protected String getOutputDirectory() {
        return outputDirectory;
    }

    @Override
    protected MavenProject getProject() {
        return project;
    }

    @Override
    protected void executeReport(Locale locale) throws MavenReportException {
        ensureAnalyzed(locale);

        if (skip) {
            return;
        }

        if (oldAPI == null || newAPI == null) {
            throw new MavenReportException("Could not determine the artifacts to compare. If you're comparing the" +
                    " currently built version, have you run the package goal?");
        }

        Sink sink = getSink();
        ResourceBundle bundle = getBundle(locale);

        sink.head();
        sink.title();
        sink.text(bundle.getString("report.revapi.title"));
        sink.title_();
        sink.head_();

        sink.body();

        sink.section1();
        sink.sectionTitle1();
        sink.rawText(bundle.getString("report.revapi.title"));
        sink.sectionTitle1_();
        sink.paragraph();
        sink.text(getDescription(locale));
        sink.paragraph_();

        reportDifferences(reporter.reportsBySeverity.get(DifferenceSeverity.BREAKING), sink, bundle,
            "report.revapi.changes.breaking");
        reportDifferences(reporter.reportsBySeverity.get(DifferenceSeverity.POTENTIALLY_BREAKING), sink, bundle,
            "report.revapi.changes.potentiallyBreaking");
        reportDifferences(reporter.reportsBySeverity.get(DifferenceSeverity.NON_BREAKING), sink, bundle,
            "report.revapi.changes.nonBreaking");

        sink.section1_();
        sink.body_();
    }

    @Override
    public String getOutputName() {
        return "revapi-report";
    }

    @Override
    public String getName(Locale locale) {
        return getBundle(locale).getString("report.revapi.name");
    }

    @Override
    public String getDescription(Locale locale) {
        ensureAnalyzed(locale);

        if (oldAPI == null || newAPI == null) {
            getLog().debug("Was unable to determine the old and new artifacts to compare while determining" +
                    " the report description.");
            return null;
        } else {
            String message = getBundle(locale).getString("report.revapi.description");
            return MessageFormat.format(message, niceList(oldAPI.getArchives()), niceList(newAPI.getArchives()));
        }
    }

    private void ensureAnalyzed(Locale locale) {
        if (!skip && reporter == null) {
            reporter = new ReportTimeReporter(reportSeverity.asDifferenceSeverity());

            //noinspection Duplicates
            if (oldArtifacts == null || oldArtifacts.length == 0) {
                //bail out quickly for POM artifacts (or any other packaging without a file result) - there's nothing we can
                //analyze there
                //only do it here, because oldArtifacts might point to another artifact.
                //if we end up here in this branch, we know we'll be comparing the current artifact with something.
                if (project.getArtifact().getFile() == null) {
                    skip = true;
                    return;
                }

                oldArtifacts = new String[]{
                        Analyzer.getProjectArtifactCoordinates(project, repositorySystemSession, oldVersion)};
            }

            //noinspection Duplicates
            if (newArtifacts == null || newArtifacts.length == 0) {
                if (project.getArtifact().getFile() == null) {
                    skip = true;
                    return;
                }

                newArtifacts = new String[]{
                        Analyzer.getProjectArtifactCoordinates(project, repositorySystemSession, newVersion)};
            }

            Analyzer analyzer = new Analyzer(analysisConfiguration, analysisConfigurationFiles, oldArtifacts,
                    newArtifacts, project, repositorySystem, repositorySystemSession, reporter, locale, getLog(),
                    failOnMissingConfigurationFiles, failOnUnresolvedArtifacts, failOnUnresolvedDependencies,
                    alwaysCheckForReleaseVersion, checkDependencies, versionFormat);

            try {
                analyzer.analyze();

                oldAPI = analyzer.getResolvedOldApi();
                newAPI = analyzer.getResolvedNewApi();
            } catch (MojoExecutionException e) {
                throw new IllegalStateException("Failed to generate report.", e);
            }
        }
    }

    private ResourceBundle getBundle(Locale locale) {
        return ResourceBundle.getBundle("revapi-report", locale, this.getClass().getClassLoader());
    }

    private String niceList(Iterable<? extends Archive> archives) {
        StringBuilder bld = new StringBuilder();

        Iterator<? extends Archive> it = archives.iterator();

        if (it.hasNext()) {
            bld.append(it.next().getName());
        } else {
            return "";
        }

        while (it.hasNext()) {
            bld.append(", ").append(it.next().getName());
        }

        return bld.toString();
    }

    private void reportDifferences(
        EnumMap<CompatibilityType, List<ReportTimeReporter.DifferenceReport>> diffsPerType, Sink sink,
        ResourceBundle bundle, String typeKey) {

        if (diffsPerType == null || diffsPerType.isEmpty()) {
            return;
        }

        sink.section2();
        sink.sectionTitle2();
        sink.text(bundle.getString(typeKey));
        sink.sectionTitle2_();

        reportDifferences(diffsPerType.get(CompatibilityType.BINARY), sink, bundle,
            "report.revapi.compatibilityType.binary");
        reportDifferences(diffsPerType.get(CompatibilityType.SOURCE), sink, bundle,
            "report.revapi.compatibilityType.source");
        reportDifferences(diffsPerType.get(CompatibilityType.SEMANTIC), sink, bundle,
            "report.revapi.compatibilityType.semantic");
        reportDifferences(diffsPerType.get(CompatibilityType.OTHER), sink, bundle,
            "report.revapi.compatibilityType.other");

        sink.section2_();
    }

    private void reportDifferences(List<ReportTimeReporter.DifferenceReport> diffs, Sink sink, ResourceBundle bundle,
        String typeKey) {

        if (diffs == null || diffs.isEmpty()) {
            return;
        }

        sink.section3();
        sink.sectionTitle3();
        sink.text(bundle.getString(typeKey));
        sink.sectionTitle3_();

        sink.table();

        sink.tableRow();

        sink.tableHeaderCell();
        sink.text(bundle.getString("report.revapi.difference.code"));
        sink.tableHeaderCell_();

        sink.tableHeaderCell();
        sink.text(bundle.getString("report.revapi.difference.element"));
        sink.tableHeaderCell_();

        sink.tableHeaderCell();
        sink.text(bundle.getString("report.revapi.difference.description"));
        sink.tableHeaderCell_();

        sink.tableRow_();

        Collections.sort(diffs, new Comparator<ReportTimeReporter.DifferenceReport>() {
            @Override
            public int compare(ReportTimeReporter.DifferenceReport d1, ReportTimeReporter.DifferenceReport d2) {
                String c1 = d1.difference.code;
                String c2 = d2.difference.code;

                int cmp = c1.compareTo(c2);
                if (cmp != 0) {
                    return cmp;
                }

                Element e1 = d1.newElement == null ? d1.oldElement : d1.newElement;
                Element e2 = d2.newElement == null ? d2.oldElement : d2.newElement;

                cmp = e1.getClass().getName().compareTo(e2.getClass().getName());
                if (cmp != 0) {
                    return cmp;
                }

                return e1.getFullHumanReadableString().compareTo(e2.getFullHumanReadableString());
            }
        });

        for (ReportTimeReporter.DifferenceReport d : diffs) {
            String element = d.oldElement == null ? (d.newElement.getFullHumanReadableString()) :
                d.oldElement.getFullHumanReadableString();

            sink.tableRow();

            sink.tableCell();
            sink.monospaced();
            sink.text(d.difference.code);
            sink.monospaced_();
            sink.tableCell_();

            sink.tableCell();
            sink.monospaced();
            sink.bold();
            sink.text(element);
            sink.bold_();
            sink.monospaced_();

            sink.tableCell();
            sink.text(d.difference.description);
            sink.tableCell_();

            sink.tableRow_();
        }

        sink.table_();

        sink.section3_();
    }
}
