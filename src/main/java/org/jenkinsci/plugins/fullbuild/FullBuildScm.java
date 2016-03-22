/*
 * The MIT License
 *
 * Copyright (c) 2010, Brad Larson
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
package org.jenkinsci.plugins.fullbuild;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogParser;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import hudson.util.FormValidation;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The main entrypoint of the plugin. This class contains code to store user
 * configuration and to check out the code using a repo binary.
 */

@ExportedBean
public class FullBuildScm extends SCM implements Serializable {

	private static Logger debug = Logger
			.getLogger("hudson.plugins.full-build.FullBuildScm");

	// Advanced Fields:
	@CheckForNull private String repoUrl;
	@CheckForNull private String protocol;
	@CheckForNull private Boolean shallow;
	@CheckForNull private String branch;
    @CheckForNull private Set<String> repositories;

	/**
	 * Returns the manifest branch name. By default, this is null and repo
	 * defaults to "master".
	 */
	@Exported
	public String getBranch() {
		return branch;
	}

    @DataBoundSetter
    public void setBranch(String branch) { this.branch = branch; }

	/**
	 * Returns the repo url. by default, this is null and
	 * repo is fetched from aosp
	 */
	@Exported
	public String getRepoUrl() {
		return repoUrl;
	}

    @DataBoundSetter
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

	/**
	 * Returns the name of the mirror directory. By default, this is null and
	 * repo does not use a mirror.
	 */
	@Exported
	public String getProtocol() {
		return protocol;
	}

    @DataBoundSetter
    public void setProtocol(String protocol) { this.protocol = protocol; }

	/**
	 * Returns the depth used for sync.  By default, this is null and repo
	 * will sync the entire history.
	 */
	@Exported
	public Boolean getShallow() {
		return this.shallow;
	}

    @DataBoundSetter
    public void setShallow(Boolean shallow) { this.shallow = shallow; }

    /**
     * returns list of ignore projects.
     */
    @Exported
    public String getIgnoreProjects() {
        return StringUtils.join(repositories, '\n');
    }

    @DataBoundSetter
    public final void setIgnoreProjects(final String repositories) {
        if (repositories == null) {
            this.repositories = Collections.<String>emptySet();
            return;
        }
        this.repositories = new LinkedHashSet<String>(
                Arrays.asList(repositories.split("\\s+")));
    }

	/**
	 * The constructor takes in user parameters and sets them. Each job using
	 * the FullBuildScm will call this constructor.
	 *
	 * @param repoUrl The URL for the manifest repository.
	 */
	@DataBoundConstructor
	public FullBuildScm(final String repoUrl) {
		this.repoUrl = repoUrl;
		this.protocol = "gerrit";
		this.branch = null;
		this.shallow = true;
        this.repositories = Collections.<String>emptySet();
	}

	@Override
	public SCMRevisionState calcRevisionsFromBuild(
			@Nonnull final Run<?, ?> build, @Nullable final FilePath workspace,
			@Nullable final Launcher launcher, @Nonnull final TaskListener listener
			) throws IOException, InterruptedException {
		// We add our SCMRevisionState from within checkout, so this shouldn't
		// be called often. However it will be called if this is the first
		// build, if a build was aborted before it reported the repository
		// state, etc.
		return null;
	}


	/**
	 * @param environment   an existing environment, which contains already
	 *                      properties from the current build
	 * @param project       the project that is being built
	 */
	private EnvVars getEnvVars(final EnvVars environment,
							   final Job<?, ?> project) {
		// create an empty vars map
		final EnvVars finalEnv = new EnvVars();
		final ParametersDefinitionProperty params = project.getProperty(
				ParametersDefinitionProperty.class);
		if (params != null) {
			for (ParameterDefinition param
					: params.getParameterDefinitions()) {
				if (param instanceof StringParameterDefinition) {
					final StringParameterDefinition stpd =
							(StringParameterDefinition) param;
					final String dflt = stpd.getDefaultValue();
					if (dflt != null) {
						finalEnv.put(param.getName(), dflt);
					}
				}
			}
		}
		// now merge the settings from the last build environment
		if (environment != null) {
			finalEnv.overrideAll(environment);
		}

		EnvVars.resolve(finalEnv);
		return finalEnv;
	}

	@Override
	public void checkout(
			@Nonnull final Run<?, ?> build, @Nonnull final Launcher launcher,
			@Nonnull final FilePath workspace, @Nonnull final TaskListener listener,
			@CheckForNull final File changelogFile, @CheckForNull final SCMRevisionState baseline)
			throws IOException, InterruptedException {

		FilePath repoDir = workspace;

		Job<?, ?> job = build.getParent();
		EnvVars env = build.getEnvironment(listener);
		env = getEnvVars(env, job);
		if (!checkoutCode(launcher, repoDir, env, listener.getLogger(), changelogFile)) {
			throw new IOException("Could not init workspace");
		}
/*
        if (changelogFile != null) {
            ChangeLog.saveChangeLog(currentState, previousState, changelogFile,
                    launcher, repoDir, showAllChanges);
        }
*/
/*
		final String manifest =
				getStaticManifest(launcher, repoDir, listener.getLogger());
		final String manifestRevision =
				getManifestRevision(launcher, repoDir, listener.getLogger());
		final String expandedBranch = env.expand(manifestBranch);
		final RevisionState currentState =
				new RevisionState(manifest, manifestRevision, expandedBranch,
						listener.getLogger());
		build.addAction(currentState);

		final Run previousBuild = build.getPreviousBuild();
		final RevisionState previousState =
				getLastState(previousBuild, expandedBranch);

		if (changelogFile != null) {
			ChangeLog.saveChangeLog(currentState, previousState, changelogFile,
					launcher, repoDir, showAllChanges);
		}
		build.addAction(new TagAction(build));
		*/
	}


	private boolean checkoutCode(
            final Launcher launcher,
            final FilePath workspace,
            final EnvVars env,
            final OutputStream logger,
            File changelogFile)
			throws IOException, InterruptedException {

        final List<String> commands = new ArrayList<String>(4);

		// init workspace first
        debug.log(Level.INFO, "Initializing workspace in: " + workspace.getName());
        commands.clear();
		commands.add(getDescriptor().getExecutable());
		commands.add("init");
		commands.add(env.expand(this.protocol));
		commands.add(env.expand(this.repoUrl));
		commands.add(workspace.getRemote());
		int initRetCode =
				launcher.launch().stdout(logger).pwd(workspace)
						.cmds(commands).envs(env).join();
		if (initRetCode != 0) {
			return false;
		}

		// install package
        debug.log(Level.INFO, "Installing packages");
        commands.clear();
        commands.add(getDescriptor().getExecutable());
        commands.add("install");
		int installRetCode =
				launcher.launch().stdout(logger).pwd(workspace)
						.cmds(commands).envs(env).join();
		if (installRetCode != 0) {
			return false;
		}

        // clone repositories
        debug.log(Level.INFO, "Cloning repositories");
        commands.clear();
        commands.add(getDescriptor().getExecutable());
        commands.add("clone");
        if(shallow)
            commands.add("--shallow");
        commands.add(env.expand(StringUtils.join(repositories, ' ')));
        int cloneRetCode =
                launcher.launch().stdout(logger).pwd(workspace)
                        .cmds(commands).envs(env).join();
        if (cloneRetCode != 0) {
            return false;
        }

        // get changes
        debug.log(Level.INFO, "Getting changes");
        commands.clear();
        commands.add(getDescriptor().getExecutable());
        commands.add("history");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        int changeRetCode =
                launcher.launch().stdout(byteArrayOutputStream).pwd(workspace)
                        .cmds(commands).envs(env).join();
        if (changeRetCode != 0) {
            return false;
        }

        try (OutputStream outputStream = new FileOutputStream(changelogFile))
        {
            byteArrayOutputStream.writeTo(outputStream);

        }

        return true;
	}

    /*
	@Override
	public ChangeLogParser createChangeLogParser() {
		return new ChangeLog();
	}
	*/

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	@Nonnull
	@Override
	public String getKey() {
		return new StringBuilder("full-build")
			.append(' ')
			.append(getRepoUrl())
			.toString();
	}

	/**
	 * A DescriptorImpl contains variables used server-wide. In our263 case, we
	 * only store the path to the repo executable, which defaults to just
	 * "repo". This class also handles some Jenkins housekeeping.
	 */
	@Extension
	public static class DescriptorImpl extends SCMDescriptor<FullBuildScm> {
		private String repoExecutable;

		/**
		 * Call the superclass constructor and load our configuration from the
		 * file system.
		 */
		public DescriptorImpl() {
			super(null);
			load();
		}

		@Override
		public String getDisplayName() {
			return "FullBuild";
		}

		@Override
		public boolean configure(final StaplerRequest req,
				final JSONObject json)
				throws hudson.model.Descriptor.FormException {
			repoExecutable =
					Util.fixEmptyAndTrim(json.getString("executable"));
			save();
			return super.configure(req, json);
		}

		/**
		 * Check that the specified parameter exists on the file system and is a
		 * valid executable.
		 *
		 * @param value
		 *            A path to an executable on the file system.
		 * @return Error if the file doesn't exist, otherwise return OK.
		 */
		public FormValidation doExecutableCheck(
				@QueryParameter final String value) {
			return FormValidation.validateExecutable(value);
		}

		/**
		 * Returns the command to use when running repo. By default, we assume
		 * that repo is in the server's PATH and just return "repo".
		 */
		public String getExecutable() {
			if (repoExecutable == null) {
				return "fullbuild.exe";
			} else {
				return repoExecutable;
			}
		}

		@Override
		public boolean isApplicable(final Job project) {
			return true;
		}
	}
}
