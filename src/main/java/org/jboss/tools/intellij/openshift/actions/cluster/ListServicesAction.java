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
package org.jboss.tools.intellij.openshift.actions.cluster;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.redhat.devtools.intellij.common.utils.UIHelper;
import org.jboss.tools.intellij.openshift.utils.odo.Odo;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static org.jboss.tools.intellij.openshift.telemetry.TelemetryService.TelemetryResult;

public class ListServicesAction extends LoggedInClusterAction {

    @Override
    protected String getTelemetryActionName() { return "list services"; }

     @Override
    public void actionPerformed(AnActionEvent anActionEvent, Object selected, Odo odo) {
        CompletableFuture.runAsync(() -> {
            try {
                odo.listServices();
                sendTelemetryResults(TelemetryResult.SUCCESS);
            } catch (IOException e) {
                sendTelemetryError(e);
                UIHelper.executeInUI(() -> Messages.showErrorDialog("Error: " + e.getLocalizedMessage(), "List services"));
            }
        });
    }
}
