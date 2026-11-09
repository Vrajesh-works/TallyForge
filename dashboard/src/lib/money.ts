const formatters = new Map<string, Intl.NumberFormat>();

function formatter(currency: string): Intl.NumberFormat {
  let f = formatters.get(currency);
  if (!f) {
    f = new Intl.NumberFormat('en-US', { style: 'currency', currency });
    formatters.set(currency, f);
  }
  return f;
}

/** Formats integer minor units (cents) as a currency string without floating point drift. */
export function formatMinor(minor: number, currency = 'USD'): string {
  const f = formatter(currency);
  const digits = f.resolvedOptions().maximumFractionDigits ?? 2;
  const scale = 10 ** digits;
  const sign = minor < 0 ? '-' : '';
  const abs = Math.abs(minor);
  const whole = Math.trunc(abs / scale);
  const frac = abs % scale;
  // Format the whole part with grouping, then splice the exact fraction back in.
  const wholeText = f.format(whole).replace(/[.,]0+$/, '');
  return digits === 0 ? `${sign}${wholeText}` : `${sign}${wholeText}.${String(frac).padStart(digits, '0')}`;
}

/** Parses a user-typed major-unit amount ("12.5", "1,000") into minor units, or null. */
export function parseMajor(text: string, digits = 2): number | null {
  const cleaned = text.replace(/,/g, '').trim();
  if (cleaned === '') return null;
  const match = /^(\d+)(?:\.(\d*))?$/.exec(cleaned);
  if (!match) return null;
  const frac = (match[2] ?? '').slice(0, digits).padEnd(digits, '0');
  return Number(match[1]) * 10 ** digits + (digits ? Number(frac) : 0);
}
