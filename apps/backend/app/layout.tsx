export const metadata = { title: 'Pips-life Backend', description: 'Pips-life trading backend API' };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
