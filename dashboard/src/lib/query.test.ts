import { describe, expect, it } from 'vitest';
import { EMPTY_FILTERS, transferQuery } from './query';

describe('transferQuery', () => {
  it('is empty when nothing is set', () => {
    expect(transferQuery(EMPTY_FILTERS)).toBe('');
  });

  it('maps every filter to the API parameter', () => {
    const q = new URLSearchParams(
      transferQuery(
        {
          status: 'PENDING',
          kind: 'TRANSFER',
          accountId: 'acc-1',
          fromDate: '2026-11-01',
          toDate: '2026-11-30',
          minAmount: '10',
          maxAmount: '99.99',
          held: 'true',
          q: '  rent ',
        },
        2,
        50,
      ).slice(1),
    );
    expect(Object.fromEntries(q)).toEqual({
      status: 'PENDING',
      kind: 'TRANSFER',
      accountId: 'acc-1',
      from: '2026-11-01T00:00:00.000Z',
      to: '2026-12-01T00:00:00.000Z',
      minAmount: '1000',
      maxAmount: '9999',
      held: 'true',
      q: 'rent',
      page: '2',
      size: '50',
    });
  });

  it('drops amounts that do not parse', () => {
    expect(transferQuery({ ...EMPTY_FILTERS, minAmount: 'lots' })).toBe('');
  });
});
