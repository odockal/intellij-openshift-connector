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
package org.jboss.tools.intellij.openshift.actions.url;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.redhat.devtools.intellij.common.utils.UIHelper;
import org.jboss.tools.intellij.openshift.Constants;
import org.jboss.tools.intellij.openshift.actions.OdoAction;
import org.jboss.tools.intellij.openshift.tree.application.ApplicationNode;
import org.jboss.tools.intellij.openshift.tree.application.ApplicationsTreeStructure;
import org.jboss.tools.intellij.openshift.tree.application.ComponentNode;
import org.jboss.tools.intellij.openshift.tree.application.NamespaceNode;
import org.jboss.tools.intellij.openshift.ui.url.CreateURLDialog;
import org.jboss.tools.intellij.openshift.utils.odo.Component;
import org.jboss.tools.intellij.openshift.utils.odo.Odo;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static org.jboss.tools.intellij.openshift.telemetry.TelemetryService.TelemetryResult;

public class CreateURLAction extends OdoAction {
    public CreateURLAction() {
        super(ComponentNode.class);
    }

    @Override
    protected String getTelemetryActionName() {
        return "create URL";
    }

    @Override
    public void actionPerformed(AnActionEvent anActionEvent, Object selected, Odo odo) {
        ComponentNode componentNode = (ComponentNode) selected;
        Component component = componentNode.getComponent();
        ApplicationNode applicationNode = componentNode.getParent();
        NamespaceNode namespaceNode = applicationNode.getParent();
        CompletableFuture.runAsync(() -> {
            try {
                if (createURL(odo, namespaceNode.getName(), applicationNode.getName(), component)) {
                    ((ApplicationsTreeStructure) getTree(anActionEvent).getClientProperty(Constants.STRUCTURE_PROPERTY)).fireModified(componentNode);
                    sendTelemetryResults(TelemetryResult.SUCCESS);
                } else {
                    sendTelemetryResults(TelemetryResult.ABORTED);
                }
            } catch (IOException e) {
                sendTelemetryError(e);
                UIHelper.executeInUI(() -> Messages.showErrorDialog("Error: " + e.getLocalizedMessage(), "Create URL"));
            }
        });
    }

    public static boolean createURL(Odo odo, String project, String application, Component component) throws IOException {
        CreateURLDialog dialog = UIHelper.executeInUI(() -> {
            CreateURLDialog dialog1 = new CreateURLDialog(!odo.isOpenShift(), odo.getMasterUrl().getHost());
            dialog1.show();
            return dialog1;
        });
        if (dialog.isOK()) {
            Integer port = dialog.getPort();
            String urlName = dialog.getName();
            boolean secure = dialog.isSecure();
            String host = dialog.getHost();
            if (port != null) {
                odo.createURL(project, application, component.getPath(), component.getName(), urlName, port, secure, host);
                return true;
            }
        }
        return false;
    }
}
