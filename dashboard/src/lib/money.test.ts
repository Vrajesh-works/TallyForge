import { describe, expect, it } from 'vitest';
import { formatMinor, parseMajor } from './money';

describe('formatMinor', () => {
  it('formats cents exactly', () => {
    expect(formatMinor(123456)).toBe('$1,234.56');
    expect(formatMinor(5)).toBe('$0.05');
    expect(formatMinor(0)).toBe('$0.00');
  });

  it('keeps the sign for negative balances', () => {
    expect(formatMinor(-250)).toBe('-$2.50');
  });

  it('does not lose precision on large amounts', () => {
    expect(formatMinor(9007199254740991)).toBe('$90,071,992,547,409.91');
  });

  it('respects zero-decimal currencies', () => {
    expect(formatMinor(1500, 'JPY')).toBe('¥1,500');
  });
});

describe('parseMajor', () => {
  it('converts major units to minor units', () => {
    expect(parseMajor('12.5')).toBe(1250);
    expect(parseMajor('1,000')).toBe(100000);
    expect(parseMajor('0.07')).toBe(7);
  });

  it('rejects blanks and junk', () => {
    expect(parseMajor('')).toBeNull();
    expect(parseMajor('abc')).toBeNull();
    expect(parseMajor('-5')).toBeNull();
  });
});
