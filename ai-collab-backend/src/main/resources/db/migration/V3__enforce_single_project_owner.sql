CREATE UNIQUE INDEX uq_project_member_single_owner
    ON project_member(project_id)
    WHERE role = 'OWNER';
