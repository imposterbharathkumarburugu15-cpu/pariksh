import { describe, it, expect } from 'vitest';
import {
  UserProfileSchema,
  UserRoleSchema,
  UserCredentialSchema
} from '../../domain/users';

describe('User and Role Schemas', () => {
  const validTimestamp = '2026-10-09T12:00:00Z';

  it('validates a correct user profile record', () => {
    const validProfile = {
      userId: 'usr_admin_01',
      displayName: 'Alice Examiner',
      assignedRole: 'EXAMINATION_ADMIN',
      accountStatus: 'ACTIVE',
      instituteScope: 'inst_central_college',
      createdAt: validTimestamp
    };

    const parsed = UserProfileSchema.safeParse(validProfile);
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data.assignedRole).toBe('EXAMINATION_ADMIN');
    }
  });

  it('rejects unsupported user roles', () => {
    const invalidProfile = {
      userId: 'usr_02',
      displayName: 'Bob Hacker',
      assignedRole: 'SUPER_ADMIN_ROOT', // Not in domain schema
      accountStatus: 'ACTIVE',
      instituteScope: 'inst_01',
      createdAt: validTimestamp
    };

    const parsed = UserProfileSchema.safeParse(invalidProfile);
    expect(parsed.success).toBe(false);
  });

  it('allows all prescribed role enumerations', () => {
    const roles = [
      'STUDENT',
      'INVIGILATOR',
      'INCIDENT_REVIEWER',
      'EXAMINATION_ADMIN',
      'CHIEF_EXAMINATION_OFFICER',
      'AUDITOR',
      'SYSTEM_ADMIN'
    ];

    for (const role of roles) {
      expect(UserRoleSchema.safeParse(role).success).toBe(true);
    }
  });

  it('rejects empty display name and invalid user IDs', () => {
    const invalidProfile = {
      userId: '',
      displayName: '   ',
      assignedRole: 'STUDENT',
      accountStatus: 'ACTIVE',
      instituteScope: 'inst_01',
      createdAt: validTimestamp
    };

    const parsed = UserProfileSchema.safeParse(invalidProfile);
    expect(parsed.success).toBe(false);
  });

  it('validates isolated credential records and enforces cryptographic digest', () => {
    const credential = {
      userId: 'usr_01',
      credentialType: 'ARGON2ID_HASH',
      credentialDigest: '$argon2id$v=19$m=65536,t=3,p=4$somehashedvalue123456789',
      updatedAt: validTimestamp
    };

    const parsed = UserCredentialSchema.safeParse(credential);
    expect(parsed.success).toBe(true);
  });
});
