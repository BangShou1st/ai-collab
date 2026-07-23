package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.project.infrastructure.entity.ProjectInvitationEntity;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectInvitationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationInsertService {
    private final ProjectInvitationRepository invitations;

    public InvitationInsertService(ProjectInvitationRepository invitations) {
        this.invitations = invitations;
    }

    /**
     * NESTED 传播为单次唯一冲突建立保存点；冲突只回滚本次 INSERT，外层事务可以安全生成新随机码重试。
     */
    @Transactional(propagation = Propagation.NESTED)
    public void insertWithSavepoint(ProjectInvitationEntity invitation) {
        invitations.create(invitation);
    }
}
