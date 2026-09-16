import "./globals.css";
import AppShell from "@/components/AppShell";

export const metadata = {
  title: "Dimensional Miner",
  description: "Dimensional Miner Internal Web Console",
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
