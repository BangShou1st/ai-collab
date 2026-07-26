package com.shitulelv.aicollab.project.application.service;

import com.shitulelv.aicollab.auth.dto.CurrentUserResponse;
import com.shitulelv.aicollab.auth.dto.LoginResponse;
import com.shitulelv.aicollab.auth.model.AuthenticationResult;
import com.shitulelv.aicollab.auth.model.IssuedRefreshToken;
import com.shitulelv.aicollab.auth.service.AccessTokenService;
import com.shitulelv.aicollab.auth.service.RefreshTokenService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.api.dto.AcceptInvitationRequest;
import com.shitulelv.aicollab.project.api.dto.CreateInvitationRequest;
import com.shitulelv.aicollab.project.application.view.InvitationAcceptanceView;
import com.shitulelv.aicollab.project.application.view.InvitationPreview;
import com.shitulelv.aicollab.project.application.view.InvitationView;
import com.shitulelv.aicollab.project.domain.model.InvitationStatus;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.entity.ProjectInvitationEntity;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectInvitationRepository;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectRepository;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class InvitationApplicationService {
    private static final Logger log = LoggerFactory.getLogger(InvitationApplicationService.class);
    private static final Pattern CODE_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int MAX_CODE_ATTEMPTS = 3;

    private final ProjectAccessGuard accessGuard;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectInvitationRepository invitations;
    private final InvitationInsertService invitationInsertService;
    private final InvitationCodeService codes;
    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final Clock clock;

    public InvitationApplicationService(
            ProjectAccessGuard accessGuard,
            ProjectRepository projects,
            ProjectMemberRepository members,
            ProjectInvitationRepository invitations,
            InvitationInsertService invitationInsertService,
            InvitationCodeService codes,
            UserService users,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens,
            AuditService audit,
            Clock clock) {
        this.accessGuard = accessGuard;
        this.projects = projects;
        this.members = members;
        this.invitations = invitations;
        this.invitationInsertService = invitationInsertService;
        this.codes = codes;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public InvitationView create(UUID projectId, CreateInvitationRequest request, UUID operatorId) {
        accessGuard.requireAdmin(projectId, operatorId);
        if (request.role() == ProjectRole.OWNER) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邀请角色只能是 ADMIN 或 MEMBER");
        }
        OffsetDateTime expiresAt = now().plusHours(request.expiresInHours());
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String rawCode = codes.generate();
            ProjectInvitationEntity entity = new ProjectInvitationEntity();
            entity.setId(UUID.randomUUID());
            entity.setProjectId(projectId);
            entity.setCodeHash(codes.hash(rawCode));
            entity.setInvitedEmail(normalizeEmail(request.invitedEmail()));
            entity.setRole(request.role());
            entity.setStatus(InvitationStatus.PENDING);
            entity.setExpiresAt(expiresAt);
            entity.setCreatedBy(operatorId);
            try {
                invitationInsertService.insertWithSavepoint(entity);
                audit.write(projectId, operatorId, "PROJECT_INVITATION_CREATED", "PROJECT_INVITATION", entity.getId());
                return new InvitationView(entity.getId(), rawCode, projectId, entity.getRole(), expiresAt);
            } catch (DataIntegrityViolationException exception) {
                if (attempt == MAX_CODE_ATTEMPTS - 1) {
                    throw exception;
                }
            }
        }
        throw new IllegalStateException("邀请码生成失败");
    }

    @Transactional(readOnly = true)
    public InvitationPreview preview(String rawCode) {
        ProjectInvitationEntity invitation = findValid(rawCode, false);
        String projectName = projects.findNameById(invitation.getProjectId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_INVALID));
        return new InvitationPreview(
                invitation.getProjectId(), projectName, invitation.getRole(),
                invitation.getInvitedEmail(), invitation.getExpiresAt());
    }

    @Transactional
    public AuthenticationResult accept(String rawCode, AcceptInvitationRequest request) {
        ProjectInvitationEntity invitation = findValid(rawCode, true);
        String requestedEmail = normalizeEmail(request.email());
        if (invitation.getInvitedEmail() != null
                && requestedEmail != null
                && !invitation.getInvitedEmail().equalsIgnoreCase(requestedEmail)) {
            throw new BusinessException(ErrorCode.INVITATION_INVALID);
        }
        String effectiveEmail = requestedEmail == null ? invitation.getInvitedEmail() : requestedEmail;
        if (users.findByUsername(request.username()).isPresent()) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (effectiveEmail != null && users.findByEmail(effectiveEmail).isPresent()) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setEmail(effectiveEmail);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        try {
            users.create(user);
        } catch (DataIntegrityViolationException exception) {
            throw userConflict(exception);
        }
        try {
            members.create(invitation.getProjectId(), user.getId(), invitation.getRole(), invitation.getCreatedBy());
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        OffsetDateTime acceptedAt = now();
        if (!invitations.markAccepted(invitation.getId(), user.getId(), acceptedAt)) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_USED);
        }
        audit.write(
                invitation.getProjectId(), user.getId(),
                "PROJECT_INVITATION_ACCEPTED", "PROJECT_INVITATION", invitation.getId());
        AccessTokenService.IssuedToken accessToken = accessTokens.createAccessToken(user);
        IssuedRefreshToken refreshToken = refreshTokens.createSession(user);
        return new AuthenticationResult(
                new LoginResponse(
                        accessToken.value(), "Bearer", accessToken.expiresInSeconds(), CurrentUserResponse.from(user)),
                refreshToken);
    }

    @Transactional
    public InvitationAcceptanceView acceptCurrentUser(String rawCode, UUID currentUserId) {
        validateCode(rawCode);
        ProjectInvitationEntity invitation = invitations.findByHashForUpdate(codes.hash(rawCode))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_INVALID));
        UserEntity user = users.findById(currentUserId)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED));
        String projectName = projects.findNameById(invitation.getProjectId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_INVALID));

        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            if (!currentUserId.equals(invitation.getAcceptedBy())) {
                throw new BusinessException(ErrorCode.INVITATION_ALREADY_USED);
            }
            ProjectRole role = members.findRole(invitation.getProjectId(), currentUserId)
                    .orElseThrow(() -> invitationConsistencyError(invitation, currentUserId));
            return acceptance(invitation, projectName, role, true);
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVITATION_INVALID);
        }
        if (!invitation.getExpiresAt().isAfter(now())) {
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        if (!emailMatches(invitation.getInvitedEmail(), user.getEmail())) {
            throw new BusinessException(ErrorCode.INVITATION_EMAIL_MISMATCH);
        }

        ProjectRole existingRole = members.findRole(invitation.getProjectId(), currentUserId).orElse(null);
        if (existingRole != null) {
            return acceptance(invitation, projectName, existingRole, true);
        }

        boolean created = members.createIfAbsent(
                invitation.getProjectId(), currentUserId, invitation.getRole(), invitation.getCreatedBy());
        if (!created) {
            ProjectRole concurrentRole = members.findRole(invitation.getProjectId(), currentUserId)
                    .orElseThrow(() -> invitationConsistencyError(invitation, currentUserId));
            return acceptance(invitation, projectName, concurrentRole, true);
        }

        if (!invitations.markAccepted(invitation.getId(), currentUserId, now())) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_USED);
        }
        audit.write(
                invitation.getProjectId(), currentUserId,
                "PROJECT_INVITATION_ACCEPTED", "PROJECT_INVITATION", invitation.getId());
        ProjectRole actualRole = members.findRole(invitation.getProjectId(), currentUserId)
                .orElseThrow(() -> invitationConsistencyError(invitation, currentUserId));
        return acceptance(invitation, projectName, actualRole, false);
    }

    private ProjectInvitationEntity findValid(String rawCode, boolean lock) {
        validateCode(rawCode);
        ProjectInvitationEntity invitation = (lock
                ? invitations.findByHashForUpdate(codes.hash(rawCode))
                : invitations.findByHash(codes.hash(rawCode)))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_INVALID));
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_USED);
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVITATION_INVALID);
        }
        if (!invitation.getExpiresAt().isAfter(now())) {
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        return invitation;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean emailMatches(String invitedEmail, String userEmail) {
        if (invitedEmail == null) {
            return true;
        }
        String normalizedUserEmail = normalizeEmail(userEmail);
        return normalizedUserEmail != null
                && normalizeEmail(invitedEmail).equals(normalizedUserEmail);
    }

    private static void validateCode(String rawCode) {
        if (rawCode == null || !CODE_FORMAT.matcher(rawCode).matches()) {
            throw new BusinessException(ErrorCode.INVITATION_INVALID);
        }
    }

    private InvitationAcceptanceView acceptance(
            ProjectInvitationEntity invitation,
            String projectName,
            ProjectRole role,
            boolean alreadyMember) {
        return new InvitationAcceptanceView(
                invitation.getProjectId(), projectName, role, alreadyMember);
    }

    private BusinessException invitationConsistencyError(
            ProjectInvitationEntity invitation,
            UUID currentUserId) {
        log.error(
                "邀请接受状态与成员关系不一致: invitationId={}, projectId={}, userId={}",
                invitation.getId(), invitation.getProjectId(), currentUserId);
        return new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    private static BusinessException userConflict(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("app_user_username_key")) {
            return new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (message != null && message.contains("app_user_email_key")) {
            return new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        return new BusinessException(ErrorCode.INTERNAL_ERROR);
    }
}
