package com.ebcdc.assistant.workflow;

import com.ebcdc.assistant.domain.Role;
import com.ebcdc.assistant.domain.WorkflowAction;

public class ForbiddenActionException extends RuntimeException {
    public ForbiddenActionException(Role role, WorkflowAction action) {
        super("Le rôle " + role + " n'est pas autorisé à effectuer l'action " + action + ".");
    }

    public ForbiddenActionException(String message) {
        super(message);
    }
}
