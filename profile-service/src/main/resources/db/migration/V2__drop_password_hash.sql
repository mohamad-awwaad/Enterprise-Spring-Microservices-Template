-- pending_registrations used to have a password_hash column back when self-registration
-- collected a password directly. The self-registration flow now sets the password via
-- Keycloak's "Set Password" email after confirmation (see RegistrationService), and the
-- entity no longer has this field. ddl-auto=update never dropped it from existing dev
-- databases, so remove it explicitly here.
ALTER TABLE pending_registrations DROP COLUMN IF EXISTS password_hash;
