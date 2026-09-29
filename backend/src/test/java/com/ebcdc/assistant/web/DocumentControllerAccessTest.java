package com.ebcdc.assistant.web;
import com.ebcdc.assistant.domain.*;
import com.ebcdc.assistant.repo.*;
import com.ebcdc.assistant.rag.RagVectorStore;
import com.ebcdc.assistant.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentControllerAccessTest {
    @Test void providerWithoutContractSeesNoDocuments() {
        var documents=mock(DocumentRepository.class);
        var controller=new DocumentController(documents,mock(DocumentVersionRepository.class),mock(ProviderRepository.class),
            mock(WorkflowTransitionRepository.class),mock(ClarificationRequestRepository.class),mock(RagJobRepository.class),
            mock(RagVectorStore.class),mock(WorkflowService.class));
        var user=new AppUser("orphan@test.fr","Orphan",Role.PRESTATAIRE,"unused",null);
        assertThat(controller.listDocuments(new UsernamePasswordAuthenticationToken(user,null))).isEmpty();
        verifyNoInteractions(documents);
    }
}
