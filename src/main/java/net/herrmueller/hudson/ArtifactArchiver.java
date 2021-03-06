/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Brian Westrich, Jean-Baptiste Quenot
 * Copyright (c) 2011, Conrad Mueller
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.herrmueller.hudson;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.*;
import hudson.util.FormValidation;
import java.io.File;
import java.io.IOException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * This is basically a copy of {@link hudson.tasks.ArtifactArchiver}
 * with the allowEmptyArchive property made configurable via the UI
 * instead of a system property.
 *
 * @author Kohsuke Kawaguchi
 * @author Conrad Mueller
 */
public class ArtifactArchiver extends Recorder {

    /**
     * Comma- or space-separated list of patterns of files/directories to be archived.
     */
    private final String artifacts;

    /**
     * Possibly null 'excludes' pattern as in Ant.
     */
    private final String excludes;

    /**
     * Just keep the last successful artifact set, no more.
     */
    private final boolean latestOnly;

    /**
     * Do not fail the build if no artifacts are present.
     */
    private final boolean allowEmptyArchive;

    @DataBoundConstructor
    public ArtifactArchiver(String artifacts, String excludes, boolean latestOnly, boolean allowEmptyArchive) {
        this.artifacts = artifacts;
        this.excludes = excludes;
        this.latestOnly = latestOnly;
        this.allowEmptyArchive = allowEmptyArchive;
    }

    public String getArtifacts() {
        return artifacts;
    }

    public String getExcludes() {
        return excludes;
    }

    public boolean isLatestOnly() {
        return latestOnly;
    }

    public boolean isAllowEmptyArchive() {
        return allowEmptyArchive;
    }

    private void listenerWarnOrError(BuildListener listener, String message) {
    	if (allowEmptyArchive) {
    		listener.getLogger().println(String.format("WARN: %s", message));
    	} else {
    		listener.error(message);
    	}
    }

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
        if(artifacts.length()==0) {
            listener.error(Messages.ArtifactArchiver_NoIncludes());
            build.setResult(Result.FAILURE);
            return true;
        }

        File dir = build.getArtifactsDir();
        dir.mkdirs();

        listener.getLogger().println(Messages.ArtifactArchiver_ARCHIVING_ARTIFACTS());
        try {
            FilePath ws = build.getWorkspace();
            if (ws==null) { // #3330: slave down?
                return true;
            }

            String artifacts = build.getEnvironment(listener).expand(this.artifacts);
            if(ws.copyRecursiveTo(artifacts,excludes,new FilePath(dir))==0) {
                if(build.getResult().isBetterOrEqualTo(Result.UNSTABLE)) {
                    // If the build failed, don't complain that there was no matching artifact.
                    // The build probably didn't even get to the point where it produces artifacts.
                    listenerWarnOrError(listener, Messages.ArtifactArchiver_NoMatchFound(artifacts));
                    String msg = null;
                    try {
                    	msg = ws.validateAntFileMask(artifacts);
                    } catch (Exception e) {
                    	listenerWarnOrError(listener, e.getMessage());
                    }
                    if(msg!=null)
                        listenerWarnOrError(listener, msg);
                }
                if (!allowEmptyArchive) {
                	build.setResult(Result.FAILURE);
                }
                return true;
            }
        } catch (IOException e) {
            Util.displayIOException(e,listener);
            e.printStackTrace(listener.error(
                    Messages.ArtifactArchiver_FailedToArchive(artifacts)));
            return true;
        }

        return true;
    }

    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
        if(latestOnly) {
            AbstractBuild<?,?> b = build.getProject().getLastCompletedBuild();
            Result bestResultSoFar = Result.NOT_BUILT;
            while(b!=null) {
                if (b.getResult().isBetterThan(bestResultSoFar)) {
                    bestResultSoFar = b.getResult();
                } else {
                    // remove old artifacts
                    File ad = b.getArtifactsDir();
                    if(ad.exists()) {
                        listener.getLogger().println(Messages.ArtifactArchiver_DeletingOld(b.getDisplayName()));
                        try {
                            Util.deleteRecursive(ad);
                        } catch (IOException e) {
                            e.printStackTrace(listener.error(e.getMessage()));
                        }
                    }
                }
                b = b.getPreviousBuild();
            }
        }
        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Archive build artifacts (gracefully)";
        }

        /**
         * Performs on-the-fly validation on the file mask wildcard.
         */
        public FormValidation doCheckArtifacts(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            return FilePath.validateFileMask(project.getSomeWorkspace(),value);
        }

        @Override
        public ArtifactArchiver newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return req.bindJSON(ArtifactArchiver.class,formData);
        }

    }

}
