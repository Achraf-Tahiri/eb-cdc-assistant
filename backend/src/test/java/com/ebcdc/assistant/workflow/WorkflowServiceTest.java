package com.ebcdc.assistant.workflow;

import com.ebcdc.assistant.domain.*;
import com.ebcdc.assistant.repo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowServiceTest {
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final WorkflowTransitionRepository audit = mock(WorkflowTransitionRepository.class);
    private final ClarificationRequestRepository questions = mock(ClarificationRequestRepository.class);
    private final WorkflowService service = new WorkflowService(documents, audit, questions);
    private final Provider alpha = new Provider("Alpha", "A", 5);
    private final AppUser metier = user(Role.METIER, null);
    private AppUser user(Role role, Provider provider) {
        return new AppUser(role+"@test.fr", "Test", role, "unused", provider);
    }
    private DocumentEntity document(WorkflowState state, Provider provider) {
        var d = new DocumentEntity("Test", DocType.EB, "TEST", provider, metier);
        d.setCurrentState(state);
        when(documents.findByIdForUpdate(d.getId())).thenReturn(Optional.of(d));
        return d;
    }
    static Stream<Object[]> combinations() {
        return Arrays.stream(WorkflowState.values()).flatMap(s -> Arrays.stream(WorkflowAction.values())
            .flatMap(a -> Arrays.stream(Role.values()).map(r -> new Object[]{s,a,r})));
    }
    // Expected business rules, independent of the implementation's transition table.
    private WorkflowState expected(WorkflowState s, WorkflowAction a, Role r) {
        return switch (a) {
            case SUBMIT -> r == Role.METIER && (s == WorkflowState.BROUILLON || s == WorkflowState.CORRECTION_METIER) ? WorkflowState.SOUMIS_PROJET : null;
            case VALIDATE -> r == Role.PROJET && s == WorkflowState.SOUMIS_PROJET ? WorkflowState.VALIDE_PROJET : null;
            case REQUEST_CORRECTION -> r == Role.PROJET && s == WorkflowState.SOUMIS_PROJET ? WorkflowState.CORRECTION_METIER : null;
            case SEND -> r == Role.PROJET && s == WorkflowState.VALIDE_PROJET ? WorkflowState.TRANSMIS_PRESTATAIRE : null;
            case REQUEST_CLARIFICATION -> r == Role.PRESTATAIRE && s == WorkflowState.TRANSMIS_PRESTATAIRE ? WorkflowState.CLARIFICATION_DEMANDEE : null;
            case RESOLVE -> r != Role.PRESTATAIRE && s == WorkflowState.CLARIFICATION_DEMANDEE ? WorkflowState.TRANSMIS_PRESTATAIRE : null;
            case ACCEPT -> r == Role.PRESTATAIRE && s == WorkflowState.TRANSMIS_PRESTATAIRE ? WorkflowState.IMPLEMENTATION_ACCEPTEE : null;
            case CLOSE -> r == Role.PROJET && s == WorkflowState.IMPLEMENTATION_ACCEPTEE ? WorkflowState.CLOTURE : null;
            default -> null;
        };
    }
    @ParameterizedTest(name="{0} / {1} / {2}") @MethodSource("combinations")
    void enforcesEveryStateActionRoleCombination(WorkflowState state, WorkflowAction action, Role role) {
        var d = document(state, alpha);
        var actor = user(role, role == Role.PRESTATAIRE ? alpha : null);
        when(questions.countByDocumentIdAndResolvedAtIsNull(d.getId()))
            .thenReturn(action == WorkflowAction.REQUEST_CLARIFICATION ? 1L : 0L);
        var target = expected(state, action, role);
        if (target == null) {
            assertThatThrownBy(() -> service.applyAction(d.getId(), action, actor, "test"))
                .isInstanceOfAny(InvalidTransitionException.class, ForbiddenActionException.class);
            assertThat(d.getCurrentState()).isEqualTo(state);
            verifyNoInteractions(audit);
            assertThat(service.availableActions(state, role)).doesNotContain(action);
        } else {
            assertThat(service.applyAction(d.getId(), action, actor, "test")).isEqualTo(target);
            assertThat(d.getCurrentState()).isEqualTo(target);
            verify(audit).save(argThat(t -> t.getFromState() == state && t.getToState() == target && t.getAction() == action));
            assertThat(service.availableActions(state, role)).contains(action);
        }
    }
    @Test void rejectsOtherProviderAndUnassignedProvider() {
        var d = document(WorkflowState.TRANSMIS_PRESTATAIRE, alpha);
        for (Provider p : Arrays.asList(new Provider("Beta", "B", 5), null)) {
            assertThatThrownBy(() -> service.applyAction(d.getId(), WorkflowAction.ACCEPT, user(Role.PRESTATAIRE,p),null))
                .isInstanceOf(ForbiddenActionException.class);
        }
        verifyNoInteractions(audit);
    }
    @Test void rejectsSendWithoutProvider() {
        var d=document(WorkflowState.VALIDE_PROJET,null);
        assertThatThrownBy(() -> service.applyAction(d.getId(),WorkflowAction.SEND,user(Role.PROJET,null),null))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
    }
    @Test void rejectsUnansweredQuestionsAndEmptyCycles() {
        var d=document(WorkflowState.CLARIFICATION_DEMANDEE,alpha);
        when(questions.countByDocumentIdAndResolvedAtIsNull(d.getId())).thenReturn(1L);
        assertThatThrownBy(() -> service.applyAction(d.getId(),WorkflowAction.RESOLVE,metier,null)).isInstanceOf(IllegalStateException.class);
        d.setCurrentState(WorkflowState.TRANSMIS_PRESTATAIRE);
        assertThatThrownBy(() -> service.applyAction(d.getId(),WorkflowAction.ACCEPT,user(Role.PRESTATAIRE,alpha),null)).isInstanceOf(IllegalStateException.class);
        when(questions.countByDocumentIdAndResolvedAtIsNull(d.getId())).thenReturn(0L);
        assertThatThrownBy(() -> service.applyAction(d.getId(),WorkflowAction.REQUEST_CLARIFICATION,user(Role.PRESTATAIRE,alpha),null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(audit);
    }
}
