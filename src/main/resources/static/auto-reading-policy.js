// This is a UX preflight, not authorization. The server must revalidate consent at dispatch.
export function allowsAutomaticReading(policy, bookId, provider, now = Date.now()) {
  if (!bookId || !provider || policy?.schemaVersion !== 1 ||
      !Number.isSafeInteger(policy.policyRevision) || policy.policyRevision < 1 ||
      !Array.isArray(policy.validConsents) || policy.defaultWindow?.mode !== 'AUTO_CURRENT') return false;
  return policy.validConsents.some(consent => {
    if (typeof consent?.consentId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(consent.consentId)) return false;
    if (consent.mode !== 'AUTO_CURRENT' || !Array.isArray(consent.allowedProviders) ||
        !(consent.allowedProviders.includes(provider) || consent.allowedProviders.includes('*'))) return false;
    if (consent.expiresAt != null && !(Date.parse(consent.expiresAt) > now)) return false;
    return consent.scope?.kind === 'ALL_BOOKS' ||
      (consent.scope?.kind === 'BOOK' && consent.scope.bookId === bookId);
  });
}
