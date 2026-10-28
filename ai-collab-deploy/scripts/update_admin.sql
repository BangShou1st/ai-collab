UPDATE app_user 
SET password_hash = (SELECT password_hash FROM app_user WHERE username LIKE 'user_%' LIMIT 1),
    system_admin = true 
WHERE username = 'staging_admin';
