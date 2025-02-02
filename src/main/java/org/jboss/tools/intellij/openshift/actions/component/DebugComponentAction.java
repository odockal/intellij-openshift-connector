/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.intellij.openshift.actions.component;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.redhat.devtools.intellij.common.utils.ExecHelper;
import com.redhat.devtools.intellij.common.utils.UIHelper;
import org.jboss.tools.intellij.openshift.Constants;
import org.jboss.tools.intellij.openshift.actions.OdoAction;
import org.jboss.tools.intellij.openshift.tree.application.ApplicationNode;
import org.jboss.tools.intellij.openshift.tree.application.ComponentNode;
import org.jboss.tools.intellij.openshift.tree.application.NamespaceNode;
import org.jboss.tools.intellij.openshift.utils.odo.Component;
import org.jboss.tools.intellij.openshift.utils.odo.ComponentState;
import org.jboss.tools.intellij.openshift.utils.odo.Odo;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Objects;
import java.util.Optional;

import static org.jboss.tools.intellij.openshift.telemetry.TelemetryService.PROP_DEBUG_COMPONENT_LANGUAGE;
import static org.jboss.tools.intellij.openshift.telemetry.TelemetryService.TelemetryResult;

public abstract class DebugComponentAction extends OdoAction {

    private static final Logger LOG = LoggerFactory.getLogger(DebugComponentAction.class);

    private RunnerAndConfigurationSettings runSettings;

    private ExecutionEnvironment environment;

    protected DebugComponentAction() {
        super(ComponentNode.class);
    }

    @Override
    protected String getTelemetryActionName() { return "debug component"; }

    @Override
    public boolean isVisible(Object selected) {
        boolean visible = super.isVisible(selected);
        if (visible) {
            ComponentNode componentNode = (ComponentNode) selected;
            Component component = componentNode.getComponent();
            return (isPushed(component) && isDebuggable(component.getInfo().getComponentTypeName()));
        }
        return false;
    }

    private boolean isPushed(Component component) {
        return component.getState() == ComponentState.PUSHED;
    }

    @Override
    public void actionPerformed(AnActionEvent anActionEvent, Object selected, Odo odo) {
        ComponentNode componentNode = (ComponentNode) selected;
        Component component = componentNode.getComponent();
        ApplicationNode applicationNode = componentNode.getParent();
        NamespaceNode namespaceNode = applicationNode.getParent();

        Project project = anActionEvent.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            sendTelemetryResults(TelemetryResult.ABORTED);
            return;
        }

        RunManager runManager = RunManager.getInstance(project);
        final Optional<Integer> port = createOrFindPortFromConfiguration(runManager, component);
        port.ifPresent(portNumber -> executeDebug(project, component, odo, applicationNode.getName(), namespaceNode.getName(), portNumber));
    }

    private void executeDebug(Project project, Component component, Odo odo, String applicationName, String projectName, Integer port) {
        telemetrySender.addProperty(PROP_DEBUG_COMPONENT_LANGUAGE, getDebugLanguage().toLowerCase());
        ExecHelper.submit(() -> {
            try {
                // run odo debug if not already running
                if (checkOdoDebugNotRunning(odo, projectName, applicationName, component)) {
                    ProgressManager.getInstance().run(
                            new Task.Modal(project, "Starting odo", true) {
                                public void run(@NotNull ProgressIndicator indicator) {
                                    indicator.setText("Starting debugger session for the component "
                                            + component.getName() + ".");
                                    indicator.setIndeterminate(true);
                                    try {
                                            indicator.setText2("Please wait while component is switching to debug mode...");
                                            odo.pushWithDebug(projectName, applicationName, component.getPath(), component.getName());
                                            indicator.checkCanceled();
                                            indicator.setText2("Component pushed!");
                                        odo.debug(
                                                projectName,
                                                applicationName,
                                                component.getPath(),
                                                component.getName(),
                                                port);
                                        indicator.checkCanceled();
                                        while (!checkOdoDebugRunning(odo, projectName, applicationName, component)) {
                                            Thread.sleep(10L);
                                            indicator.checkCanceled();
                                        }
                                    } catch (IOException | InterruptedException e) {
                                        sendTelemetryError(e);
                                        UIHelper.executeInUI(() -> Messages.showErrorDialog(
                                                "Error: " + e.getLocalizedMessage(), "Odo Debug"));
                                        if (e instanceof InterruptedException)
                                            Thread.currentThread().interrupt();
                                    }
                                }
                            });

                }
            } catch (IOException e) {
                sendTelemetryError(e);
                UIHelper.executeInUI(() -> Messages.showErrorDialog(
                        "Error: " + e.getLocalizedMessage(), "Odo Debug"));
                return;
            }
            // check if local debugger process is already running.
            if (ExecutionManagerImpl.isProcessRunning(getEnvironment().getContentToReuse())) {
                UIHelper.executeInUI(() ->
                        Messages.showMessageDialog(
                                "'" + runSettings.getName() + "' is a single-instance run configuration "
                                        + "and already running.",
                                "Process '" + runSettings.getName() + "' is already running",
                                Messages.getInformationIcon()));
                return;
            }
            // if debugger not running, run the debug config
            ApplicationManager.getApplication().invokeLater(
                    () -> {
                        try {
                            Objects.requireNonNull(ProgramRunner.getRunner(
                                    DefaultDebugExecutor.getDebugExecutorInstance().getId(),
                                    runSettings.getConfiguration())).execute(getEnvironment());
                            sendTelemetryResults(TelemetryResult.SUCCESS);
                        } catch (ExecutionException e) {
                            sendTelemetryError(e);
                            LOG.error(e.getLocalizedMessage(), e);
                        }
                    });
        });

    }

    private boolean checkOdoDebugNotRunning(Odo odo, String projectName, String applicationName, Component component) throws IOException {
        return odo.debugStatus(
                projectName,
                applicationName,
                component.getPath(),
                component.getName()) == Constants.DebugStatus.NOT_RUNNING;

    }

    private boolean checkOdoDebugRunning(Odo odo, String projectName, String applicationName, Component component) throws IOException {
        return odo.debugStatus(
                projectName,
                applicationName,
                component.getPath(),
                component.getName()) == Constants.DebugStatus.RUNNING;

    }

    private Optional<Integer> createOrFindPortFromConfiguration(RunManager runManager, Component component) {
        ConfigurationType configurationType = getConfigurationType();
        String configurationName = component.getName() + " Remote Debug";
        Integer port;

        //lookup if existing config already exist, based on name and type
        runSettings = runManager.findConfigurationByTypeAndName(
                configurationType.getId(), configurationName);
        if (runSettings == null) {
            // no run configuration found, create one and assign an available port
            runSettings = runManager.createConfiguration(
                    configurationName, configurationType.getConfigurationFactories()[0]);
            // also reset environment
            environment = null;
            try {
                // find an available port and use it
                ServerSocket serverSocket = new ServerSocket(0);
                port = serverSocket.getLocalPort();
                serverSocket.close();
                // delegates configuration of run configuration
                initConfiguration(runSettings.getConfiguration(), port);
                runSettings.getConfiguration().setAllowRunningInParallel(false);
                runManager.addConfiguration(runSettings);
            } catch (IOException e) {
                telemetrySender.error(e);
                Messages.showErrorDialog("Error: " + e.getLocalizedMessage(), "Odo Debug");
                return Optional.empty();
            }
        } else {
            port = getPortFromConfiguration(runSettings.getConfiguration());
            if (port == -1) {
                String message = "Error when retrieving local port from configuration.";
                telemetrySender.error(message);
                Messages.showErrorDialog(message, "Odo Debug");
                return Optional.empty();
            }

        }
        runManager.setSelectedConfiguration(runSettings);
        return Optional.of(port);
    }

    private ExecutionEnvironment getEnvironment() {
        if (environment == null) {
            try {
                environment = ExecutionEnvironmentBuilder.create(
                        DefaultDebugExecutor.getDebugExecutorInstance(), runSettings).build();
            } catch (ExecutionException e) {
                telemetrySender.error(e);
                LOG.error(e.getLocalizedMessage(), e);
            }
        }
        return environment;
    }

    protected abstract boolean isDebuggable(@NotNull String componentTypeName);

    protected abstract String getDebugLanguage();

    protected abstract ConfigurationType getConfigurationType();

    protected abstract void initConfiguration(RunConfiguration configuration, Integer port);

    protected abstract int getPortFromConfiguration(RunConfiguration configuration);

}
