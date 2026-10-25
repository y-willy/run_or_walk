/** Only allowlisted gateway codes escape. Never return upstream text, URLs or secret values. */
export type PublicApiFailure = 'KEY_REJECTED' | 'ACCESS_FORBIDDEN' | 'KEY_EXPIRED' | 'RATE_LIMITED' | 'REJECTED';
export function publicApiFailure(text: string): PublicApiFailure | null {
  let code: unknown;
  if (/<OpenAPI_ServiceResponse\b/.test(text) && /<cmmMsgHeader\b/.test(text)) {
    code = /<returnReasonCode>\s*([0-9]{1,2})\s*<\/returnReasonCode>/.exec(text)?.[1];
    // Some provider gateways omit the numeric code. Do not guess that authentication failed.
    if (code === undefined) return 'REJECTED';
  } else {
    try {
      const value = JSON.parse(text);
      const header = value?.OpenAPI_ServiceResponse?.cmmMsgHeader;
      if (!header || typeof header !== 'object') return null;
      code = header.returnReasonCode;
    } catch { return null; }
  }
  switch (String(code).replace(/^0(?=\d)/, '')) {
    case '30': return 'KEY_REJECTED';
    case '31': return 'KEY_EXPIRED';
    case '20': case '29': case '32': return 'ACCESS_FORBIDDEN';
    case '22': case '23': return 'RATE_LIMITED';
    default: return 'REJECTED';
  }
}
