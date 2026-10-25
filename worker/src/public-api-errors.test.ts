import { describe, expect, it } from 'vitest';
import { publicApiFailure } from './public-api-errors';

describe('public-data gateway classification without copying source text', () => {
  it.each([
    ['30', 'KEY_REJECTED'], ['31', 'KEY_EXPIRED'], ['20', 'ACCESS_FORBIDDEN'],
    ['29', 'ACCESS_FORBIDDEN'], ['32', 'ACCESS_FORBIDDEN'], ['22', 'RATE_LIMITED'],
    ['23', 'RATE_LIMITED'], ['12', 'REJECTED'],
  ])('classifies only the allowlisted code %s from XML and JSON', (code, expected) => {
    const xml = `<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>secret and URL</errMsg><returnReasonCode>${code}</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>`;
    expect(publicApiFailure(xml)).toBe(expected);
    expect(publicApiFailure(JSON.stringify({ OpenAPI_ServiceResponse: { cmmMsgHeader: { returnReasonCode: Number(code) } } }))).toBe(expected);
  });
  it('keeps missing or arbitrary codes separate from authentication', () => {
    expect(publicApiFailure('<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>key=secret</errMsg></cmmMsgHeader></OpenAPI_ServiceResponse>')).toBe('REJECTED');
    expect(publicApiFailure('<html>failure</html>')).toBeNull();
    expect(publicApiFailure('{"msgHeader":{"headerCd":"0"}}')).toBeNull();
  });
});
