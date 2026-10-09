import { z } from 'zod';
import { EntityIdSchema, IsoTimestampSchema } from './common';

/**
 * Permitted domain roles for DOOMEDAMS offline examination system
 */
export const UserRoleSchema = z.enum([
  'STUDENT',
  'INVIGILATOR',
  'INCIDENT_REVIEWER',
  'EXAMINATION_ADMIN',
  'CHIEF_EXAMINATION_OFFICER',
  'AUDITOR',
  'SYSTEM_ADMIN'
]);

export type UserRole = z.infer<typeof UserRoleSchema>;

/**
 * Account lifecycle status
 */
export const AccountStatusSchema = z.enum([
  'ACTIVE',
  'SUSPENDED',
  'DEACTIVATED'
]);

export type AccountStatus = z.infer<typeof AccountStatusSchema>;

/**
 * Public User Profile Record (Contains no authentication secrets)
 */
export const UserProfileSchema = z.object({
  userId: EntityIdSchema,
  displayName: z.string().trim().min(1, 'Display name cannot be empty').max(100),
  assignedRole: UserRoleSchema,
  accountStatus: AccountStatusSchema,
  instituteScope: EntityIdSchema,
  examinationScope: EntityIdSchema.optional(),
  createdAt: IsoTimestampSchema
});

export type UserProfile = z.infer<typeof UserProfileSchema>;

/**
 * Supported cryptographic credential types (strictly hashed/public keys only)
 */
export const CredentialTypeSchema = z.enum([
  'ED25519_PUBLIC_KEY',
  'ARGON2ID_HASH',
  'OFFLINE_SESSION_TOKEN_HASH'
]);

export type CredentialType = z.infer<typeof CredentialTypeSchema>;

/**
 * Isolated User Credential Record.
 * Explicitly separated from public profile to guarantee zero credential leakage.
 * Plaintext passwords, private keys, and hardcoded secrets are strictly prohibited.
 */
export const UserCredentialSchema = z.object({
  userId: EntityIdSchema,
  credentialType: CredentialTypeSchema,
  credentialDigest: z.string().min(16, 'Credential digest must be a valid cryptographic representation'),
  salt: z.string().min(8).optional(),
  updatedAt: IsoTimestampSchema
});

export type UserCredential = z.infer<typeof UserCredentialSchema>;
