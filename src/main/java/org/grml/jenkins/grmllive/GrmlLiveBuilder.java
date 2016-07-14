package org.grml.jenkins.grmllive;

import hudson.*;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * grml-live {@link Builder}.
 */
public class GrmlLiveBuilder extends Builder implements SimpleBuildStep {

    private final String name;

    private final String codename;
    private final String arch;
    private final String classes;
    private final String suite;
    private final String outputDirectory;
    private final String version;
    private final boolean versionFromDate;
    private final String extractIso;
    private final boolean buildOnly;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public GrmlLiveBuilder(String name, String codename, String arch, String classes, String suite, String outputDirectory, String version, boolean versionFromDate, String extractIso, boolean buildOnly) {
        this.name = name;
        this.codename = codename;
        this.arch = arch;
        this.classes = classes;
        this.suite = suite;
        this.outputDirectory = outputDirectory;
        this.version = version;
        this.versionFromDate = versionFromDate;
        this.extractIso = extractIso;
        this.buildOnly = buildOnly;
    }

    public String getName() {
        return name;
    }

    public String getCodename() {
        return codename;
    }

    public String getArch() {
        return arch;
    }

    public String getClasses() {
        return classes;
    }

    public String getSuite() {
        return suite;
    }

    public String getOutputDirectory() {
        return outputDirectory;
    }

    public String getVersion() {
        return version;
    }

    public boolean isVersionFromDate() {
        return versionFromDate;
    }

    public String getExtractIso() {
        return extractIso;
    }

    public boolean isBuildOnly() {
        return buildOnly;
    }


    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        ArgumentListBuilder args = new ArgumentListBuilder()
                .add("sudo")
                .add("-n")
                .add("grml-live")
                .add("-F")
                .add("-V")
                .add("-A");

        if (!arch.isEmpty()) {
            args.add("-a").add(arch);
        }

        if (!classes.isEmpty()) {
            args.add("-c").add(classes);
        }

        if (!suite.isEmpty()) {
            args.add("-s").add(suite);
        }

        if (buildOnly) {
            args.add("-b");
        }

        if (!extractIso.isEmpty()) {
            args.add("-e").add(extractIso);
        }

        hudson.EnvVars env;
        env = build.getEnvironment(listener);

        String user = env.get("GRML_LIVE_USER", env.get("USER", env.get("LOGNAME", "")));
        if (!user.isEmpty()) {
            args.add("-U").add(user);
        }

        String buildVersion;
        if (version.isEmpty()) {
            if (versionFromDate) {
                buildVersion = DateTimeFormatter.ofPattern("uuuuMMdd").format(ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()));
            } else {
                buildVersion = "build" + env.get("BUILD_NUMBER");
            }
        } else {
            buildVersion = version;
        }

        args.add("-v").add(buildVersion);

        args.add("-r");
        if (codename.isEmpty()) {
            args.add("autobuild-" + buildVersion);
        } else {
            args.add(codename);
        }

        String buildName = "autobuild";
        if (!name.isEmpty()) {
            buildName = name;
        }
        args.add("-g").add(buildName);

        args.add("-o");
        if (outputDirectory.isEmpty()) {
            args.add(env.get("WORKSPACE"));
        } else {
            args.add(outputDirectory);
        }

        listener.getLogger().println("Running grml-live...");
        Proc proc = launcher.launch()
                .cmds(args)
                .pwd(workspace)
                .stdout(listener.getLogger())
                .stderr(listener.getLogger())
                .start();
        int exitCode = proc.join();
        if (exitCode != 0) {
            throw new AbortException("Fatal error while running grml-live. Exit code: " + Integer.toString(exitCode));
        }
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link GrmlLiveBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/GrmlLiveBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a name");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the name too short?");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "grml-live";
        }
    }
}

