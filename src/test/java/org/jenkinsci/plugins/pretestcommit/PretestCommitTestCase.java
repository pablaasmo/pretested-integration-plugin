package org.jenkinsci.plugins.pretestcommit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jvnet.hudson.test.HudsonTestCase;

import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.model.*;
import hudson.plugins.mercurial.MercurialSCM;
import hudson.scm.SCM;
import hudson.tasks.BuildWrapper;
import hudson.tasks.Publisher;
import hudson.util.StreamTaskListener;

import org.junit.*;

/**
 * Base class for testing classes that requires a jenkins instance. This class 
 * should only contain methods that are used across testcases.
 * @author rel
 *
 */
public abstract class PretestCommitTestCase extends HudsonTestCase {
	
	public FreeStyleProject project;
	public BuildWrapper buildWrapper;
	
	public String jobName = "test";
	public String jenkinsRoot = "localhost:8080";/*Hudson.getInstance()
			.getRootUrl()
			.replaceAll("^http://|/$", "");*/
	
	protected TaskListener listener;
	protected Launcher launcher;
	
	@Before
	public File setup() throws Exception, IOException {
		//Create the project

		listener = new StreamTaskListener(System.out, Charset.defaultCharset());
        launcher = Hudson.getInstance().createLauncher(listener);
        
		FreeStyleProject project = createFreeStyleProject();
		File tmp = createTempDirectory();
		String local = tmp.getPath() + "/local";
		String stage = tmp.getPath() + "/stage";
		String remote = tmp.getPath() + "/remote";
		
		
		//Initialise repositories
		hg("init",remote);
		hg("clone",remote,stage);
		hg("clone",remote,local);
		
		//Local repo pushes to stage
		File localHgrc = new File(local,".hg/hgrc");
		FileWriter localFw = new FileWriter(localHgrc);
		localFw.write("[paths]\r\ndefault="+remote+"\r\ndefault-push="+stage);
		localFw.close();
		
		//Correctly setup the stage repository so that it notifies jenkins
		File stageHg = new File(stage,".hg");
		File stageHgrc = new File(stageHg,"hgrc");
		FileWriter stageFw = new FileWriter(stageHgrc);
		stageFw.write("[default]\r\n\tchangegroup=\"hg_changegroup_hook.py\"");
		stageFw.close();
		writeChangegroupHook(stageHg);
		
		
		//Setup the correct scm
		SCM scm = new MercurialSCM("default",remote,"default","","", null, false, true);
		project.setScm(scm);
		
		//Make sure the buildwrapper is added to the project and configured correctly
		BuildWrapper buildWrapper = new PretestCommitPreCheckout(stage);
		//Ensure that it is added per default
		project.getBuildWrappersList().add(buildWrapper);

		//Make sure the publisher is added to the project
		Publisher notifier = new PretestCommitPostCheckout();
		project.getPublishersList().add(notifier);
		return tmp;
		
		//Configure the project
	}

	//Thank you MercurialSCM
	static ProcStarter launch(Launcher launcher) {
		return launcher.launch().envs(Collections.singletonMap("HGPLAIN", "true"));
	}

    protected final void hg(String... args) throws Exception {
        List<String> cmds = assembleHgCommand(args);
        assertEquals(0, launch(launcher).cmds(cmds).stdout(listener).join());
    }

    protected final void hg(File repo, String... args) throws Exception {
        List<String> cmds = assembleHgCommand(args);
        assertEquals(0, launch(launcher).cmds(cmds).pwd(repo).stdout(listener).join());
    }	
    
	public List<String> assembleHgCommand(String[] args){
        List<String> cmds = new ArrayList<String>();
        cmds.add("hg");
        cmds.add("--config");
        cmds.add("ui.username=nobody@nowhere.net");
        cmds.addAll(Arrays.asList(args));
        return cmds;
	}
	
	//This is the 
	//public void testShouldCommitToRemote(){
		
	//Given The stage and remote repositories are in sync
		//Setup empty local, stage and remote repository
		//write a simple file
		//
	//When I commit a change that does not break the build
	//Then the build should succeed
	//And be pushed to the remote repository
	//}
	
	public File createHgrc(File repoDir) throws IOException {
		File hgrc = new File(repoDir, "hgrc");
		if(hgrc.exists() || hgrc.createNewFile())
			return hgrc;
		throw new IOException("Could not create hgrc");
	}
	
	public File writeChangegroupHook(File repoDir) throws IOException {
		File hook = new File(repoDir, "hg_changegroup_hook.py");
		if(hook.exists() || hook.createNewFile()){
			//write the file
			FileWriter fw = new FileWriter(hook);
			fw.write("from mercurial import ui, hg\r\n");
			fw.write("from mercurial.node import hex\r\n");
			fw.write("from httplib import HTTPConnection\r\n");
			fw.write("from urllib import urlencode\r\n");
			fw.write("import os\r\n\r\n");
		
			fw.write("def run(ui, repo, **kwargs):\r\n");
			fw.write("\thttp = HTTPConnection(\"" 
					+ jenkinsRoot + "\")\r\n");
			fw.write("\thttp.request(\"GET\",\"http://" 
					+ jenkinsRoot + "/job/" 
					+ jobName + "/build\")\r\n");
			fw.write("\tui.warn(\"http://" 
					+ jenkinsRoot + "/job/" 
					+ jobName + "/build\\n\")\r\n");
			fw.write("\tui.warn(str(http.getresponse().read())"
					+ "+\"\\n\")\r\n");
			fw.write("\treturn False\r\n");
			
			fw.close();
			return hook;
		}
		throw new IOException("Could not write changegroup hook file");
	}
	
	public File setupHgRepository(File repoDir) throws Exception, IOException {
		
		hg("init", repoDir.getPath());
		File hgDir = new File(repoDir, ".hg");
		if(hgDir.exists())
			return hgDir;
		throw new IOException("Could not create repository");
	}
	
	/**
	 * Borrowed from
	 * http://stackoverflow.com/questions/617414/create-a-temporary-directory-in-java
	 * potentially unsafe, but we only use it for testing, so it suffice.
	 * @return A filepointer to a temporary directory
	 */
	public static File createTempDirectory()
		    throws IOException
		{
		    final File temp;

		    temp = File.createTempFile("prteco-", Long.toString(System.nanoTime()));
		    
		    if(!(temp.delete()))
		    {
		        throw new IOException("Could not delete temp file: " + temp.getAbsolutePath());
		    }
		    
		    if(!(temp.mkdir()))
		    {
		        throw new IOException("Could not create temp directory: " + temp.getAbsolutePath());
		    }
		    return (temp);
		}
}
