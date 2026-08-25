import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'RentRadar — compare furniture rental prices in Bengaluru',
  description:
    'Compare furniture and appliance rental prices across Bengaluru providers in one place: monthly rent, deposit and true cost for your tenure, with a visible last-checked time.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en-IN">
      <body>{children}</body>
    </html>
  );
}
