import type { Metadata } from 'next';
import { Bricolage_Grotesque, Inter } from 'next/font/google';
// Order matters: tokens define the system, globals carries the rules the
// redesign did not touch (tables, status page, charts), components.css is the
// new surface layer and must win.
import './tokens.css';
import './globals.css';
import './components.css';

// A display face for the wordmark and headings, Inter for everything a person
// reads at 13px. Both are self-hosted by next/font at build, so the static
// export carries them and there is no third-party request at runtime.
const display = Bricolage_Grotesque({
  subsets: ['latin'],
  weight: ['600', '700', '800'],
  variable: '--font-display',
  display: 'swap',
});

const sans = Inter({
  subsets: ['latin'],
  variable: '--font-sans',
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'RentRadar — compare furniture rental prices in Bengaluru',
  description:
    'Compare furniture and appliance rental prices across Bengaluru providers in one place: monthly rent, deposit and true cost for your tenure, with a visible last-checked time.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en-IN" className={`${display.variable} ${sans.variable}`}>
      <body>
        {/* Two full-page washes, both inert: a cool glow behind the hero so the
            page has a light source, and a fine grain so large dark areas are
            not perfectly flat. */}
        <div className="page-glow" aria-hidden="true" />
        <div className="page-grain" aria-hidden="true" />
        {children}
      </body>
    </html>
  );
}
