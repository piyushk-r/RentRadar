// Money is integer paise end to end (PRD section 17); the browser only formats it.

const rupeeFormatter = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

export function formatPaise(paise: number): string {
  const rupees = paise / 100;
  if (Number.isInteger(rupees)) {
    return `₹${rupeeFormatter.format(rupees)}`;
  }
  return `₹${new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(rupees)}`;
}
