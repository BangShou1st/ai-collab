package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.common.exception.*;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeConversationContext;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Small deterministic business evaluation. Provider quality is evaluated separately through production adapters. */
class AgentBusinessEvaluationTest {
    @Test void olderRelevantMemoryOutranksNewUnrelatedMemoriesAndCurrentAccessIsRequired() {
        var repository=mock(AgentMemoryRepository.class);var access=mock(ProjectAccessGuard.class);
        var service=new AgentMemoryService(repository,access);UUID project=UUID.randomUUID(),user=UUID.randomUUID();
        List<AgentMemoryView> rows=new ArrayList<>();
        for(int i=0;i<30;i++) rows.add(memory(project,user,"DECISION","其他项目话题"+i,"营销内容",i));
        var relevant=memory(project,user,"CONSTRAINT","会议纪要系统","日期保持不变，任务最多四项",-30);
        rows.add(relevant);rows.add(memory(project,user,"PREFERENCE","展示偏好","中文表格",0));
        when(repository.list(project,true,100)).thenReturn(rows);
        assertThat(service.context(project,user,"为会议纪要系统生成规划")).hasSize(2).first().isEqualTo(relevant);
        assertThat(service.context(project,user,"现在换成移动支付项目")).hasSize(1).allMatch(m->m.type().equals("PREFERENCE"));
        doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN)).when(access).requireMember(project,user);
        assertThatThrownBy(()->service.context(project,user,"会议纪要")).isInstanceOf(BusinessException.class);
    }
    @Test void explicitTargetSwitchDoesNotCarryPreviousRetrievalQuery() {
        var history=List.of(question("会议纪要的审批要求是什么？"));
        assertThat(KnowledgeConversationContext.from(history,"继续讲它的人工确认流程").retrievalQuery()).startsWith("会议纪要的审批要求是什么？");
        assertThat(KnowledgeConversationContext.from(history,"换个目标，具体讲支付接口").retrievalQuery()).isEqualTo("换个目标，具体讲支付接口");
        assertThat(KnowledgeConversationContext.from(history,"如何实现移动支付接口的可靠重试机制和幂等状态持久化？").retrievalQuery()).doesNotContain("会议纪要");
        assertThat(KnowledgeConversationContext.from(history,"继续").prompt()).contains("历史回答及其引用编号不能作为本轮证据");
    }
    private static KnowledgeMessageEntity question(String text) { var m=new KnowledgeMessageEntity();m.setRole("USER");m.setContent(text);m.setCreatedAt(OffsetDateTime.now());return m; }
    private static AgentMemoryView memory(UUID project,UUID user,String type,String title,String content,int minutes) {
        return new AgentMemoryView(UUID.randomUUID(),project,type,title,content,"USER",null,"ACTIVE",user,user,1,OffsetDateTime.now().plusMinutes(minutes),OffsetDateTime.now().plusMinutes(minutes));
    }
}
