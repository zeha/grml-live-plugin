package org.grml.jenkins.grmllive;

import hudson.*;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * grml-live changelist {@link Builder}.
 */
public class GrmlLiveChangelistBuilder extends Builder implements SimpleBuildStep {

    private final String outputFilename;
    private final String dpkgListOldName;
    private final String packagePrefix;
    private final String gitUrlBase;
    private final String SEP = "------------------------------------------------------------------------\n";

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public GrmlLiveChangelistBuilder(String outputFilename, String dpkgListOldName, String packagePrefix, String gitUrlBase) {
        if (outputFilename.isEmpty()) {
            this.outputFilename = "changelog.txt";
        } else {
            this.outputFilename = outputFilename;
        }
        if (dpkgListOldName.isEmpty()) {
            this.dpkgListOldName = "dpkg.list.old";
        } else {
            this.dpkgListOldName = dpkgListOldName;
        }
        this.packagePrefix = packagePrefix;
        this.gitUrlBase = gitUrlBase;
    }

    public String getOutputFilename() {
        return outputFilename;
    }

    public String getDpkgListOldName() {
        return dpkgListOldName;
    }

    public String getPackagePrefix() {
        return packagePrefix;
    }

    public String getGitUrlBase() {
        return gitUrlBase;
    }

    private void joinAndAbortNonZeroExitCode(Proc proc) throws IOException, InterruptedException {
        int exitCode = proc.join();
        if (exitCode != 0) {
            throw new AbortException("Command exited with code: " + Integer.toString(exitCode));
        }
    }

    private void simpleLaunch(Launcher launcher, ArgumentListBuilder args, FilePath pwd, TaskListener listener) throws InterruptedException, IOException {
        Proc proc = launcher.launch()
                .cmds(args)
                .pwd(pwd)
                .stdout(listener.getLogger())
                .stderr(listener.getLogger())
                .start();
        joinAndAbortNonZeroExitCode(proc);
    }

    private String makeGitChangelog(String projectName, String revisionRange, FilePath gitWorkingPath, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        listener.getLogger().println("Building git changelog for " + projectName + " revisions " + revisionRange);
        String gitUrl = getGitUrlBase() + "/" + projectName;
        FilePath gitDir = gitWorkingPath.child(projectName + ".git");
        if (!gitDir.exists()) {
            listener.getLogger().println("> Cloning git from " + gitUrl);
            simpleLaunch(launcher, new ArgumentListBuilder("git", "clone", "--mirror", gitUrl), gitWorkingPath, listener);
        }
        if (!gitDir.exists()) {
            throw new AbortException("Cloning from " + gitUrl + " into " + gitDir + " failed: output directory not found.");
        }

        listener.getLogger().println("> Updating git from " + gitUrl);
        simpleLaunch(launcher, new ArgumentListBuilder("git", "remote", "set-url", "origin", gitUrl), gitDir, listener);

        simpleLaunch(launcher, new ArgumentListBuilder("git", "remote", "update", "--prune"), gitDir, listener);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Proc proc = launcher.launch()
                .cmds(new ArgumentListBuilder("git", "log", "--oneline", revisionRange))
                .pwd(gitDir)
                .stdout(baos)
                .stderr(listener.getLogger())
                .start();
        joinAndAbortNonZeroExitCode(proc);
        return baos.toString("UTF-8");
    }

    private HashMap<String, String> parsePackageList(FilePath packageList, TaskListener listener) throws InterruptedException, IOException {
        listener.getLogger().println("Parsing package list " + packageList.getRemote());
        String packages = packageList.readToString();
        HashMap<String, String> m = new HashMap<>();
        Pattern p = Pattern.compile("^ii\\s+(\\S+)\\s+(\\S+)\\s");
        for(String line : packages.split("\n")) {
            Matcher matcher = p.matcher(line);
            if (matcher.find()) {
                m.put(matcher.group(1), matcher.group(2));
            }
        }
        return m;
    }

    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        hudson.EnvVars env;

        env = build.getEnvironment(listener);

        FilePath gitWorkingPath = workspace.child("packages");
        gitWorkingPath.mkdirs();

        StringBuilder changelogBuilder = new StringBuilder()
                .append(SEP)
                .append("Generated by GrmlLiveChangelistBuilder for job\n")
                .append(env.get("JOB_NAME")).append(" ").append(env.get("BUILD_ID")).append("\n")
                .append(SEP)
                ;

        FilePath packageList = workspace.child("grml_logs").child("fai").child("dpkg.list");
        FilePath packageListOld = workspace.child(getDpkgListOldName());

        if (!packageList.exists()) {
            throw new AbortException("Could not find package list: " + packageList.getRemote());
        }

        HashMap<String,String> packages = parsePackageList(packageList, listener);
        HashMap<String,String> packagesOld = new HashMap<>();
        try {
            packagesOld = parsePackageList(packageListOld, listener);
        } catch (IOException | InterruptedException e) {
            listener.getLogger().println("Parsing old package list failed: " + e.toString());
        }

        Set<String> debianRemoved = new TreeSet<>();
        Set<String> debianAdded = new TreeSet<>();
        Set<String> debianChanged = new TreeSet<>();

        // .keySet() returns a modifiable View, must copy it.
        Set<String> removedPackages = new TreeSet<>(packagesOld.keySet());
        removedPackages.removeAll(packages.keySet());
        for(String packageName : removedPackages) {
            if (packageName.indexOf(getPackagePrefix()) == 0) {
                changelogBuilder.append("\n")
                        .append(packageName).append("\n").append("Removed.").append(SEP);
            } else {
                debianRemoved.add(packageName);
            }
        }

        for(String packageName: packages.keySet()) {
            String newVersion = packages.get(packageName);
            String oldVersion = packagesOld.get(packageName);
            if (oldVersion != null && oldVersion.equals(newVersion)) {
                continue;
            }

            if (packageName.indexOf(getPackagePrefix()) == 0) {
                String range = "v" + newVersion;
                if (oldVersion != null) {
                    range = "v" + oldVersion + ".." + range;
                }
                changelogBuilder.append("\n")
                        .append(packageName).append(" ").append(range).append("\n")
                        .append("Changes:\n")
                        .append(makeGitChangelog(packageName, range, gitWorkingPath, launcher, listener))
                        .append(SEP);
            } else {
                if (oldVersion != null) {
                    debianChanged.add(packageName + " " + oldVersion + " -> " + newVersion);
                } else {
                    debianAdded.add(packageName);
                }
            }
        }

        changelogBuilder.append("\n")
            .append("Changes to Debian package list:\n")
            .append("  Added:\n     ")
            .append(String.join("\n     ", debianAdded).trim()).append("\n")
            .append("  Changed:\n     ")
            .append(String.join("\n     ", debianChanged).trim()).append("\n")
            .append("  Removed:\n     ")
            .append(String.join("\n     ", debianRemoved).trim()).append("\n")
            .append(SEP);


        FilePath changelogFile = workspace.child(getOutputFilename());
        listener.getLogger().println("Writing changelog to " + changelogFile.getRemote());
        changelogFile.write(changelogBuilder.toString(), "UTF-8");
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link GrmlLiveChangelistBuilder}. Used as a singleton.
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
            return "grml-live: generate changelist";
        }
    }
}

