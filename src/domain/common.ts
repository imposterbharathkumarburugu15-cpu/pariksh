import { z } from 'zod';

/**
 * Standard identifier regex: Non-empty alphanumeric string with dashes/underscores (e.g. UUID, CUID, or prefixed IDs)
 */
export const EntityIdSchema = z
  .string()
  .min(1, 'Entity ID cannot be empty')
  .max(128, 'Entity ID exceeds maximum length')
  .regex(/^[a-zA-Z0-9_-]+$/, 'Entity ID must be alphanumeric and may include dashes or underscores');

export type EntityId = z.infer<typeof EntityIdSchema>;

/**
 * ISO 8601 UTC timestamp format validation
 */
export const IsoTimestampSchema = z
  .string()
  .datetime({ offset: true, message: 'Must be a valid ISO 8601 timestamp string' });

export type IsoTimestamp = z.infer<typeof IsoTimestampSchema>;

/**
 * Standard entity metadata for auditable domain items
 */
export const EntityMetadataSchema = z.object({
  createdAt: IsoTimestampSchema,
  updatedAt: IsoTimestampSchema,
  version: z.number().int().nonnegative().default(1)
});

export type EntityMetadata = z.infer<typeof EntityMetadataSchema>;

/**
 * Scaled Integer Mark precision strategy:
 * To eliminate floating-point representation and precision drift,
 * marks are stored as scaled integers where 100 units = 1.00 mark (precision: 2 decimal places).
 */
export const ScaledMarkSchema = z
  .number()
  .int('Scaled mark must be an integer representing hundredths of a point');

export type ScaledMark = z.infer<typeof ScaledMarkSchema>;

/**
 * Standard domain validation error structure
 */
export interface DomainValidationError {
  readonly path: string;
  readonly message: string;
  readonly code: string;
}
