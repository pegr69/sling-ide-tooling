/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.ide.eclipse.wst.ui.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Iterator;

import org.apache.sling.ide.eclipse.wst.internal.SlingLaunchpadBehaviour;
import org.apache.sling.ide.eclipse.wst.internal.SlingLaunchpadServer;
import org.apache.sling.slingclipse.SlingclipsePlugin;
import org.apache.sling.slingclipse.api.Command;
import org.apache.sling.slingclipse.api.Repository;
import org.apache.sling.slingclipse.api.RepositoryException;
import org.apache.sling.slingclipse.api.ResponseType;
import org.apache.sling.slingclipse.api.Result;
import org.apache.sling.slingclipse.helper.SlingclipseHelper;
import org.apache.sling.slingclipse.helper.Tracer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.wst.server.core.IServer;
import org.json.JSONException;
import org.json.JSONML;
import org.json.JSONObject;

/**
 * Renders the import wizard container page for the Slingclipse repository
 * import.
 */
public class ImportWizard extends Wizard implements IImportWizard {
	private ImportWizardPage mainPage;

	/**
	 * Construct a new Import Wizard container instance.
	 */
	public ImportWizard() {
		super();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.wizard.Wizard#performFinish()
	 */
	public boolean performFinish() {
		
		if (mainPage.isPageComplete()) {

            final IServer server = mainPage.getServer();
	 
			IPath destinationPath = mainPage.getResourcePath();
			
			final IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(destinationPath.segments()[0]);
			final IPath projectRelativePath = destinationPath.removeFirstSegments(1);
			final String repositoryPath = mainPage.getRepositoryPath();
			
			Job job = new Job("Import") {

				protected IStatus run(IProgressMonitor monitor) {

                    Repository repository = SlingLaunchpadBehaviour.getRepository(server, monitor);

					Tracer tracer = SlingclipsePlugin.getDefault().getTracer();
					
					monitor.setTaskName("Loading configuration...");
					monitor.worked(5);
                    SlingLaunchpadServer launchpad = (SlingLaunchpadServer) server.loadAdapter(
                            SlingLaunchpadServer.class, monitor);
					
                    int oldPublishState = launchpad.getPublishState();
                    // TODO disabling publish does not work; since the publish is done async
                    // Not sure if there is a simple workaround. Anyway, the only side effect is that we
                    // make too many calls after the import, functionality is not affected
                    if (server.canPublish().isOK() && oldPublishState != SlingLaunchpadServer.PUBLISH_STATE_NEVER) {
                        launchpad.setPublishState(SlingLaunchpadServer.PUBLISH_STATE_NEVER);
                    }

					try {

						// TODO: We should try to make this give 'nice' progress feedback (aka here's what I'm processing)
						monitor.setTaskName("Importing...");
						monitor.worked(10);

                        // we create the root node and assume this is a folder
                        createRoot(project, projectRelativePath, repositoryPath);

				 		crawlChildrenAndImport(repository, repositoryPath, project, projectRelativePath, tracer);
						
						monitor.setTaskName("Import Complete");
						monitor.worked(100);
					} catch ( Exception e) {
						Status status = new Status(Status.ERROR, SlingclipsePlugin.PLUGIN_ID, "Failed importing repository ", e);
						SlingclipsePlugin.getDefault().getLog().log(status);
						return status;
					}finally{
                        if (oldPublishState != SlingLaunchpadServer.PUBLISH_STATE_NEVER) {
                            launchpad.setPublishState(oldPublishState);
                        }

					}
					
					return Status.OK_STATUS;
				}

                private void createRoot(final IProject project, final IPath projectRelativePath,
                        final String repositoryPath) throws CoreException {

                    IPath rootImportPath = projectRelativePath.append(repositoryPath);

                    for (int i = rootImportPath.segmentCount() - 1; i > 0; i--)
                        createFolder(project, rootImportPath.removeLastSegments(i));
                }
			};
			job.setSystem(false);
			job.setUser(true);
			job.schedule();
			return true;
		} else {
			return false;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchWizard#init(org.eclipse.ui.IWorkbench,
	 * org.eclipse.jface.viewers.IStructuredSelection)
	 */
	public void init(IWorkbench workbench, IStructuredSelection selection) {
		setWindowTitle("Repositoy Import"); // NON-NLS-1
		setNeedsProgressMonitor(true);
		mainPage = new ImportWizardPage("Import from Repository", selection); // NON-NLS-1
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.wizard.IWizard#addPages()
	 */
	public void addPages() {
		super.addPages();
		addPage(mainPage);
	}
	
	/**
	 * Crawls the repository and recursively imports founds resources
	 * 
	 * @param repository the sling repository to import from
	 * @param path the current path to import from
	 * @param project the project to create resources in
	 * @param projectRelativePath the path, relative to the project root, where the resources should be created
	 * @param tracer 
	 * @throws JSONException
	 * @throws RepositoryException
	 * @throws CoreException
	 */
	// TODO: This probably should be pushed into the service layer	
	private void crawlChildrenAndImport(Repository repository,String path,IProject project, IPath projectRelativePath, Tracer tracer) throws JSONException, RepositoryException, CoreException{

        System.out.println("crawlChildrenAndImport(" + repository + ", " + path + ", " + project + ", "
                + projectRelativePath + ")");

        String children = executeCommand(repository.newListChildrenNodeCommand(path, ResponseType.JSON), tracer);
		JSONObject json = new JSONObject(children);
		String primaryType= json.optString(Repository.JCR_PRIMARY_TYPE);
 
		if (Repository.NT_FILE.equals(primaryType)){
			importFile(repository, path, project, projectRelativePath, tracer);
		}else if (Repository.NT_FOLDER.equals(primaryType)){
			createFolder(project, projectRelativePath.append(path));
		}else if(Repository.NT_RESOURCE.equals(primaryType)){
			//DO NOTHING
		}else{		
			createFolder(project, projectRelativePath.append(path));
			String content = executeCommand(repository.newGetNodeContentCommand(path, ResponseType.JSON), tracer);
			JSONObject jsonContent = new JSONObject(content);
			jsonContent.put(SlingclipseHelper.TAG_NAME, Repository.JCR_ROOT);
			String contentXml = JSONML.toString(jsonContent);		
			createFile( project, projectRelativePath.append( path+"/"+SlingclipseHelper.CONTENT_XML), contentXml.getBytes(Charset.forName("UTF-8") /* TODO is this enough? */));
		}
 		
        for (Iterator<?> keys = json.keys(); keys.hasNext();) {
            String key = (String) keys.next();
			JSONObject innerjson=json.optJSONObject(key);
			if (innerjson!=null){
				crawlChildrenAndImport(repository, path+"/"+key, project, projectRelativePath, tracer);
			}
		}
	}

	private <T> T executeCommand(Command<T> command, Tracer tracer) throws RepositoryException {
		
		Result<T> result = command.execute();
		
		SlingclipsePlugin.getDefault().getTracer().trace("{0} : {1}.", command, result);
		
		return result.get();
	}	
	
	private void importFile(Repository repository,String path,IProject project, IPath destinationPath, Tracer tracer) throws JSONException, RepositoryException, CoreException{ 

        System.out.println("importFile: " + path + " -> " + destinationPath);

        byte[] node = executeCommand(repository.newGetNodeCommand(path), tracer);
			createFile(project, destinationPath.append(path), node );
	}
	
	private void createFolder(IProject project, IPath destinationPath) throws CoreException{

		IFolder destinationFolder = project.getFolder(destinationPath);
		if ( destinationFolder.exists() )
			return;

		SlingclipsePlugin.getDefault().getTracer().trace("Creating folder {0}", destinationFolder.getFullPath());
		
		destinationFolder.create(true, true, null /* TODO progress monitor */);
	}
	
	private void createFile(IProject project, IPath path, byte[] node) throws CoreException {		
		
		IFile destinationFile = project.getFile(path);
		if ( destinationFile.exists() )
			return;

		SlingclipsePlugin.getDefault().getTracer().trace("Creating file{0}", destinationFile.getFullPath());
		
		destinationFile.create(new ByteArrayInputStream(node), true, null /* TODO progress monitor */);
	}
}