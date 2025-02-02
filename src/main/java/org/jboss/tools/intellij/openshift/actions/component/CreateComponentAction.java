/*******************************************************************************
 * Copyright (c) 2019 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.intellij.openshift.actions.component;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.redhat.devtools.intellij.common.utils.UIHelper;
import org.apache.commons.lang3.StringUtils;
import org.jboss.tools.intellij.openshift.Constants;
import org.jboss.tools.intellij.openshift.actions.OdoAction;
import org.jboss.tools.intellij.openshift.telemetry.TelemetryService;
import org.jboss.tools.intellij.openshift.tree.application.ApplicationNode;
import org.jboss.tools.intellij.openshift.tree.application.ApplicationsRootNode;
import org.jboss.tools.intellij.openshift.tree.application.ApplicationsTreeStructure;
import org.jboss.tools.intellij.openshift.tree.application.NamespaceNode;
import org.jboss.tools.intellij.openshift.tree.application.ParentableNode;
import org.jboss.tools.intellij.openshift.ui.component.CreateComponentDialog;
import org.jboss.tools.intellij.openshift.ui.component.CreateComponentModel;
import org.jboss.tools.intellij.openshift.utils.odo.DevfileComponentType;
import org.jboss.tools.intellij.openshift.utils.odo.Odo;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static org.jboss.tools.intellij.openshift.telemetry.TelemetryService.TelemetryResult;

public class CreateComponentAction extends OdoAction {
  public CreateComponentAction() {
    super(ApplicationNode.class, NamespaceNode.class);
  }

  protected CreateComponentAction(Class... clazz) {
    super(clazz);
  }

  public static void execute(ParentableNode<? extends Object> parentNode) {
    CreateComponentAction action = (CreateComponentAction) ActionManager.getInstance().getAction(CreateComponentAction.class.getName());
    if (parentNode instanceof NamespaceNode) {
      NamespaceNode namespaceNode = (NamespaceNode) parentNode;
      action.doActionPerformed(namespaceNode, namespaceNode.getRoot().getOdo(), Optional.empty(), namespaceNode.getName(),
              namespaceNode.getRoot(), namespaceNode.getRoot().getProject());
    } else if (parentNode instanceof ApplicationNode) {
      ApplicationNode applicationNode = (ApplicationNode) parentNode;
      action.doActionPerformed(applicationNode, applicationNode.getRoot().getOdo(), Optional.of(applicationNode.getName()),
              applicationNode.getNamespace(), applicationNode.getRoot(), applicationNode.getRoot().getProject());
    }
  }

  @Override
  protected String getTelemetryActionName() { return "create component"; }

  @Override
  public void actionPerformed(AnActionEvent anActionEvent, Object selected, Odo odo) {
    final Optional<String> application;
    String projectName;
    if (selected instanceof ApplicationNode) {
      application = Optional.of(((ApplicationNode) selected).getName());
      projectName =  ((ApplicationNode)selected).getParent().getName();
    } else {
      application = Optional.empty();
      projectName = ((NamespaceNode)selected).getName();
    }
    ApplicationsRootNode rootNode = ((ParentableNode<Object>)selected).getRoot();
    Project project = rootNode.getProject();
    doActionPerformed((ParentableNode<Object>) selected, odo, application, projectName, rootNode, project);
  }

  private void doActionPerformed(ParentableNode<? extends Object> selected, Odo odo, Optional<String> application, String projectName, ApplicationsRootNode rootNode, Project project) {
    CompletableFuture.runAsync(() -> {
      try {
        CreateComponentModel model = getModel(project, application, odo, p -> rootNode.getComponents().containsKey(p));
        process(selected, odo, projectName, application, rootNode, model, rootNode.getStructure());
      } catch (IOException e) {
        sendTelemetryError(e);
        UIHelper.executeInUI(() -> Messages.showErrorDialog("Error: " + e.getLocalizedMessage(), "Create component"));
      }
    });
  }

  protected void process(ParentableNode<? extends Object> selected, Odo odo, String projectName, Optional<String> application,
                         ApplicationsRootNode rootNode, CreateComponentModel model, ApplicationsTreeStructure structure) throws IOException {
    boolean doit = UIHelper.executeInUI(() -> showDialog(model));
    if (doit) {
      createComponent(odo, projectName, application.orElse(model.getApplication()), model);
      rootNode.addContext(model.getContext());
      structure.fireModified(selected);
      sendTelemetryResults(TelemetryResult.SUCCESS);
    } else {
      sendTelemetryResults(TelemetryResult.ABORTED);
    }
  }

  private void createComponent(Odo odo, String project, String application, CreateComponentModel model) throws IOException{
    computeTelemetry(model);
    odo.createComponent(project, application, model.getSelectedComponentType().getName(),
              model.getSelectedComponentType() instanceof DevfileComponentType?((DevfileComponentType)model.getSelectedComponentType()).getDevfileRegistry().getName():null,
              model.getName(),
              model.getContext(),
              model.isProjectHasDevfile()? Constants.DEVFILE_NAME:null,
              model.getSelectedComponentStarter(),
              model.isPushAfterCreate());
  }

  protected boolean showDialog(CreateComponentModel model) {
    CreateComponentDialog dialog = new CreateComponentDialog(model.getProject(), true, model);
    dialog.show();
    return dialog.isOK();
  }

  @NotNull
  protected CreateComponentModel getModel(Project project, Optional<String> application, Odo odo, Predicate<String> componentChecker) throws IOException{
    CreateComponentModel model =  new CreateComponentModel("Create component", project, odo, odo.getComponentTypes());
    application.ifPresent(v -> {
      model.setApplication(v);
      model.setApplicationReadOnly(true);
    });
    model.setComponentPredicate(componentChecker);
    return model;
  }

    private void computeTelemetry(CreateComponentModel model) {
        telemetrySender
                .addProperty(TelemetryService.PROP_COMPONENT_HAS_LOCAL_DEVFILE, String.valueOf(model.isProjectHasDevfile()));
        telemetrySender
                .addProperty(TelemetryService.PROP_COMPONENT_PUSH_AFTER_CREATE, String.valueOf(model.isPushAfterCreate()));
        if (StringUtils.isNotBlank(model.getSelectedComponentType().getName())) {
            telemetrySender.addProperty(TelemetryService.PROP_COMPONENT_KIND, "devfile:" + model.getSelectedComponentType().getName());
        }
        if (StringUtils.isNotBlank(model.getSelectedComponentStarter())) {
            telemetrySender.addProperty(TelemetryService.PROP_COMPONENT_SELECTED_STARTER, model.getSelectedComponentStarter());
        }
    }

}
