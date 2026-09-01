import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Pips-life',
  description: 'Pips-life MT5/MetaApi backend',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
