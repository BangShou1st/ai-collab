package com.shitulelv.aicollab.agent.application;

import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.common.exception.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Small deterministic business evaluation. Provider quality is evaluated separately through production adapters.
 *
 * <p>P3 起 {@code KnowledgeConversationContext} 随多轮消歧迁 Python（契约 §2.1 行 2），
 * 其行为断言由 worker 金标准 {@code p3_understanding_golden.v1.jsonl} 承接，此处不再保留。</p>
 */
class AgentBusinessEvaluationTest {
    @Test void olderRelevantMemoryOutranksNewUnrelatedMemoriesAndCurrentAccessIsRequired() {
        var repository=mock(AgentMemoryRepository.class);var access=mock(ProjectAccessGuard.class);
        var service=new AgentMemoryService(repository,access,mock(AgentMemorySourceVerifier.class));UUID project=UUID.randomUUID(),user=UUID.randomUUID();
        List<AgentMemoryView> rows=new ArrayList<>();
        for(int i=0;i<30;i++) rows.add(memory(project,user,"DECISION","其他项目话题"+i,"营销内容",i));
        var relevant=memory(project,user,"CONSTRAINT","会议纪要系统","日期保持不变，任务最多四项",-30);
        rows.add(relevant);rows.add(memory(project,user,"PREFERENCE","展示偏好","中文表格",0));
        when(repository.list(project,true,100,true)).thenReturn(rows);
        assertThat(service.context(project,user,"为会议纪要系统生成规划")).hasSize(2).first().isEqualTo(relevant);
        assertThat(service.context(project,user,"现在换成移动支付项目")).hasSize(1).allMatch(m->m.type().equals("PREFERENCE"));
        doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN)).when(access).requireMember(project,user);
        assertThatThrownBy(()->service.context(project,user,"会议纪要")).isInstanceOf(BusinessException.class);
    }
    private static AgentMemoryView memory(UUID project,UUID user,String type,String title,String content,int minutes) {
        return new AgentMemoryView(UUID.randomUUID(),project,type,title,content,"USER",null,"ACTIVE",user,user,1,OffsetDateTime.now().plusMinutes(minutes),OffsetDateTime.now().plusMinutes(minutes),"UNVERIFIED",null,null,null);
    }
}
