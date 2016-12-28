/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.ext.git.client.add;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.che.api.git.shared.Status;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.PromiseError;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.git.GitServiceClient;
import org.eclipse.che.ide.api.machine.DevMachine;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.resources.Container;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.ext.git.client.GitLocalizationConstant;
import org.eclipse.che.ide.ext.git.client.outputconsole.GitOutputConsole;
import org.eclipse.che.ide.ext.git.client.outputconsole.GitOutputConsoleFactory;
import org.eclipse.che.ide.extension.machine.client.processes.panel.ProcessesPanelPresenter;
import org.eclipse.che.ide.resource.Path;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.FLOAT_MODE;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.FAIL;

/**
 * Presenter for add changes to Git index.
 *
 * @author Ann Zhuleva
 * @author Vlad Zhukovskyi
 */
@Singleton
public class AddToIndexPresenter implements AddToIndexView.ActionDelegate {
    private static final String ADD_TO_INDEX_COMMAND_NAME = "Git add to index";

    private final AddToIndexView          view;
    private final GitServiceClient        service;
    private final GitLocalizationConstant constant;
    private final AppContext              appContext;
    private final NotificationManager     notificationManager;
    private final GitOutputConsoleFactory gitOutputConsoleFactory;
    private final ProcessesPanelPresenter consolesPanelPresenter;

    @Inject
    public AddToIndexPresenter(AddToIndexView view,
                               AppContext appContext,
                               GitLocalizationConstant constant,
                               GitOutputConsoleFactory gitOutputConsoleFactory,
                               ProcessesPanelPresenter processesPanelPresenter,
                               GitServiceClient service,
                               NotificationManager notificationManager) {
        this.view = view;
        this.view.setDelegate(this);
        this.service = service;
        this.constant = constant;
        this.appContext = appContext;
        this.notificationManager = notificationManager;
        this.gitOutputConsoleFactory = gitOutputConsoleFactory;
        this.consolesPanelPresenter = processesPanelPresenter;
    }

    public void showDialog() {
        final Resource[] resources = appContext.getResources();
        checkState(resources != null && resources.length > 0);
        final GitOutputConsole console = gitOutputConsoleFactory.create(ADD_TO_INDEX_COMMAND_NAME);
        final Project project = appContext.getRootProject();
        final Path projectLocation = project.getLocation();
        final DevMachine devMachine = appContext.getDevMachine();
        service.getStatus(devMachine, projectLocation)
               .then(new Operation<Status>() {
                   @Override
                   public void apply(Status status) throws OperationException {
                       List<String> untracked = new ArrayList<String>();
                       untracked.addAll(status.getModified());
                       untracked.addAll(status.getUntracked());

                       for (String untrackedItem : untracked) {
                           for (Resource selectedItem : resources) {
                               String selectedItemPath = selectedItem.getLocation()
                                                                     .removeFirstSegments(projectLocation.segmentCount())
                                                                     .toString();
                               if (untrackedItem.startsWith(selectedItemPath)) {
                                   updateViewAndShowDialog(resources.length, selectedItem.getName());
                                   return;
                               }
                           }
                       }
                       String message = resources.length > 1 ? constant.nothingAddToIndexMultiSelect() : constant.nothingAddToIndex();
                       console.print(message);
                       consolesPanelPresenter.addCommandOutput(devMachine.getId(), console);
                       notificationManager.notify(message);
                   }
               })
               .catchError(new Operation<PromiseError>() {
                   @Override
                   public void apply(PromiseError error) throws OperationException {
                       console.printError(constant.statusFailed());
                       consolesPanelPresenter.addCommandOutput(devMachine.getId(), console);
                       notificationManager.notify(constant.statusFailed(), FAIL, FLOAT_MODE);
                   }
               });
    }

    /** {@inheritDoc} */
    @Override
    public void onAddClicked() {
        final GitOutputConsole console = gitOutputConsoleFactory.create(ADD_TO_INDEX_COMMAND_NAME);
        final Resource[] resources = appContext.getResources();

        checkState(resources != null && resources.length > 0);

        final Path[] paths = new Path[resources.length];

        for (int i = 0; i < resources.length; i++) {
            checkState(appContext.getRootProject().getLocation().isPrefixOf(resources[i].getLocation()));

            final Path tmpPath = resources[i].getLocation().removeFirstSegments(appContext.getRootProject().getLocation().segmentCount());

            paths[i] = tmpPath.segmentCount() == 0 ? Path.EMPTY : tmpPath;
        }

        service.add(appContext.getDevMachine(), appContext.getRootProject().getLocation(), view.isUpdated(), paths)
               .then(new Operation<Void>() {
                   @Override
                   public void apply(Void arg) throws OperationException {
                       console.print(constant.addSuccess());
                       consolesPanelPresenter.addCommandOutput(appContext.getDevMachine().getId(), console);
                       notificationManager.notify(constant.addSuccess());
                       view.close();
                   }
               })
               .catchError(new Operation<PromiseError>() {
                   @Override
                   public void apply(PromiseError arg) throws OperationException {
                       String errorMessage = constant.addFailed();
                       console.printError(errorMessage);
                       consolesPanelPresenter.addCommandOutput(appContext.getDevMachine().getId(), console);
                       notificationManager.notify(constant.addFailed(), FAIL, FLOAT_MODE);
                       view.close();
                   }
               });
    }

    /** {@inheritDoc} */
    @Override
    public void onCancelClicked() {
        view.close();
    }

    private void updateViewAndShowDialog(int length, String name) {
        if (length == 1) {
            if (appContext.getResource() instanceof Container) {
                view.setMessage(constant.addToIndexFolder(name), null);
            } else {
                view.setMessage(constant.addToIndexFile(name), null);
            }
        } else if (length > 1) {
            view.setMessage(constant.addToIndexMultiple(), null);
        }
        view.setUpdated(false);
        view.showDialog();
    }
}
