export function formatCompactNumber(num) {
  if (num === null || num === undefined || num === '') {
    return { display: '0', full: '0' };
  }
  
  if (typeof num === 'string' && num.includes('/')) {
    const parts = num.split('/').map(p => p.trim());
    const formatted = parts.map(p => {
      const cleanNum = Number(p.replace(/,/g, ''));
      if (isNaN(cleanNum)) return p;
      return formatCompactNumber(cleanNum).display;
    }).join(' / ');
    return { display: formatted, full: num };
  }

  const n = typeof num === 'number' ? num : Number(String(num).replace(/,/g, ''));
  if (isNaN(n)) return { display: String(num), full: String(num) };

  const full = n.toLocaleString('en-US');

  if (n < 100000) {
    return { display: full, full };
  }

  if (n >= 1000000000) {
    const compact = (n / 1000000000).toLocaleString('en-US', {
      maximumFractionDigits: n % 1000000000 === 0 ? 0 : 2,
    });
    return { display: `${compact}B`, full };
  }

  if (n >= 1000000) {
    const compact = (n / 1000000).toLocaleString('en-US', {
      maximumFractionDigits: n % 1000000 === 0 ? 0 : 2,
    });
    return { display: `${compact}M`, full };
  }

  const compact = (n / 1000).toLocaleString('en-US', {
    maximumFractionDigits: n % 1000 === 0 ? 0 : 1,
  });
  return { display: `${compact}K`, full };
}

export function nf(n) {
  return Number(n || 0).toLocaleString('en-US');
}
