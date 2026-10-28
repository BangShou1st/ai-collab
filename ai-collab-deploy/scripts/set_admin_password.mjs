import { execSync } from 'child_process';
import bcrypt from 'bcryptjs';

const newPassword = 'Password123!';
const hash = bcrypt.hashSync(newPassword, 12);
console.log('Hash generated successfully.');

execSync(`docker exec ai-collab-staging-postgres-1 psql -U ai_collab_staging -d ai_collab_staging -c "UPDATE app_user SET password_hash = '${hash}', system_admin = true WHERE username = 'staging_admin';"`);
console.log('staging_admin password updated to Password123! with system_admin=true.');
