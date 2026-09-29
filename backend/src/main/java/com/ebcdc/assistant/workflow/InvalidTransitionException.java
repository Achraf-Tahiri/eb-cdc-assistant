package com.ebcdc.assistant.workflow;

import com.ebcdc.assistant.domain.WorkflowAction;
import com.ebcdc.assistant.domain.WorkflowState;

public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(WorkflowState state, WorkflowAction action) {
        super("L'action " + action + " n'est pas autorisée depuis l'état " + state + ".");
    }
}
